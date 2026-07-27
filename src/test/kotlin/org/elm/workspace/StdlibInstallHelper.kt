package org.elm.workspace

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import org.elm.fileTree
import org.elm.openapiext.pathAsPath
import org.elm.workspace.ElmToolchain.Companion.ELM_JSON
import org.elm.workspace.compiler.ElmBuildMode
import org.elm.workspace.compiler.ElmCompilerKind
import org.elm.workspace.compiler.ResolvedBuildTarget
import org.elm.workspace.compiler.nullOutputTargetPathString
import org.intellij.lang.annotations.Language
import java.nio.file.Paths

/*
Some of the Elm tests depend on having certain Elm packages installed in the global location.
And since Elm has pretty poor facilities for installing packages, we have to resort to tricks
like asking it to compile a dummy application.
 */

interface ElmStdlibVariant {

    /**
     * A description of the packages that we want to be installed, as an application `elm.json`.
     *
     * The `elm-version` must match the compiler the developer has installed (the compiler rejects a
     * mismatch) and, because it also selects which `~/.elm/<version>/packages/` directory the plugin
     * resolves dependencies from, it must match the version the stdlib was installed under.
     */
    fun manifestFor(elmVersion: Version): String

    /**
     * Install Elm 0.19 stdlib in the default location ($HOME/.elm)
     */
    fun ensureElmStdlibInstalled(project: Project, toolchain: ElmToolchain)
}

object EmptyElmStdlibVariant : ElmStdlibVariant {
    override fun ensureElmStdlibInstalled(project: Project, toolchain: ElmToolchain) {
        // Don't do anything. The Elm compiler would refuse to use this manifest
        // since it's missing `elm/core` and other required packages. Also, it's
        // faster if we short-circuit things here rather than invoking the Elm
        // compiler in an external process.
    }

    @Language("JSON")
    override fun manifestFor(elmVersion: Version): String = """
            {
                "type": "application",
                "source-directories": [
                    "."
                ],
                "elm-version": "$elmVersion",
                "dependencies": {
                    "direct": {},
                    "indirect": {}
                },
                "test-dependencies": {
                    "direct": {},
                    "indirect": {}
                }
            }
            """.trimIndent()
}

/**
 * Describes a "minimal" installation of the Elm stdlib. This is the bare minimum
 * required to compile an Elm application.
 */
object MinimalElmStdlibVariant : ElmStdlibVariant {
    // The Elm compiler refuses to compile an application whose `elm.json` declares a different
    // `elm-version` than the compiler itself. Parameterize the version so this manifest matches
    // whichever 0.19.x compiler the developer happens to have installed (0.19.1, 0.19.2, ...).
    @Language("JSON")
    override fun manifestFor(elmVersion: Version): String = """
            {
                "type": "application",
                "source-directories": [
                    "."
                ],
                "elm-version": "$elmVersion",
                "dependencies": {
                    "direct": {
                        "elm/core": "1.0.0",
                        "elm/json": "1.0.0"
                    },
                    "indirect": {}
                },
                "test-dependencies": {
                    "direct": {},
                    "indirect": {}
                }
            }
            """.trimIndent()

    override fun ensureElmStdlibInstalled(project: Project, toolchain: ElmToolchain) {
        val elmCLI = toolchain.elmCLI
                ?: error("Must have a path to the Elm compiler to install Elm stdlib")

        val compilerVersion = elmCLI.queryVersion(project).orNull()
                ?: error("Could not query the Elm compiler version")
        require(compilerVersion != Version(0, 18, 0))

        // Create the dummy Elm project on-disk (real file system) and invoke the Elm compiler on it.
        val onDiskTmpDir = LocalFileSystem.getInstance()
                .refreshAndFindFileByIoFile(FileUtil.createTempDirectory("elm-stdlib-variant", null, true))
                ?: error("Could not create on-disk temp dir for Elm stdlib installation")

        fileTree {
            project(ELM_JSON, manifestFor(compilerVersion))
            elm("Main.elm")
        }.create(project, onDiskTmpDir)

        val entryPoint = ResolvedBuildTarget(
            name = "Main",
            type = org.elm.workspace.compiler.ElmBuildTargetType.APPLICATION,
            workDir = onDiskTmpDir.pathAsPath,
            inputPath = Paths.get("Main.elm"),
            inputPathForCompiler = "Main.elm",
            outputPathForCompiler = nullOutputTargetPathString(),
            mode = ElmBuildMode.NONE,
            compilerKind = ElmCompilerKind.ELM,
            compilerPath = elmCLI.elmExecutablePath,
            compileOnSave = false,
            offset = 0
        )
        elmCLI.make(project, onDiskTmpDir.pathAsPath, null, listOf(entryPoint))
    }
}
