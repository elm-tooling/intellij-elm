package org.elm.ide.test.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.psi.PsiElement
import org.elm.workspace.elmWorkspace
import kotlin.io.path.pathString

/** * Helper object to create a run configuration and add it to the run manager. Returns the config settings. */
object ElmTestRunConfigurationSettingsBuilder {
    fun createAndRegisterFromElement(element: PsiElement, filter: String? = null): RunnerAndConfigurationSettings {
        val project = element.project
        val runManager = RunManager.getInstance(project)
        val configurationFactory = ElmTestRunConfigurationType.instance.configurationFactories.single()

        val configuration = configurationFactory.createTemplateConfiguration(project)
        configuration.options.elmFolder =
            project.elmWorkspace.findProjectForFile(element.containingFile.virtualFile)?.projectDirPath?.pathString
        configuration.options.filteredTestConfig = ElmTestRunConfiguration.FilteredTest.from(element, filter)
        configuration.setGeneratedName()

        val configSettings = runManager.createConfiguration(configuration, configurationFactory)
        configSettings.isTemporary = true
        runManager.addConfiguration(configSettings)
        runManager.selectedConfiguration = configSettings

        return configSettings
    }
}
