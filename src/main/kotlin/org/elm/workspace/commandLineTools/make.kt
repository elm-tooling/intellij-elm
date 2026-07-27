package org.elm.workspace.commandLineTools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.workspace.compiler.COMPILER_OUTPUT_TOPIC
import org.elm.workspace.compiler.ERRORS_TOPIC
import org.elm.workspace.compiler.ElmBuildTargetType
import org.elm.workspace.compiler.ElmCompilerKind
import org.elm.workspace.compiler.ElmCompilerOutput
import org.elm.workspace.compiler.ElmError
import org.elm.workspace.compiler.ResolvedBuildTarget
import java.nio.file.Path

fun makeProject(
    project: Project,
    entryPoints: List<ResolvedBuildTarget>,
    currentFileInEditor: VirtualFile?
): Boolean {
    if (entryPoints.isEmpty()) return true

    // Test targets run via elm-test, not the compiler CLIs, so handle them on their own path.
    val (testTargets, compilerTargets) = entryPoints.partition { it.type == ElmBuildTargetType.TEST }

    var allSucceeded = true
    // Each target carries its own working directory (its elm.json directory), so group by that
    // together with the compiler; every group is one `elm make` working directory.
    val grouped = compilerTargets.groupBy { Triple(it.compilerKind, it.compilerPath, it.workDir) }
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
    for ((key, targets) in testTargets.groupByElmTest()) {
        val (exe, workDir) = key
        val succeeded = ElmTestCLI(exe).make(project, workDir, targets, currentFile = currentFileInEditor)
        allSucceeded = allSucceeded && succeeded
    }
    return allSucceeded
}

/**
 * Group test targets by their `elm-test` executable and working directory, mirroring how compiler
 * targets are grouped: every group is a single `elm-test make` working directory. Targets are known
 * to carry a non-null [ResolvedBuildTarget.testExecutablePath] because they are [ElmBuildTargetType.TEST].
 */
private fun List<ResolvedBuildTarget>.groupByElmTest(): Map<Pair<Path, Path>, List<ResolvedBuildTarget>> =
    groupBy { it.testExecutablePath!! to it.workDir }

/**
 * Build every target across all Elm projects and display the combined, deduplicated errors.
 *
 * Each individual build would normally post its errors to [ERRORS_TOPIC] and its console output to
 * [COMPILER_OUTPUT_TOPIC], where the tool window *replaces* what is currently shown. To show the
 * results from all targets at once we instead collect errors via a message sink (with file paths
 * made absolute so they resolve regardless of which project they came from) and console output via
 * an output sink, then post a single combined result for each.
 */
fun makeAllTargets(
    project: Project,
    targets: List<ResolvedBuildTarget>,
    currentFileInEditor: VirtualFile?
): Boolean {
    if (targets.isEmpty()) return true

    val (testTargets, compilerTargets) = targets.partition { it.type == ElmBuildTargetType.TEST }

    val sink = mutableListOf<ElmError>()
    val outputSink = mutableListOf<ElmCompilerOutput>()
    var allSucceeded = true
    val grouped = compilerTargets.groupBy { Triple(it.compilerKind, it.compilerPath, it.workDir) }
    for ((key, kindTargets) in grouped) {
        val (kind, path, workDir) = key
        val succeeded = when (kind) {
            ElmCompilerKind.ELM ->
                ElmCLI(path).make(
                    project, workDir, workDir, kindTargets,
                    jsonReport = true, currentFile = currentFileInEditor, messageSink = sink, outputSink = outputSink
                )
            ElmCompilerKind.LAMDERA ->
                LamderaCLI(path).make(
                    project, workDir, workDir, kindTargets,
                    jsonReport = true, currentFile = currentFileInEditor, messageSink = sink, outputSink = outputSink
                )
            ElmCompilerKind.WRAP ->
                WrapCLI(path).make(
                    project, workDir, workDir, kindTargets,
                    jsonReport = true, currentFile = currentFileInEditor, messageSink = sink, outputSink = outputSink
                )
        }
        allSucceeded = allSucceeded && succeeded
    }
    for ((key, elmTestTargets) in testTargets.groupByElmTest()) {
        val (exe, workDir) = key
        val succeeded = ElmTestCLI(exe).make(
            project, workDir, elmTestTargets, currentFile = currentFileInEditor,
            messageSink = sink, outputSink = outputSink
        )
        allSucceeded = allSucceeded && succeeded
    }

    project.messageBus.syncPublisher(COMPILER_OUTPUT_TOPIC).update(outputSink)

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
