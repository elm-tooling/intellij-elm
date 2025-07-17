package org.elm.ide.test.run

import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import org.elm.ide.test.run.ElmTestConfigurationFactory.Companion.RUN_ICON

class ElmTestRunConfigurationType : ConfigurationType {

    override fun getDisplayName() = "Elm Test"

    override fun getConfigurationTypeDescription() = "Elm test runner"

    override fun getIcon() = RUN_ICON

    override fun getId() = "ELM_TEST_RUN_CONFIGURATION"

    override fun getConfigurationFactories() = arrayOf(ElmTestConfigurationFactory(this))

    companion object {
        val instance: ElmTestRunConfigurationType
            get() = ConfigurationTypeUtil.findConfigurationType(ElmTestRunConfigurationType::class.java)
    }
}
