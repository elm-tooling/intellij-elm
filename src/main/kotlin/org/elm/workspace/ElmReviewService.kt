package org.elm.workspace

import com.google.gson.Strictness
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.Topic
import org.elm.ide.notifications.showBalloon
import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.readErrorReport
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

private val log = logger<ElmReviewService>()

@Service(Service.Level.PROJECT)
class ElmReviewService(private val project: Project) {

    private val watchers: MutableMap<Path, Process> = ConcurrentHashMap()
    private val messages: MutableMap<Path, List<ElmReviewError>> = ConcurrentHashMap()

    interface ElmReviewWatchListener {
        fun update(baseDirPath: Path, messages: List<ElmReviewError>)
    }

    companion object {
        val ELM_REVIEW_WATCH_TOPIC = Topic("elm-review watch errors", ElmReviewWatchListener::class.java)
    }

    fun messagesForCurrentProject(path: Path): List<ElmReviewError> =
        messages[path] ?: emptyList()

    fun start(projectBasePath: Path) {
        if (!project.elmSettings.toolchain.isElmReviewOnTheFlyEnabled) return
        if (!projectBasePath.resolve("elm.json").exists()) return
        if (!projectBasePath.resolve("review").exists()) return

        val current = watchers[projectBasePath]
        if (current != null && current.isAlive) return

        val elmReviewExecutablePath = project.elmToolchain.elmReviewPath ?: run {
            showError("Could not find elm-review executable", includeFixAction = true)
            return
        }
        val elmCompiler = project.elmToolchain.elmCLI
        val arguments = listOf("--watch", "--report=json", "--namespace=intellij-elm") +
            "--config=./review" +
            if (elmCompiler == null) "" else "--compiler=${elmCompiler.elmExecutablePath}"
        val command = listOf(elmReviewExecutablePath.absolutePathString(), *arguments.toTypedArray())

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val processBuilder = ProcessBuilder(command).directory(projectBasePath.toFile())
                augmentPathForCliTools(processBuilder)
                val process = processBuilder.start()
                watchers[projectBasePath] = process
                Disposer.register(project) { process.destroyForcibly() }
                log.info("elm-review watch started for $projectBasePath")

                process.inputStream.bufferedReader().forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEachLine
                    // elm-review --watch can emit NDJSON records and occasional non-JSON text.
                    if (!trimmed.startsWith("{")) return@forEachLine

                    try {
                        val reader = com.google.gson.stream.JsonReader(trimmed.reader())
                        reader.setStrictness(Strictness.LENIENT)
                        val reviewErrors = reader.readErrorReport()
                        messages[projectBasePath] = reviewErrors
                        log.info("elm-review update for $projectBasePath: ${reviewErrors.size} messages")
                        project.messageBus.syncPublisher(ELM_REVIEW_WATCH_TOPIC).update(projectBasePath, reviewErrors)
                    } catch (t: Throwable) {
                        log.debug("Skipping unparsable elm-review watch line: $trimmed", t)
                    }
                }

                val exitCode = when {
                    !process.isAlive -> process.exitValue()
                    process.waitFor(50, TimeUnit.MILLISECONDS) -> process.exitValue()
                    else -> null
                }
                if (process.isAlive) {
                    // If stdout closes before process exit, prevent a stuck watcher instance.
                    process.destroyForcibly()
                }
                watchers.remove(projectBasePath, process)
                if (exitCode != null && exitCode != 0 && !project.isDisposed) {
                    log.warn("elm-review watch exited with code $exitCode for $projectBasePath")
                }
            } catch (t: Throwable) {
                watchers.remove(projectBasePath)
                if (!project.isDisposed) {
                    showError("elm-review watch failed: ${t.message}")
                }
                log.warn("elm-review watch startup/parsing failed", t)
            }
        }
    }

    fun stopAll() {
        watchers.values.forEach { it.destroyForcibly() }
        watchers.clear()
    }

    private fun showError(message: String, includeFixAction: Boolean = false) {
        val actions = if (includeFixAction) {
            arrayOf("Fix" to { project.elmWorkspace.showConfigureToolchainUI() })
        } else {
            emptyArray()
        }
        project.showBalloon(message, NotificationType.ERROR, *actions)
    }

    private fun augmentPathForCliTools(processBuilder: ProcessBuilder) {
        val env = processBuilder.environment()
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
