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
import org.elm.ide.statusbar.elmTaskStatus
import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.readErrorReport
import org.elm.workspace.compiler.toPathOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

private val log = logger<ElmReviewService>()

@Service(Service.Level.PROJECT)
class ElmReviewService(private val project: Project) {

    private val runningReviews: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val missingExecutableNotified: MutableSet<Path> = ConcurrentHashMap.newKeySet()
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
        runReview(projectBasePath, elmProjectHint = null, compilerPathHint = null)
    }

    fun runReview(projectBasePath: Path, elmProjectHint: ElmProject?, compilerPathHint: Path? = null) {
        if (!project.elmSettings.toolchain.isElmReviewOnTheFlyEnabled) return
        if (!projectBasePath.resolve("elm.json").exists()) return
        if (!projectBasePath.resolve("review").exists()) return

        if (!runningReviews.add(projectBasePath)) return

        val elmReviewExecutablePath = project.elmToolchain.elmReviewPath ?: run {
            runningReviews.remove(projectBasePath)
            if (missingExecutableNotified.add(projectBasePath)) {
                showError("Could not find elm-review executable", includeFixAction = true)
            }
            return
        }
        missingExecutableNotified.remove(projectBasePath)
        ApplicationManager.getApplication().executeOnPooledThread {
            project.elmTaskStatus.reviewStarted()
            val suggestedTools = ElmSuggest.suggestTools(project)
            try {
                val compilerPathForReview = resolveCompilerPathForReview(
                    projectBasePath = projectBasePath,
                    elmProjectHint = elmProjectHint,
                    compilerPathHint = compilerPathHint,
                    suggestedTools = suggestedTools
                )
                val arguments = buildList {
                    add("--report=json")
                    add("--namespace=intellij-elm")
                    add("--config=./review")
                    compilerPathForReview?.let { add("--compiler=$it") }
                }
                val commandText = buildCommandText(elmReviewExecutablePath, projectBasePath, arguments)
                val command = GeneralCommandLine(elmReviewExecutablePath)
                    .withWorkDirectory(projectBasePath.toString())
                    .withParameters(arguments)
                augmentPathForCliTools(command.environment, compilerPathForReview, suggestedTools)

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
                project.messageBus.syncPublisher(ELM_REVIEW_WATCH_TOPIC).update(projectBasePath, reviewErrors)
            } catch (t: Throwable) {
                if (!project.isDisposed) {
                    val compilerPathForReview = resolveCompilerPathForReview(
                        projectBasePath = projectBasePath,
                        elmProjectHint = elmProjectHint,
                        compilerPathHint = compilerPathHint,
                        suggestedTools = suggestedTools
                    )
                    val args = buildList {
                        add("--report=json")
                        add("--namespace=intellij-elm")
                        add("--config=./review")
                        compilerPathForReview?.let { add("--compiler=$it") }
                    }
                    val commandText = buildCommandText(elmReviewExecutablePath, projectBasePath, args)
                    showError("elm-review failed: ${t.message}\n$commandText")
                }
                log.warn("elm-review run failed", t)
            } finally {
                runningReviews.remove(projectBasePath)
                project.elmTaskStatus.reviewFinished()
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

    private fun augmentPathForCliTools(
        env: MutableMap<String, String>,
        compilerPath: Path?,
        suggestedTools: Map<String, Path?>
    ) {
        val existing = env["PATH"].orEmpty()
        val separator = java.io.File.pathSeparator
        val extraDirs = linkedSetOf<String>()
        extraDirs += "/opt/homebrew/bin"
        project.elmToolchain.elmReviewPath?.parent?.toString()?.let(extraDirs::add)
        compilerPath?.parent?.toString()?.let(extraDirs::add)
        project.elmToolchain.compilerPath?.parent?.toString()?.let(extraDirs::add)
        suggestedTools[elmCompilerTool]?.parent?.toString()?.let(extraDirs::add)
        suggestedTools[lamderaCompilerTool]?.parent?.toString()?.let(extraDirs::add)
        suggestedTools[elmWrapCompilerTool]?.parent?.toString()?.let(extraDirs::add)

        val prefix = extraDirs.filter { it.isNotBlank() }.joinToString(separator)
        env["PATH"] = if (existing.isBlank()) prefix else "$prefix$separator$existing"

    }

    private fun resolveCompilerPathForReview(
        projectBasePath: Path,
        elmProjectHint: ElmProject?,
        compilerPathHint: Path?,
        suggestedTools: Map<String, Path?>
    ): Path? {
        if (compilerPathHint != null && Files.isExecutable(compilerPathHint)) return compilerPathHint

        val fromToolchain = project.elmToolchain.compilerPath
        if (fromToolchain != null && Files.isExecutable(fromToolchain)) return fromToolchain

        val elmProject = elmProjectHint
            ?: project.elmWorkspace.allProjects.firstOrNull { it.projectDirPath.normalize() == projectBasePath.normalize() }
        if (elmProject != null) {
            val fromTargets = project.elmWorkspace.buildTargetConfigsFor(elmProject)
                .asSequence()
                .mapNotNull { target ->
                    val raw = target.compilerPath.trim()
                    if (raw.isBlank()) return@mapNotNull null
                    val path = raw.toPathOrNull() ?: return@mapNotNull null
                    when {
                        path.isAbsolute && Files.isExecutable(path) -> path
                        !path.isAbsolute -> projectBasePath.resolve(path).normalize().takeIf { Files.isExecutable(it) }
                        else -> null
                    }
                }
                .firstOrNull()
            if (fromTargets != null) return fromTargets
        }

        return sequenceOf(elmCompilerTool, lamderaCompilerTool, elmWrapCompilerTool)
            .mapNotNull { suggestedTools[it] }
            .firstOrNull { Files.isExecutable(it) }
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
