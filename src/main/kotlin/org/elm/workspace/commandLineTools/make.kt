package org.elm.workspace.commandLineTools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.workspace.ElmProject
import org.elm.workspace.compiler.ERRORS_TOPIC
import org.elm.workspace.compiler.ElmCompilerKind
import org.elm.workspace.compiler.ElmError
import org.elm.workspace.compiler.ResolvedBuildTarget
import java.nio.file.Path

fun makeProject(
    elmProject: ElmProject,
    project: Project,
    entryPoints: List<ResolvedBuildTarget>,
    currentFileInEditor: VirtualFile?
): Boolean {
    if (entryPoints.isEmpty()) return true

    var allSucceeded = true
    val grouped = entryPoints.groupBy { it.compilerKind to it.compilerPath }
    for ((kindAndPath, targets) in grouped) {
        val (kind, path) = kindAndPath
        val succeeded = when (kind) {
            ElmCompilerKind.ELM ->
                ElmCLI(path).make(
                    project,
                    elmProject.projectDirPath,
                    elmProject,
                    targets,
                    jsonReport = true,
                    currentFile = currentFileInEditor
                )
            ElmCompilerKind.LAMDERA ->
                LamderaCLI(path).make(
                    project,
                    elmProject.projectDirPath,
                    elmProject,
                    targets,
                    jsonReport = true,
                    currentFile = currentFileInEditor
                )
            ElmCompilerKind.WRAP ->
                WrapCLI(path).make(
                    project,
                    elmProject.projectDirPath,
                    elmProject,
                    targets,
                    jsonReport = true,
                    currentFile = currentFileInEditor
                )
        }
        allSucceeded = allSucceeded && succeeded
    }
    return allSucceeded
}

/**
 * Build every target across all Elm projects and display the combined, deduplicated errors.
 *
 * Each individual build would normally post its errors to [ERRORS_TOPIC], where the tool window
 * *replaces* the currently shown messages. To show errors from all targets at once we instead
 * collect them via a message sink (with file paths made absolute so they resolve regardless of
 * which project they came from), deduplicate, and post a single combined result.
 */
fun makeAllTargets(
    project: Project,
    targetsByProject: List<Pair<ElmProject, List<ResolvedBuildTarget>>>,
    currentFileInEditor: VirtualFile?
): Boolean {
    val relevant = targetsByProject.filter { it.second.isNotEmpty() }
    if (relevant.isEmpty()) return true

    val sink = mutableListOf<ElmError>()
    var allSucceeded = true
    for ((elmProject, targets) in relevant) {
        val grouped = targets.groupBy { it.compilerKind to it.compilerPath }
        for ((kindAndPath, kindTargets) in grouped) {
            val (kind, path) = kindAndPath
            val succeeded = when (kind) {
                ElmCompilerKind.ELM ->
                    ElmCLI(path).make(
                        project, elmProject.projectDirPath, elmProject, kindTargets,
                        jsonReport = true, currentFile = currentFileInEditor, messageSink = sink
                    )
                ElmCompilerKind.LAMDERA ->
                    LamderaCLI(path).make(
                        project, elmProject.projectDirPath, elmProject, kindTargets,
                        jsonReport = true, currentFile = currentFileInEditor, messageSink = sink
                    )
                ElmCompilerKind.WRAP ->
                    WrapCLI(path).make(
                        project, elmProject.projectDirPath, elmProject, kindTargets,
                        jsonReport = true, currentFile = currentFileInEditor, messageSink = sink
                    )
            }
            allSucceeded = allSucceeded && succeeded
        }
    }

    val sorted = sink.distinct().sortedWith(
        compareBy(
            { it.location?.path },
            { it.location?.region?.start?.line },
            { it.location?.region?.start?.column }
        )
    )
    val messages = if (currentFileInEditor != null) {
        val currentPath = currentFileInEditor.path
        val predicate: (ElmError) -> Boolean = { it.location?.path == currentPath }
        sorted.filter(predicate) + sorted.filterNot(predicate)
    } else sorted

    val baseDirPath = relevant.first().first.projectDirPath
    project.messageBus.syncPublisher(ERRORS_TOPIC).update(baseDirPath, messages, "", 0)
    return messages.isEmpty() && allSucceeded
}

internal fun ElmError.withAbsolutePath(workDir: Path): ElmError {
    val loc = location ?: return this
    return copy(location = loc.copy(path = workDir.resolve(loc.path).normalize().toString()))
}
