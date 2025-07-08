package org.elm.ide.lineMarkers

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.SingleConfigurationConfigurable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.psi.PsiElement
import org.elm.ide.test.run.ElmTestRunConfiguration
import org.elm.ide.test.run.ElmTestRunConfigurationSettingsBuilder

/**
 * Action to run all tests for a given file or directory
 */
class RunAllTestsAction(private val element: PsiElement) : AnAction("Run All Tests") {
    override fun actionPerformed(event: AnActionEvent) {
        ProgramRunnerUtil.executeConfiguration(
            ElmTestRunConfigurationSettingsBuilder.createAndRegisterFromElement(element),
            DefaultRunExecutor.getRunExecutorInstance()
        )
    }
}

/**
 * Creates a run configuration and opens editor
 */
class ModifyRunConfiguration(private val element: PsiElement) : AnAction("Modify Run Configuration") {
    override fun actionPerformed(event: AnActionEvent) {
        ShowSettingsUtil.getInstance().editConfigurable(
            element.project,
            SingleConfigurationConfigurable.editSettings<ElmTestRunConfiguration>(
                ElmTestRunConfigurationSettingsBuilder.createAndRegisterFromElement(element),
                null
            )
        )
    }
}