package org.elm.workspace.commandLineTools

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import org.elm.ide.statusbar.elmTaskStatus
import org.elm.openapiext.*
import org.elm.workspace.*
import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.readErrorReport
import java.nio.file.Path

private val log = logger<ElmReviewCLI>()


/**
 * Interact with external `elm-review` process.
 */
class ElmReviewCLI(private val elmReviewExecutablePath: Path) {

    fun runReview(project: Project, elmProject: ElmProject, compilerPath: Path?, currentFile: VirtualFile? = null) {

        // This option makes the CLI output non-JSON output, but can be useful to debug what is happening
        // "--debug",

        val arguments = buildList {
            add("--report=json")
            add("--namespace=intellij-elm")
            if (elmProject is ElmApplicationProject) add("--config=.")
            if (compilerPath != null) add("--compiler=$compilerPath")
        }

        val generalCommandLine = GeneralCommandLine(elmReviewExecutablePath).withWorkDirectory(elmProject.projectDirPath.toString()).withParameters(arguments)

        executeReviewAsync(project) { indicator ->
            project.elmTaskStatus.reviewStarted()
            try {
                indicator.text = "reviewing ${elmProject.projectDirPath}"
                val handler = CapturingProcessHandler(generalCommandLine)
                val processKiller = Disposable { handler.destroyProcess() }

                Disposer.register(project, processKiller)
                try {
                    val output = handler.runProcess()
                    val alreadyDisposed = runReadAction { project.isDisposed }
                    if (alreadyDisposed) {
                        throw ExecutionException("External command failed to start")
                    }
                    if (output.exitCode != 0) {
                        log.warn("elm-review exited with code ${output.exitCode} and output ${output.stdoutLines}")
                    }
                    val json = extractElmReviewJson(output.stdout, output.stderr)
                    val messages = if (json.isNullOrBlank())
                        emptyList()
                    else {
                        val reader = JsonReader(json.byteInputStream().bufferedReader())
                        reader.strictness = Strictness.LENIENT
                        val msgs = reader.readErrorReport().sortedWith(
                            compareBy(
                                { it.path },
                                { it.region!!.start!!.line },
                                { it.region!!.start!!.column }
                            ))
                        if (currentFile != null) {
                            val predicate: (ElmReviewError) -> Boolean = { it.path == currentFile.pathRelative(project).toString() }
                            val sortedMessages = msgs.filter(predicate) + msgs.filterNot(predicate)
                            sortedMessages
                        } else msgs
                    }
                    if (!isUnitTestMode) {
                        indicator.text = "Review finished"
                        ApplicationManager.getApplication().invokeLater {
                            project.messageBus.syncPublisher(ELM_REVIEW_ERRORS_TOPIC).update(elmProject.projectDirPath, messages, null, 0)
                        }
                    }
                } finally {
                    Disposer.dispose(processKiller)
                }
            } finally {
                project.elmTaskStatus.reviewFinished()
            }
        }
    }

    fun queryVersion(project: Project): Result<Version> {
        val firstLine = try {
            val arguments: List<String> = listOf("--version")
            GeneralCommandLine(elmReviewExecutablePath)
                .withParameters(arguments)
                .execute(elmReviewTool, project)
                .stdoutLines
                .firstOrNull()
        } catch (e: ExecutionException) {
            return Result.Err("failed to run elm-review: ${e.message}")
        } ?: return Result.Err("no output from elm-review")

        return try {
            Result.Ok(Version.parse(firstLine))
        } catch (e: ParseException) {
            Result.Err("invalid elm-review version: ${e.message}")
        }
    }
}

@Throws(ExecutionException::class)
fun executeReviewAsync(
    project: Project,
    task: (indicator: ProgressIndicator) -> Unit
) {
    runBackgroundableTask(elmReviewTool, project, true, task)
}

val ELM_REVIEW_ERRORS_TOPIC = Topic("elm-review errors", ElmReviewErrorsListener::class.java)

interface ElmReviewErrorsListener {
    @Suppress("unused")
    fun update(baseDirPath: Path, messages: List<ElmReviewError>, targetPath: String?, offset: Int)
}

private fun extractElmReviewJson(stdout: String, stderr: String): String? {
    fun from(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }

    return from(stdout)
        ?: from(stderr)
        ?: from("$stdout\n$stderr")
}
