package org.elm.ide.test.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.autotest.ToggleAutoTestAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.events.*
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor
import org.elm.ide.actions.ELM_COMPILER_TOOL_WINDOW_ID
import org.elm.ide.actions.buildTarget
import org.elm.ide.actions.resolveAllBuildTargets
import org.elm.ide.notifications.showBalloon
import org.elm.ide.test.core.ElmProjectTestsHelper
import org.elm.ide.test.core.ElmTestJsonProcessor
import org.elm.workspace.ElmProject
import org.elm.workspace.compiler.ElmBuildTargetType
import org.elm.workspace.elmTestTool
import org.elm.workspace.elmWorkspace
import java.nio.file.Files

class ElmTestRunProfileState internal constructor(
        environment: ExecutionEnvironment,
        configuration: ElmTestRunConfiguration
) : CommandLineState(environment) {

    private val elmFolder =
            configuration.options.elmFolder?.takeIf { it.isNotEmpty() }
                    ?: environment.project.basePath

    private val elmProject =
            elmFolder?.let {
                ElmProjectTestsHelper(environment.project).elmProjectByProjectDirPath(elmFolder)
            }

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        FileDocumentManager.getInstance().saveAllDocuments()
        val project = environment.project
        val toolchain = project.elmWorkspace.settings.toolchain

        val elmTestCLI = toolchain.elmTestCLI
                ?: return handleBadConfiguration(project, "Missing path to elm-test")

        if (elmFolder == null) return handleBadConfiguration(project, "Missing path to elmFolder")
        if (elmProject == null) return handleBadConfiguration(project, "Could not find the Elm project for these tests")

        val elmCompilerBinary = toolchain.elmCompilerPath?.takeIf { Files.exists(it) }
        val handler = elmTestCLI.runTestsProcessHandler(project, elmCompilerBinary, elmProject)
        forwardCompilationErrorsToCompilerPanel(handler, project, elmProject)
        return handler
    }

    /**
     * When a test run fails because the tests don't *compile* (as opposed to assertions failing),
     * the raw compiler output is much nicer to read in the Elm Compiler tool window. So watch the
     * test process, and if its output indicates a compilation failure, switch to that tool window
     * and recompile the matching test target there (via `elm-test make`) to render the errors.
     *
     * Detection matches the compile-error signature of both supported runners (the streams are
     * merged, so a single accumulated buffer is checked):
     * - elm-test:    stderr contains "`elm make` failed with exit code 1"
     * - elm-test-rs: stdout contains "\"type\":\"compile-errors\""
     *
     * A plain test failure (exit code 2) or `skip`/`only` usage (exit code 3) is left in the test
     * runner untouched.
     */
    private fun forwardCompilationErrorsToCompilerPanel(
        handler: ProcessHandler,
        project: Project,
        elmProject: ElmProject
    ) {
        val output = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                val text = output.toString()
                val hasCompilationErrors =
                    text.contains("`elm make` failed with exit code 1") ||
                    text.contains(""""type":"compile-errors"""")
                if (!hasCompilationErrors) return
                ApplicationManager.getApplication().invokeLater {
                    showTestCompilationErrorsInCompilerPanel(project, elmProject)
                }
            }
        })
    }

    private fun showTestCompilationErrorsInCompilerPanel(project: Project, elmProject: ElmProject) {
        val target = resolveAllBuildTargets(project).firstOrNull {
            it.type == ElmBuildTargetType.TEST && it.workDir == elmProject.projectDirPath
        } ?: return
        // Bring the Elm Compiler tool window to the front, then recompile the test target so its
        // errors are posted there.
        ToolWindowManager.getInstance(project).getToolWindow(ELM_COMPILER_TOOL_WINDOW_ID)?.activate(null)
        buildTarget(project, target)
    }

    @Throws(ExecutionException::class)
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val result = super.execute(executor, runner)
        if (result is DefaultExecutionResult) {
            result.setRestartActions(object : ToggleAutoTestAction() {
                override fun getAutoTestManager(project: Project) = project.elmAutoTestManager
            })
        }
        return result
    }

    @Throws(ExecutionException::class)
    private fun handleBadConfiguration(project: Project, errorMessage: String): ProcessHandler {
        project.showBalloon(
                errorMessage,
                NotificationType.ERROR,
                "Fix" to { project.elmWorkspace.showConfigureToolchainUI() }
        )
        throw ExecutionException(errorMessage)
    }

    override fun createConsole(executor: Executor): ConsoleView {
        if (elmProject == null) error("Missing ElmProject")

        val runConfiguration = environment.runProfile as RunConfiguration
        val properties = ConsoleProperties(runConfiguration, executor, elmProject.testsRelativeDirPath)
        val consoleView = SMTRunnerConsoleView(properties)
        SMTestRunnerConnectionUtil.initConsoleView(consoleView, properties.testFrameworkName)
        return consoleView
    }

    private class ConsoleProperties(
            config: RunConfiguration,
            executor: Executor,
            private val testsRelativeDirPath: String
    ) : SMTRunnerConsoleProperties(config, elmTestTool, executor), SMCustomMessagesParsing {

        init {

            setIfUndefined(TRACK_RUNNING_TEST, true)
            setIfUndefined(OPEN_FAILURE_LINE, true)
            setIfUndefined(HIDE_PASSED_TESTS, false)
            setIfUndefined(SHOW_STATISTICS, true)
            setIfUndefined(SELECT_FIRST_DEFECT, true)
            setIfUndefined(SCROLL_TO_SOURCE, true)
            //            INCLUDE_NON_STARTED_IN_RERUN_FAILED
            //            setIdBasedTestTree(true);
            //            setPrintTestingStartedTime(false);
        }


        override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter {
            return object : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {
                var processor = ElmTestJsonProcessor(testsRelativeDirPath)
                private var reporterAttached = false

                @Synchronized
                override fun finishTesting() {
                    super.finishTesting()
                }

                override fun processServiceMessages(
                    text: String,
                    outputType: Key<*>,
                    visitor: ServiceMessageVisitor
                ): Boolean {
                    val events = processor.accept(text) ?: return false
                    // Signal that a real test reporter is present, as soon as elm-test produces
                    // recognized output. Without this, a run that reports no tests at all — e.g.
                    // when every test is skipped, where elm-test emits only runStart/runComplete
                    // (with `autoFail: "Test.skip was used"`) and no testCompleted — leaves the root
                    // node childless with no reporter attached, which the SMTestRunner renders as
                    // "Test framework quit unexpectedly" rather than "No tests were found".
                    if (!reporterAttached) {
                        reporterAttached = true
                        getProcessor().onTestsReporterAttached()
                    }
                    events.forEach { processEvent(it) }
                    return true
                }

                private fun processEvent(event: TreeNodeEvent) {
                    when (event) {
                        is TestStartedEvent -> this.getProcessor().onTestStarted(event)
                        is TestFinishedEvent -> this.getProcessor().onTestFinished(event)
                        is TestFailedEvent -> this.getProcessor().onTestFailure(event)
                        is TestIgnoredEvent -> this.getProcessor().onTestIgnored(event)
                        is TestSuiteStartedEvent -> this.getProcessor().onSuiteStarted(event)
                        is TestSuiteFinishedEvent -> this.getProcessor().onSuiteFinished(event)
                    }
                }
            }
        }

        override fun getTestLocator() = ElmTestLocator
    }
}
