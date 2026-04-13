package org.elm.workspace.commandLineTools

import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.openapiext.*
import org.elm.workspace.ElmProject
import org.elm.workspace.ParseException
import org.elm.workspace.Version
import org.elm.workspace.compiler.ERRORS_TOPIC
import org.elm.workspace.compiler.ElmError
import org.elm.workspace.compiler.COMPILER_OUTPUT_TOPIC
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
        elmProject: ElmProject?,
        entryPoints: List<ResolvedBuildTarget>,
        jsonReport: Boolean = false,
        currentFile: VirtualFile? = null
    ): Boolean {

        if (entryPoints.isEmpty()) return true

        project.elmTaskStatus.compilerStarted()
        try {
            val allMessages = mutableListOf<ElmError>()
            var allSucceeded = true
            for (entry in entryPoints) {
                val modeFlag = entry.mode.asFlag()
                val params = mutableListOf("make")
                if (entry.inputPathForCompiler.isNotBlank()) {
                    params += entry.inputPathForCompiler
                }
                params += "--output=${entry.outputPathForCompiler}"
                if (modeFlag != null) params += modeFlag

                val commandLine = GeneralCommandLine(elmExecutablePath)
                    .withWorkDirectory(workDir)
                    .withParameters(*params.toTypedArray())
                    .apply { if (jsonReport) addParameter("--report=json") }
                val output = commandLine.execute(elmCompilerTool, project)
                project.messageBus.syncPublisher(COMPILER_OUTPUT_TOPIC).update(
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
                    allMessages += elmJsonToCompilerMessages(cleansedJson)
                }
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

            if (elmProject == null) {
                // from ElmWorkSpaceService
                return allSucceeded
                // TODO Lamdera
                //  org.elm.workspace.log.error("Failed to install deps: Elm compiler failed: ${output.stderr}")
            } else {
                val first = entryPoints.first()
                fun postErrors() = project.messageBus.syncPublisher(ERRORS_TOPIC)
                    .update(elmProject.projectDirPath, messages, first.inputPathForCompiler, first.offset)
                when {
                    isUnitTestMode -> postErrors()
                    else -> postErrors()
                }
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
