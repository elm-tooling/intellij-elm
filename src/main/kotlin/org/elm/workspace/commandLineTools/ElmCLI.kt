package org.elm.workspace.commandLineTools

import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.openapiext.*
import org.elm.workspace.ParseException
import org.elm.workspace.Version
import org.elm.workspace.compiler.ERRORS_TOPIC
import org.elm.workspace.compiler.ElmError
import org.elm.workspace.compiler.COMPILER_OUTPUT_TOPIC
import org.elm.workspace.compiler.ElmCompilerOutput
import org.elm.workspace.compiler.ResolvedBuildTarget
import org.elm.workspace.compiler.elmJsonToCompilerMessages
import org.elm.workspace.elmCompilerTool
import org.elm.ide.statusbar.elmTaskStatus
import java.nio.file.Path

/**
 * Interact with external `elm` process (the compiler, package manager, etc.)
 */
class ElmCLI(val elmExecutablePath: Path) {

    fun make(
        project: Project,
        workDir: Path,
        // The base dir for reporting errors to the tool window, or null to not report (e.g. an
        // internal dependency-install build). Error file paths from `elm` are absolute regardless.
        baseDirForErrors: Path?,
        entryPoints: List<ResolvedBuildTarget>,
        jsonReport: Boolean = false,
        currentFile: VirtualFile? = null,
        messageSink: MutableList<ElmError>? = null,
        // Collects console output for an aggregated build (e.g. "Build all") instead of posting it
        // here; the caller posts every command's output at once. Null for a normal single build.
        outputSink: MutableList<ElmCompilerOutput>? = null
    ): Boolean {

        if (entryPoints.isEmpty()) return true

        project.elmTaskStatus.compilerStarted()
        try {
            val allMessages = mutableListOf<ElmError>()
            val outputs = mutableListOf<ElmCompilerOutput>()
            var allSucceeded = true
            // Elm 0.19.2 has a bug where the error locations reported by `--report=json` are
            // off by one: https://github.com/elm/compiler/issues/2358
            // Correct them by adding 1 to each reported row and column. Computed
            // lazily so we only run `elm --version` when there is actually an error to adjust.
            val rowAndColumnOffset by lazy {
                if (queryVersion(project).orNull()?.xyz == Version(0, 19, 2)) 1 else 0
            }
            for (entry in entryPoints) {
                val params = entry.makeParameters()

                val commandLine = GeneralCommandLine(elmExecutablePath)
                    .withWorkDirectory(workDir)
                    .withParameters(*params.toTypedArray())
                    .apply { if (jsonReport) addParameter("--report=json") }
                val output = commandLine.execute(elmCompilerTool, project)
                outputs += ElmCompilerOutput(
                    elmCompilerTool,
                    commandLine.commandLineString,
                    output.stdout,
                    output.stderr,
                    output.exitCode
                )
                if (!output.isSuccess) {
                    allSucceeded = false
                }
                val json = output.stderr
                val regex = "\\{.*}".toRegex()
                val cleansedJson = regex.find(json)?.value
                if (!cleansedJson.isNullOrEmpty()) {
                    allMessages += elmJsonToCompilerMessages(cleansedJson, rowAndColumnOffset)
                }
            }

            if (outputSink != null) {
                outputSink += outputs
            } else {
                project.messageBus.syncPublisher(COMPILER_OUTPUT_TOPIC).update(outputs)
            }

            val sortedMessages = allMessages.sortedWith(
                compareBy(
                    { it.location?.moduleName },
                    { it.location?.region?.start?.line },
                    { it.location?.region?.start?.column }
                )
            )
            val messages = if (currentFile != null) {
                val predicate: (ElmError) -> Boolean = { it.location?.path == currentFile.path }
                sortedMessages.filter(predicate) + sortedMessages.filterNot(predicate)
            } else sortedMessages

            if (messageSink != null) {
                // Collect messages for an aggregated build (e.g. "Build all") instead of
                // posting them here; the caller deduplicates and posts once.
                messageSink += messages.map { it.withAbsolutePath(workDir) }
                return messages.isEmpty() && allSucceeded
            }

            if (baseDirForErrors == null) {
                // Internal build (e.g. dependency install from ElmWorkspaceService); don't report.
                return allSucceeded
            } else {
                val first = entryPoints.first()
                project.messageBus.syncPublisher(ERRORS_TOPIC)
                    .update(baseDirForErrors, messages, first.inputPathForCompiler, first.offset)
            }
            return messages.isEmpty() && allSucceeded
        } finally {
            project.elmTaskStatus.compilerFinished()
        }
    }

    fun queryVersion(project: Project): Result<Version> {
        // Output of `elm --version` is a single line containing the version number (e.g. `0.19.0\n`)
        val firstLine = try {
            GeneralCommandLine(elmExecutablePath).withParameters("--version")
                    .execute(elmCompilerTool, project)
                    .stdoutLines
                    .firstOrNull()
        } catch (e: ExecutionException) {
            return Result.Err("failed to run elm: ${e.message}")
        }

        if (firstLine == null) {
            return Result.Err("no output from elm")
        }

        return try {
            Result.Ok(Version.parse(firstLine))
        } catch (e: ParseException) {
            Result.Err("could not parse Elm version: ${e.message}")
        }
    }
}
