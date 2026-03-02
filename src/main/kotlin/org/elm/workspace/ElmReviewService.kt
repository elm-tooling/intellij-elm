package org.elm.workspace

import com.google.gson.Strictness
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.elm.openapiext.GeneralCommandLine
import org.elm.openapiext.execute
import com.intellij.util.messages.Topic
import org.elm.ide.notifications.showBalloon
import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.readErrorReport
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

private val log = logger<ElmReviewService>()

@Service(Service.Level.PROJECT)
class ElmReviewService(private val project: Project) {

    private val runningReviews: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val messages: MutableMap<Path, List<ElmReviewError>> = ConcurrentHashMap()

    interface ElmReviewWatchListener {
        fun update(baseDirPath: Path, messages: List<ElmReviewError>)
    }

    companion object {
        val ELM_REVIEW_WATCH_TOPIC = Topic("elm-review watch errors", ElmReviewWatchListener::class.java)
    }

    fun messagesForCurrentProject(path: Path): List<ElmReviewError> =
        messages[path] ?: emptyList()

    fun runReview(projectBasePath: Path) {
        if (!project.elmSettings.toolchain.isElmReviewOnTheFlyEnabled) return
        if (!projectBasePath.resolve("elm.json").exists()) return
        if (!projectBasePath.resolve("review").exists()) return

        if (!runningReviews.add(projectBasePath)) return

        val elmReviewExecutablePath = project.elmToolchain.elmReviewPath ?: run {
            runningReviews.remove(projectBasePath)
            showError("Could not find elm-review executable", includeFixAction = true)
            return
        }
        val elmCompiler = project.elmToolchain.elmCLI
        val arguments = listOf("--report=json", "--namespace=intellij-elm") +
            "--config=./review" +
            if (elmCompiler == null) "" else "--compiler=${elmCompiler.elmExecutablePath}"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val command = GeneralCommandLine(elmReviewExecutablePath)
                    .withWorkDirectory(projectBasePath.toString())
                    .withParameters(arguments)
                augmentPathForCliTools(command.environment)

                val output = command.execute(
                    elmReviewTool,
                    project,
                    timeoutInMilliseconds = 120_000
                )

                val json = output.stderr.ifBlank { output.stdout }.trim()
                val reviewErrors = if (json.startsWith("{")) {
                    val reader = com.google.gson.stream.JsonReader(json.reader())
                    reader.strictness = Strictness.LENIENT
                    reader.readErrorReport()
                } else {
                    if (output.exitCode != 0 && json.isNotBlank()) {
                        log.warn("elm-review run failed for $projectBasePath: $json")
                    }
                    emptyList()
                }

                val previous = messages[projectBasePath]
                if (previous != null && reviewErrors.deepContentEquals(previous)) {
                    return@executeOnPooledThread
                }
                messages[projectBasePath] = reviewErrors
                log.debug("elm-review update for $projectBasePath: ${reviewErrors.size} messages")
                project.messageBus.syncPublisher(ELM_REVIEW_WATCH_TOPIC).update(projectBasePath, reviewErrors)
            } catch (t: Throwable) {
                if (!project.isDisposed) {
                    showError("elm-review failed: ${t.message}")
                }
                log.warn("elm-review run failed", t)
            } finally {
                runningReviews.remove(projectBasePath)
            }
        }
    }

    private fun showError(message: String, includeFixAction: Boolean = false) {
        val actions = if (includeFixAction) {
            arrayOf("Fix" to { project.elmWorkspace.showConfigureToolchainUI() })
        } else {
            emptyArray()
        }
        project.showBalloon(message, NotificationType.ERROR, *actions)
    }

    private fun augmentPathForCliTools(env: MutableMap<String, String>) {
        val existing = env["PATH"].orEmpty()
        val separator = java.io.File.pathSeparator
        val extraDirs = linkedSetOf<String>()
        extraDirs += "/opt/homebrew/bin"
        project.elmToolchain.elmReviewPath?.parent?.toString()?.let(extraDirs::add)
        project.elmToolchain.elmCompilerPath?.parent?.toString()?.let(extraDirs::add)

        val prefix = extraDirs.filter { it.isNotBlank() }.joinToString(separator)
        env["PATH"] = if (existing.isBlank()) prefix else "$prefix$separator$existing"

    }
}

val Project.elmReviewService: ElmReviewService
    get() = service()

private fun List<ElmReviewError>.deepContentEquals(other: List<ElmReviewError>): Boolean {
    if (size != other.size) return false
    return indices.all { this[it].deepContentEquals(other[it]) }
}

private fun ElmReviewError.deepContentEquals(other: ElmReviewError): Boolean =
    suppressed == other.suppressed &&
        path == other.path &&
        rule == other.rule &&
        message == other.message &&
        region == other.region &&
        formattedText == other.formattedText &&
        formattedChunks == other.formattedChunks &&
        ruleLink == other.ruleLink &&
        details == other.details &&
        fix == other.fix
