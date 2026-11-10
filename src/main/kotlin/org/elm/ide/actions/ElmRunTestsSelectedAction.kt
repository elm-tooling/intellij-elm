package org.elm.ide.actions

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import org.elm.ide.notifications.showBalloon
import org.elm.ide.test.run.ElmTestRunConfiguration
import org.elm.ide.test.run.ElmTestRunConfigurationType
import org.elm.workspace.compiler.ElmBuildTargetType
import org.elm.workspace.compiler.ResolvedBuildTarget

/**
 * Run the tests for the test build target currently selected in the Elm Compiler tool window.
 *
 * If the selected target is not a test target (or nothing is selected yet), this falls back to the
 * first automatic test target, mirroring how "Build Selected Elm Target" falls back to the first
 * target. The chosen target becomes the persisted selection so the panel reflects what ran.
 *
 * Available from the command palette ("Find Action"). It has no default keyboard shortcut, but the
 * user can assign one via Settings | Keymap.
 */
class ElmRunTestsSelectedAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Open the tool window (unless already open) so the selection is visible.
        openElmCompilerToolWindow(project)

        val targets = resolveAllBuildTargets(project)
        val testTargets = targets.filter { it.type == ElmBuildTargetType.TEST }
        if (testTargets.isEmpty()) {
            project.showBalloon("There are no Elm test targets to run.", NotificationType.INFORMATION)
            return
        }

        val selectedKey = project.elmBuildTargetSelection.selectedKey
        val selected = targets.firstOrNull { buildTargetKeyOf(it) == selectedKey }
        // Run the selection when it is a test target; otherwise fall back to the first test target.
        val target = selected?.takeIf { it.type == ElmBuildTargetType.TEST } ?: testTargets.first()

        // Keep the selection in sync so the panel reflects what we ran.
        project.elmBuildTargetSelection.selectedKey = buildTargetKeyOf(target)
        runElmTestsForTarget(project, target)
    }
}

/**
 * Run the tests for a resolved test build target by reusing the existing Elm test run configuration
 * — the same machinery behind the gutter "Run tests" action — so results appear in IntelliJ's test
 * runner UI. An existing run configuration for the target's project directory is reused when present
 * (avoiding duplicate configs/tabs); otherwise a temporary one is created.
 *
 * Must be called on the EDT.
 */
fun runElmTestsForTarget(project: Project, target: ResolvedBuildTarget) {
    val elmFolder = target.workDir.toString()
    val runManager = RunManager.getInstance(project)

    val settings: RunnerAndConfigurationSettings =
        runManager.allSettings.firstOrNull {
            (it.configuration as? ElmTestRunConfiguration)?.options?.elmFolder == elmFolder
        } ?: run {
            val type = ConfigurationTypeUtil.findConfigurationType(ElmTestRunConfigurationType::class.java)
            val created = runManager.createConfiguration(target.name.ifBlank { "Elm Tests" }, type.configurationFactories.first())
            (created.configuration as ElmTestRunConfiguration).options.elmFolder = elmFolder
            runManager.setTemporaryConfiguration(created)
            created
        }

    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}
