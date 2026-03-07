package org.elm.workspace

import com.google.gson.Strictness
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.messages.Topic
import org.elm.ide.notifications.showBalloon
import org.elm.ide.statusbar.elmTaskStatus
import org.elm.openapiext.execute
import org.elm.workspace.commandLineTools.buildReviewCommandLine
import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.readErrorReport
import org.elm.workspace.elmreview.resolveElmReviewCompiler
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists

private val log = logger<ElmReviewService>()

@Service(Service.Level.PROJECT)
class ElmReviewService(private val project: Project) {

    private val runningReviews: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val pendingReviews: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val missingExecutableNotified: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val messages: MutableMap<Path, List<ElmReviewError>> = ConcurrentHashMap()
    private val lastRequestedStampByFile: MutableMap<Path, Long> = ConcurrentHashMap()
    private val reviewGeneration = AtomicLong(0)
    private val requestedGenerationByProject: MutableMap<Path, Long> = ConcurrentHashMap()
    private val completedGenerationByProject: MutableMap<Path, Long> = ConcurrentHashMap()

    interface ElmReviewWatchListener {
        fun update(baseDirPath: Path, messages: List<ElmReviewError>)
    }

    companion object {
        val ELM_REVIEW_WATCH_TOPIC = Topic("elm-review watch errors", ElmReviewWatchListener::class.java)
    }

    fun messagesForCurrentProject(path: Path): List<ElmReviewError> =
        messages[path] ?: emptyList()

    fun hasFreshResults(path: Path): Boolean {
        val requested = requestedGenerationByProject[path] ?: return true
        val completed = completedGenerationByProject[path] ?: 0L
        return completed >= requested
    }

    fun runReview(projectBasePath: Path) {
        runReview(projectBasePath, elmProjectHint = null)
    }

