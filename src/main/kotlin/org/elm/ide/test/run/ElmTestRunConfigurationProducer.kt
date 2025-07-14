package org.elm.ide.test.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import org.elm.ide.test.core.ElmTestElementNavigator
import org.elm.workspace.elmWorkspace

class ElmTestRunConfigurationProducer : LazyRunConfigurationProducer<ElmTestRunConfiguration>() {

    override fun getConfigurationFactory() =
        ElmTestRunConfigurationType.instance.configurationFactories.single()

    override fun setupConfigurationFromContext(configuration: ElmTestRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val elmFolder = getCandidateElmFolder(context) ?: return false
        val vfile = context.location?.virtualFile

        configuration.options.elmFolder = elmFolder
        if (vfile != null) {
            val filter = ElmTestElementNavigator.findTestDescription(context.psiLocation)
            configuration.options.filteredTestConfig = ElmTestRunConfiguration.FilteredTest.from(sourceElement.get(), filter)
        }

        configuration.setGeneratedName()

        return true
    }

    override fun isConfigurationFromContext(configuration: ElmTestRunConfiguration, context: ConfigurationContext): Boolean {
        val elmFolder = getCandidateElmFolder(context) ?: return false
        val vfile = context.location?.virtualFile
        val filter = ElmTestElementNavigator.findTestDescription(context.psiLocation)

        return configuration.options.elmFolder == elmFolder
                && configuration.options.filteredTestConfig?.filePath == vfile?.path
                && configuration.options.filteredTestConfig?.filter == filter
    }

    private fun getCandidateElmFolder(context: ConfigurationContext): String? {
        val vfile = context.location?.virtualFile ?: return null
        val elmProject = context.project.elmWorkspace.findProjectForFile(vfile) ?: return null
        return elmProject.projectDirPath.toString()
    }
}
