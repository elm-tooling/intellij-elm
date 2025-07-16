package org.elm.ide.test.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import org.elm.ide.test.core.ElmTestElementNavigator
import org.elm.workspace.elmToolchain
import org.elm.workspace.elmWorkspace

class ElmTestRunConfigurationProducer : LazyRunConfigurationProducer<ElmTestRunConfiguration>() {

    override fun getConfigurationFactory() =
        ElmTestRunConfigurationType.instance.configurationFactories.single()

    override fun setupConfigurationFromContext(configuration: ElmTestRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val elmFolder = getCandidateElmFolder(context) ?: return false
        val vfile = context.location?.virtualFile

        configuration.options.elmFolder = elmFolder
        if (vfile != null) {
            configuration.options.filteredTestConfig = ElmTestRunConfiguration.FilteredTest.from(sourceElement.get(), getFilter(context))
        }

        configuration.setGeneratedName()

        return true
    }

    override fun isConfigurationFromContext(configuration: ElmTestRunConfiguration, context: ConfigurationContext): Boolean {
        val elmFolder = getCandidateElmFolder(context) ?: return false
        val vfile = context.location?.virtualFile

        var result = configuration.options.elmFolder == elmFolder
        val config = configuration.options.filteredTestConfig

        if (config == null) return result

        if (config.moduleName.isNotBlank()) {
            result = result && config.filePath == vfile?.path
        }

        if (!config.filter.isNullOrBlank()) {
            result = result && config.filter == getFilter(context)
        }

        return result
    }

    private fun getCandidateElmFolder(context: ConfigurationContext): String? {
        val vfile = context.location?.virtualFile ?: return null
        val elmProject = context.project.elmWorkspace.findProjectForFile(vfile) ?: return null
        return elmProject.projectDirPath.toString()
    }

    /** Get the filter string or null if it couldn't be found */
    private fun getFilter(context: ConfigurationContext): String? {
        return if (context.project.elmToolchain.isElmTestRsEnabled) ElmTestElementNavigator.findTestDescription(context.psiLocation) else null
    }
}