    fun runReview(projectBasePath: Path, elmProjectHint: ElmProject?) {
        if (!project.elmSettings.toolchain.isElmReviewOnTheFlyEnabled) return
        if (!projectBasePath.resolve("elm.json").exists()) return
        if (!projectBasePath.resolve("review").exists()) return

        val runGeneration = requestedGenerationByProject[projectBasePath] ?: markReviewRequested(projectBasePath)

        if (!runningReviews.add(projectBasePath)) {
            pendingReviews.add(projectBasePath)
            return
        }

        val elmReviewExecutablePath = project.elmToolchain.elmReviewPath ?: run {
            runningReviews.remove(projectBasePath)
            if (missingExecutableNotified.add(projectBasePath)) {
                showError("Could not find elm-review executable", includeFixAction = true)
            }
            return
        }
        missingExecutableNotified.remove(projectBasePath)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) {
                runningReviews.remove(projectBasePath)
                completedGenerationByProject.merge(projectBasePath, runGeneration, ::maxOf)
                pendingReviews.remove(projectBasePath)
                return@executeOnPooledThread
            }
            project.elmTaskStatus.reviewStarted()
            val suggestedTools = ElmSuggest.suggestTools(project)
            try {
                val compilerResolution = resolveElmReviewCompiler(
                    project = project,
                    projectBasePath = projectBasePath,
                    elmProjectHint = elmProjectHint,
                    suggestedTools = suggestedTools
                )
                val compilerPathForReview = compilerResolution.path
                val arguments = buildList {
                    add("--report=json")
                    add("--namespace=intellij-elm")
                    add("--config=./review")
                    compilerPathForReview?.let { add("--compiler=$it") }
                }
                val commandText = buildCommandText(elmReviewExecutablePath, projectBasePath, arguments)
                val command = buildReviewCommandLine(
                    executablePath = elmReviewExecutablePath,
                    workDir = projectBasePath,
                    arguments = arguments,
                    compilerPath = compilerPathForReview,
                    suggestedTools = suggestedTools
                )

                val output = command.execute(
                    elmReviewTool,
                    project,
                    timeoutInMilliseconds = 120_000
                )

                val json = extractElmReviewJson(output.stdout, output.stderr)
                val reviewErrors = if (json != null) {
                    val reader = com.google.gson.stream.JsonReader(json.reader())
                    reader.strictness = Strictness.LENIENT
                    reader.readErrorReport()
                } else {
                    if (output.exitCode != 0) {
                        val stderr = output.stderr.trim()
                        val stdout = output.stdout.trim()
                        val details = when {
                            stderr.isNotBlank() -> stderr
                            stdout.isNotBlank() -> stdout
                            else -> "<no output>"
                        }
                        log.warn("elm-review run failed for $projectBasePath: $details")
                        if (!project.isDisposed) {
                            val firstLine = details.lineSequence().firstOrNull().orEmpty()
                            showError("elm-review failed: $firstLine\n$commandText")
                        }
                    }
                    emptyList()
                }

                val previous = messages[projectBasePath]
                if (previous != null && reviewErrors.deepContentEquals(previous)) {
                    return@executeOnPooledThread
                }
                messages[projectBasePath] = reviewErrors
                log.debug("elm-review update for $projectBasePath: ${reviewErrors.size} messages")
                if (!project.isDisposed) {
                    project.messageBus.syncPublisher(ELM_REVIEW_WATCH_TOPIC).update(projectBasePath, reviewErrors)
                }
            } catch (_: AlreadyDisposedException) {
                // Expected during shutdown/test teardown if an async review completes late.
            } catch (t: Throwable) {
                if (!project.isDisposed) {
                    val compilerPathForReview = resolveElmReviewCompiler(
                        project = project,
                        projectBasePath = projectBasePath,
                        elmProjectHint = elmProjectHint,
                        suggestedTools = suggestedTools
                    ).path
                    val args = buildList {
                        add("--report=json")
                        add("--namespace=intellij-elm")
                        add("--config=./review")
                        compilerPathForReview?.let { add("--compiler=$it") }
                    }
                    val commandText = buildCommandText(elmReviewExecutablePath, projectBasePath, args)
                    showError("elm-review failed: ${t.message}\n$commandText")
                }
                if (project.isDisposed) {
                    log.debug("elm-review task finished after project disposal")
                } else {
                    log.warn("elm-review run failed", t)
                }
            } finally {
                runningReviews.remove(projectBasePath)
                completedGenerationByProject.merge(projectBasePath, runGeneration, ::maxOf)
                if (!project.isDisposed) {
                    project.elmTaskStatus.reviewFinished()
                }
                if (pendingReviews.remove(projectBasePath) && !project.isDisposed) {
                    runReview(projectBasePath, elmProjectHint = null)
                }
            }
        }
    }

    fun runReviewOnDocumentChange(
        projectBasePath: Path,
        sourceFilePath: Path,
        documentModificationStamp: Long,
        elmProjectHint: ElmProject?
    ) {
        if (!project.elmSettings.toolchain.isElmReviewOnTheFlyEnabled) return
        val previousStamp = lastRequestedStampByFile[sourceFilePath]
        if (previousStamp != null && previousStamp >= documentModificationStamp) return
        lastRequestedStampByFile[sourceFilePath] = documentModificationStamp
        markReviewRequested(projectBasePath)
        runReview(projectBasePath, elmProjectHint = elmProjectHint)
    }

    private fun markReviewRequested(projectBasePath: Path): Long {
        val generation = reviewGeneration.incrementAndGet()
        requestedGenerationByProject[projectBasePath] = generation
        return generation
    }

    private fun showError(message: String, includeFixAction: Boolean = false) {
        val actions = if (includeFixAction) {
            arrayOf("Fix" to { project.elmWorkspace.showConfigureToolchainUI() })
        } else {
            emptyArray()
        }
        project.showBalloon(message, NotificationType.ERROR, *actions)
    }
}

private fun buildCommandText(executable: Path, workingDir: Path, arguments: List<String>): String {
    val cmd = buildList {
        add(executable.toString())
        addAll(arguments)
    }.joinToString(" ")
    return "Command (cwd=$workingDir): $cmd"
}

private fun extractElmReviewJson(stdout: String, stderr: String): String? {
    fun from(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start !in 0..<end) return null
        return trimmed.substring(start, end + 1)
    }

    return from(stdout)
        ?: from(stderr)
        ?: from("$stdout\n$stderr")
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
