package org.elm.workspace.commandLineTools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.workspace.compiler.ERRORS_TOPIC
import org.elm.workspace.compiler.ElmCompilerKind
import org.elm.workspace.compiler.ElmError
import org.elm.workspace.compiler.ResolvedBuildTarget
import java.nio.file.Path

fun makeProject(
    project: Project,
    entryPoints: List<ResolvedBuildTarget>,
    currentFileInEditor: VirtualFile?
): Boolean {
    if (entryPoints.isEmpty()) return true

    var allSucceeded = true
    // Each target carries its own working directory (its elm.json directory), so group by that
    // together with the compiler; every group is one `elm make` working directory.
    val grouped = entryPoints.groupBy { Triple(it.compilerKind, it.compilerPath, it.workDir) }
    for ((key, targets) in grouped) {
        val (kind, path, workDir) = key
        val succeeded = when (kind) {
            ElmCompilerKind.ELM ->
                ElmCLI(path).make(project, workDir, workDir, targets, jsonReport = true, currentFile = currentFileInEditor)
            ElmCompilerKind.LAMDERA ->
                LamderaCLI(path).make(project, workDir, workDir, targets, jsonReport = true, currentFile = currentFileInEditor)
            ElmCompilerKind.WRAP ->
                WrapCLI(path).make(project, workDir, workDir, targets, jsonReport = true, currentFile = currentFileInEditor)
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
    targets: List<ResolvedBuildTarget>,
    currentFileInEditor: VirtualFile?
): Boolean {
    if (targets.isEmpty()) return true

    val sink = mutableListOf<ElmError>()
    var allSucceeded = true
    val grouped = targets.groupBy { Triple(it.compilerKind, it.compilerPath, it.workDir) }
    for ((key, kindTargets) in grouped) {
        val (kind, path, workDir) = key
        val succeeded = when (kind) {
            ElmCompilerKind.ELM ->
                ElmCLI(path).make(
                    project, workDir, workDir, kindTargets,
                    jsonReport = true, currentFile = currentFileInEditor, messageSink = sink
                )
            ElmCompilerKind.LAMDERA ->
                LamderaCLI(path).make(
                    project, workDir, workDir, kindTargets,
                    jsonReport = true, currentFile = currentFileInEditor, messageSink = sink
                )
            ElmCompilerKind.WRAP ->
                WrapCLI(path).make(
                    project, workDir, workDir, kindTargets,
                    jsonReport = true, currentFile = currentFileInEditor, messageSink = sink
                )
        }
        allSucceeded = allSucceeded && succeeded
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

    val baseDirPath = targets.first().workDir
    project.messageBus.syncPublisher(ERRORS_TOPIC).update(baseDirPath, messages, "", 0)
    return messages.isEmpty() && allSucceeded
}

internal fun ElmError.withAbsolutePath(workDir: Path): ElmError {
    val loc = location ?: return this
    return copy(location = loc.copy(path = workDir.resolve(loc.path).normalize().toString()))
}
