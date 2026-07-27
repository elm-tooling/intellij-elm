package org.elm.workspace.ui

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScopesCore
import org.elm.lang.core.psi.elements.ElmFunctionDeclarationLeft
import org.elm.lang.core.stubs.index.find
import org.elm.openapiext.findFileByPathTestAware
import org.elm.workspace.ElmApplicationProject
import org.elm.workspace.ElmPackageProject
import org.elm.workspace.ElmProject
import org.elm.workspace.ElmSuggest
import org.elm.workspace.LamderaApplicationProject
import org.elm.workspace.compiler.ElmBuildTargetConfig
import org.elm.workspace.compiler.ElmBuildTargetType
import org.elm.workspace.compiler.ElmCompilerKind
import org.elm.workspace.compiler.toPathOrNull
import org.elm.workspace.elmCompilerTool
import org.elm.workspace.elmWorkspace
import org.elm.workspace.lamderaCompilerTool
import java.nio.file.Paths

/**
 * A single auto-detected build target the user can pick when adding a new target.
 *
 * [label] is what's shown in the chooser popup; [config] is the target that gets created if picked.
 */
data class BuildTargetSuggestion(
    val label: String,
    val config: ElmBuildTargetConfig
)

/**
 * Inspect every Elm project in the workspace and propose build targets the user is likely to want:
 *
 *  - **Packages** yield a single "package" target (type-checked via `elm make` with no args).
 *  - **Lamdera applications** yield "Lamdera Frontend"/"Lamdera Backend" targets for whichever of
 *    `src/Frontend.elm` / `src/Backend.elm` exist (Lamdera projects always use those exact names).
 *  - **Plain applications** yield one target per `.elm` file (within the project's source
 *    directories) that declares a top-level `main`.
 *
 * Application labels are always prefixed with the owning project's name so suggestions from
 * different projects (e.g. two `src/Main.elm` files) can be told apart.
 *
 * Suggestions that would duplicate an [existingTargets] entry are filtered out: a package
 * suggestion whose `elm.json` is already a package target, or an application/Lamdera suggestion
 * whose entry `.elm` file is already an application target.
 */
fun detectBuildTargetSuggestions(
    project: Project,
    existingTargets: List<ElmBuildTargetConfig>
): List<BuildTargetSuggestion> =
    buildTargetSuggestionsFor(project, project.elmWorkspace.allProjects, existingTargets)

/**
 * The workspace-independent core of [detectBuildTargetSuggestions], taking the [elmProjects]
 * explicitly. Exposed (rather than folded into the function above) so tests can exercise it with
 * hand-built projects — in particular a [LamderaApplicationProject], whose dependencies can't be
 * resolved from the package cache without first installing Lamdera's packages into `~/.elm`.
 */
internal fun buildTargetSuggestionsFor(
    project: Project,
    elmProjects: List<ElmProject>,
    existingTargets: List<ElmBuildTargetConfig>
): List<BuildTargetSuggestion> {
    if (elmProjects.isEmpty()) return emptyList()

    val tools = ElmSuggest.suggestTools(project)
    val elmCompilerPath = tools[elmCompilerTool]?.toString().orEmpty()
    val lamderaCompilerPath = tools[lamderaCompilerTool]?.toString().orEmpty()

    val suggestions = mutableListOf<BuildTargetSuggestion>()
    for (elmProject in elmProjects) {
        when (elmProject) {
            is ElmPackageProject -> {
                suggestions += BuildTargetSuggestion(
                    label = "${elmProject.name} (package)",
                    config = ElmBuildTargetConfig(
                        name = elmProject.name,
                        type = ElmBuildTargetType.PACKAGE,
                        inputPath = elmProject.manifestPath.toString(),
                        compilerKind = ElmCompilerKind.ELM,
                        compilerPath = elmCompilerPath,
                        compileOnSave = true
                    )
                )
            }

            is LamderaApplicationProject -> {
                val entries = listOf(
                    "Frontend.elm" to "Lamdera Frontend",
                    "Backend.elm" to "Lamdera Backend"
                )
                for ((fileName, targetName) in entries) {
                    val path = elmProject.projectDirPath.resolve("src").resolve(fileName)
                    if (findFileByPathTestAware(path) == null) continue
                    suggestions += BuildTargetSuggestion(
                        label = "${elmProject.presentableName}: $targetName",
                        config = ElmBuildTargetConfig(
                            name = targetName,
                            type = ElmBuildTargetType.APPLICATION,
                            inputPath = path.toString(),
                            compilerKind = ElmCompilerKind.LAMDERA,
                            compilerPath = lamderaCompilerPath,
                            compileOnSave = true
                        )
                    )
                }
            }

            is ElmApplicationProject -> {
                for (mainFile in findTopLevelMainFiles(project, elmProject)) {
                    val relativePath = relativeLabelPath(elmProject, mainFile)
                    suggestions += BuildTargetSuggestion(
                        label = "${elmProject.presentableName}: $relativePath (application)",
                        config = ElmBuildTargetConfig(
                            name = relativePath,
                            type = ElmBuildTargetType.APPLICATION,
                            inputPath = mainFile.path,
                            compilerKind = ElmCompilerKind.ELM,
                            compilerPath = elmCompilerPath,
                            compileOnSave = true
                        )
                    )
                }
            }

            // Other project kinds (e.g. elm-review configs) don't map to a user build target.
            else -> {}
        }
    }
    return suggestions.filterNot { suggestion -> existingTargets.any { it.matchesInput(suggestion.config) } }
}

/**
 * True if this target already covers the same input as [suggestion]: a package target with the
 * same `elm.json`, or an application target with the same entry `.elm` file. Paths are compared
 * after normalization so `./src/Main.elm` and `src/Main.elm` are treated as the same file.
 */
private fun ElmBuildTargetConfig.matchesInput(suggestion: ElmBuildTargetConfig): Boolean {
    if (type != suggestion.type) return false
    val a = inputPath.trim().toPathOrNull()?.normalize()
    val b = suggestion.inputPath.trim().toPathOrNull()?.normalize()
    return if (a != null && b != null) a == b else inputPath.trim() == suggestion.inputPath.trim()
}

/**
 * Return the `.elm` files within [elmProject]'s source directories that declare a top-level `main`.
 *
 * Backed by the stub index, so no files are parsed. Returns nothing while indexing is in progress
 * (the suggestions are best-effort; the user can always pick "Empty" and fill things in by hand).
 */
private fun findTopLevelMainFiles(project: Project, elmProject: ElmProject): List<VirtualFile> {
    if (DumbService.isDumb(project)) return emptyList()

    val srcDirs = elmProject.absoluteSourceDirectories.mapNotNull { findFileByPathTestAware(it) }
    if (srcDirs.isEmpty()) return emptyList()

    val scope = GlobalSearchScopesCore.directoriesScope(project, true, *srcDirs.toTypedArray())
    return try {
        find("main", project, scope)
            .filterIsInstance<ElmFunctionDeclarationLeft>()
            .filter { it.isTopLevel }
            .mapNotNull { it.containingFile?.virtualFile }
            .distinct()
    } catch (e: IndexNotReadyException) {
        emptyList()
    }
}

/** The path of [file] relative to the project directory, using `/` separators (e.g. `src/Main.elm`). */
private fun relativeLabelPath(elmProject: ElmProject, file: VirtualFile): String =
    runCatching { elmProject.projectDirPath.relativize(Paths.get(file.path)).toString() }
        .getOrDefault(file.path)
        .replace('\\', '/')
