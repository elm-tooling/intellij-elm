package org.elm.workspace.commandLineTools

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.elm.ide.test.run.ElmTestRunConfiguration.FilteredTest
import org.elm.openapiext.GeneralCommandLine
import org.elm.openapiext.Result
import org.elm.openapiext.execute
import org.elm.workspace.ElmProject
import org.elm.workspace.ParseException
import org.elm.workspace.Version
import org.elm.workspace.elmTestRsTool
import org.elm.workspace.elmTestTool
import org.elm.workspace.elmToolchain
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
     * @param elmCompilerPath The path to the Elm compiler.
     * @param elmProject The [ElmProject] containing the tests to be run.
     * @param filteredTest An instance of the filter parameters for the test
     * @param isElmTestRsEnabled True if using elm-test-rs
     */
    fun runTestsProcessHandler(elmCompilerPath: Path, elmProject: ElmProject, filteredTest: FilteredTest?, isElmTestRsEnabled: Boolean): ProcessHandler {
        val commandLine = GeneralCommandLine(executablePath.toString(), "--report=json")
                .withWorkDirectory(elmProject.projectDirPath.toString())
                .withParameters("--compiler", elmCompilerPath.toString())
                .withRedirectErrorStream(true)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        if (filteredTest != null) {
            log.debug { """Test file or directory was provided: "${elmProject.testsRelativeDirPath}". Will specify this path as argument to elm-test.""" }

            if (isElmTestRsEnabled && !filteredTest.filter.isNullOrBlank()) {
                commandLine.withParameters("--filter", filteredTest.filter)
            }

            commandLine.withParameters(filteredTest.runnableFilePath())
        // By default elm-test will process tests in a folder called "tests", under the current working directory
        // (in this case elmProject.projectDirPath). If the project has a custom location for tests we need to supply a
        // path to that folder.
        } else if (elmProject.isCustomTestsDir) {
            log.debug { """Tests are in custom location: "${elmProject.testsRelativeDirPath}". Will specify this path as argument to elm-test.""" }
            commandLine.withParameters(elmProject.testsRelativeDirPath)
        } else {
            log.debug("""Tests are in default location ("tests") so will run elm-test without argument specifying path.""")
        }

        return ColoredProcessHandler(commandLine)
    }


    fun queryVersion(project: Project): Result<Version> {
        val tool = if (project.elmToolchain.isElmTestRsEnabled) elmTestRsTool else elmTestTool

        // Output of `elm-test --version` is a single line containing the version number,
        // e.g. `0.19.0-beta9\n`, trimming off the "-betaN" suffix, if present.
        val firstLine = try {
            GeneralCommandLine(executablePath).withParameters("--version")
                    .execute(tool, project)
                    .stdoutLines
                    .firstOrNull()
        } catch (e: ExecutionException) {
            return Result.Err("failed to run elm-test: ${e.message}")
        }

        if (firstLine == null) {
            return Result.Err("no output from elm-test")
        }

        val trimmedFirstLine = if (project.elmToolchain.isElmTestRsEnabled) {
            if (!firstLine.contains("elm-test-rs")) return Result.Err("Could not parse elm-test-rs version: $firstLine")

            firstLine.removePrefix("elm-test-rs").trim()
        } else {
            firstLine.takeWhile { it != '-' }
        }

        return try {
            Result.Ok(Version.parse(trimmedFirstLine))
        } catch (e: ParseException) {
            Result.Err("could not parse elm-test version: ${e.message}")
        }
    }
}