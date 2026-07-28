package org.elm.workspace.commandLineTools

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.ide.statusbar.elmTaskStatus
import org.elm.openapiext.GeneralCommandLine
import org.elm.openapiext.Result
import org.elm.openapiext.execute
import org.elm.openapiext.isSuccess
import org.elm.workspace.ElmSuggest
import org.elm.workspace.ElmProject
import org.elm.workspace.ParseException
import org.elm.workspace.Version
import org.elm.workspace.elmTestTool
import org.elm.workspace.compiler.COMPILER_OUTPUT_TOPIC
import org.elm.workspace.compiler.ERRORS_TOPIC
import org.elm.workspace.compiler.ElmCompilerOutput
import org.elm.workspace.compiler.ElmError
import org.elm.workspace.compiler.ResolvedBuildTarget
import org.elm.workspace.compiler.elmJsonToCompilerMessages
import java.nio.file.Path

private val log = logger<ElmTestCLI>()

/**
 * Interact with external `elm-test` process.
 */
class ElmTestCLI(private val executablePath: Path) {

    /**
     * Construct a [ProcessHandler] that will run `elm-test` (the caller is responsible for
     * actually invoking the process). The test results will be reported using elm-test's
     * JSON format on stdout.
     *
     * @param elmCompilerPath The path to the Elm compiler. If null, elm-test resolves
     * the compiler from the environment/PATH.
     * @param elmProject The [ElmProject] containing the tests to be run.
     */
    fun runTestsProcessHandler(project: Project, elmCompilerPath: Path?, elmProject: ElmProject): ProcessHandler {
        val suggestedTools = ElmSuggest.suggestTools(project)
        val commandLine = GeneralCommandLine(executablePath.toString(), "--report=json")
                .withWorkDirectory(elmProject.projectDirPath.toString())
                .withRedirectErrorStream(true)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .apply {
                    augmentPathForNodeBackedTool(
                        env = environment,
                        executablePath = executablePath,
                        compilerPath = elmCompilerPath,
                        suggestedTools = suggestedTools
                    )
                }
        if (elmCompilerPath != null) {
            commandLine.withParameters("--compiler", elmCompilerPath.toString())
        }

        // By default elm-test will process tests in a folder called "tests", under the current working directory
        // (in this case elmProject.projectDirPath). If the project has a custom location for tests we need to supply a
        // path to that folder.
        if (elmProject.isCustomTestsDir) {
            log.debug { """Tests are in custom location: "${elmProject.testsRelativeDirPath}". Will specify this path as argument to elm-test.""" }
            commandLine.withParameters(elmProject.testsRelativeDirPath)
        } else {
            log.debug("""Tests are in default location ("tests") so will run elm-test without argument specifying path.""")
        }

        return ColoredProcessHandler(commandLine)
    }

    /**
     * Type-check a project's tests by running `elm-test make --report=json` (the same command line
     * used to run tests, but with the `make` subcommand). Compilation errors are reported to the
     * Elm Compiler tool window just like a regular `elm make` build; `elm-test` forwards the
     * compiler's `--report=json` error output on stderr in the same shape the compiler produces.
     *
     * Mirrors the shape of [ElmCLI.make]: each entry runs in its own working directory, output is
     * published to [COMPILER_OUTPUT_TOPIC], and errors are either collected into [messageSink] (for
     * an aggregated "Build all") or posted once to [ERRORS_TOPIC].
     */
    fun make(
        project: Project,
        baseDirForErrors: Path?,
        entryPoints: List<ResolvedBuildTarget>,
        currentFile: VirtualFile? = null,
        messageSink: MutableList<ElmError>? = null,
        // Collects console output for an aggregated build (e.g. "Build all") instead of posting it
        // here; the caller posts every command's output at once. Null for a normal single build.
        outputSink: MutableList<ElmCompilerOutput>? = null
    ): Boolean {
        if (entryPoints.isEmpty()) return true

        val suggestedTools = ElmSuggest.suggestTools(project)
        project.elmTaskStatus.compilerStarted()
        try {
            val allMessages = mutableListOf<ElmError>()
            val outputs = mutableListOf<ElmCompilerOutput>()
            var allSucceeded = true
            // Elm 0.19.2 has a bug where the error locations reported by `--report=json` are off by
            // one: https://github.com/elm/compiler/issues/2358. elm-test drives the Elm compiler, so
            // the same correction applies when the underlying compiler is 0.19.2. Query the compiler
            // version only when there is actually an error to adjust, memoized per compiler path.
            val offsetByCompiler = mutableMapOf<Path, Int>()
            fun rowAndColumnOffsetFor(compilerPath: Path): Int =
                offsetByCompiler.getOrPut(compilerPath) {
                    if (ElmCLI(compilerPath).queryVersion(project).orNull()?.xyz == Version(0, 19, 2)) 1 else 0
                }
            for (entry in entryPoints) {
                val elmCompilerPath = entry.compilerPath
                val commandLine = GeneralCommandLine(executablePath.toString(), "make", "--report=json")
                    .withWorkDirectory(entry.workDir.toString())
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                    .apply {
                        augmentPathForNodeBackedTool(
                            env = environment,
                            executablePath = executablePath,
                            compilerPath = elmCompilerPath,
                            suggestedTools = suggestedTools
                        )
                    }
                    .withParameters("--compiler", elmCompilerPath.toString())
                // elm-test defaults to the "tests" directory; only pass a path for a custom one.
                entry.testsCustomDir?.let { commandLine.withParameters(it) }

                // Generous timeout: elm-test is a Node-backed tool, so it pays Node startup on top
                // of the compilation itself, and can easily exceed the short default.
                val output = commandLine.execute(elmTestTool, project, timeoutInMilliseconds = MAKE_TIMEOUT_MS)
                outputs += ElmCompilerOutput(
                    elmTestTool,
                    commandLine.commandLineString,
                    output.stdout,
                    output.stderr,
                    output.exitCode
                )
                if (!output.isSuccess) {
                    allSucceeded = false
                }
                val cleansedJson = "\\{.*}".toRegex().find(output.stderr)?.value
                if (!cleansedJson.isNullOrEmpty()) {
                    allMessages += elmJsonToCompilerMessages(cleansedJson, rowAndColumnOffsetFor(elmCompilerPath))
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
                // Collect messages for an aggregated build (e.g. "Build all") instead of posting
                // them here; the caller deduplicates and posts once.
                messageSink += messages.map { it.withAbsolutePath(entryPoints.first().workDir) }
                return messages.isEmpty() && allSucceeded
            }

            if (baseDirForErrors != null) {
                project.messageBus.syncPublisher(ERRORS_TOPIC)
                    .update(baseDirForErrors, messages, "", 0)
            }
            return messages.isEmpty() && allSucceeded
        } finally {
            project.elmTaskStatus.compilerFinished()
        }
    }


    fun queryVersion(project: Project): Result<Version> {
        // Output of `elm-test --version` can be a plain version or include a binary prefix
        // (for example: `elm-test-rs 3.0.1`).
        val firstLine = try {
            GeneralCommandLine(executablePath)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                    .apply {
                        augmentPathForNodeBackedTool(
                            env = environment,
                            executablePath = executablePath,
                            compilerPath = null,
                            suggestedTools = ElmSuggest.suggestTools(project)
                        )
                    }
                    .withParameters("--version")
                    .execute(elmTestTool, project)
                    .stdoutLines
                    .firstOrNull()
        } catch (e: ExecutionException) {
            return Result.Err("failed to run elm-test: ${e.message}")
        }

        if (firstLine == null) {
            return Result.Err("no output from elm-test")
        }

        return parseVersionLine(firstLine)
    }

    companion object {
        /** Timeout for `elm-test make`; large enough to cover Node startup plus compilation. */
        private const val MAKE_TIMEOUT_MS = 120_000

        private val VERSION_TOKEN_REGEX = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?""")

        internal fun parseVersionLine(line: String): Result<Version> {
            val versionText = VERSION_TOKEN_REGEX.find(line)?.value ?: line.trim()
            return try {
                Result.Ok(Version.parse(versionText))
            } catch (e: ParseException) {
                Result.Err("could not parse elm-test version: ${e.message}")
            }
        }
    }
}
