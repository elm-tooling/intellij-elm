package org.elm.ide.test.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.InvalidDataException
import com.intellij.openapi.util.WriteExternalException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.elm.ide.test.run.ElmTestConfigurationFactory.Companion.RUN_ICON
import org.elm.lang.core.psi.ElmFile
import org.jdom.Element
import java.nio.file.Paths


class ElmTestRunConfiguration internal constructor(project: Project, factory: ConfigurationFactory, name: String) : LocatableConfigurationBase<ElmTestRunProfileState>(project, factory, name) {

    var options = Options()

    override fun clone(): RunConfiguration {
        val copy = super.clone() as ElmTestRunConfiguration
        copy.options = options.copy()
        return copy
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
            ElmTestSettingsEditor(project)

    override fun checkConfiguration() {}

    override fun getState(executor: Executor, executionEnvironment: ExecutionEnvironment) =
            ElmTestRunProfileState(executionEnvironment, this)

    data class Options(var elmFolder: String? = null, var filteredTestConfig: FilteredTest? = null)

    data class FilteredTest(val filePath: String, val moduleName: String, val testIsDirectory: Boolean, val filter: String?) {
        companion object {
            fun from(path: String?, project: Project, filter: String? = null): FilteredTest? {
                if (path.isNullOrBlank())  return null

                val virtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$path") ?: return null

                return from(virtualFile, project, filter)
            }

            fun from(path: String?, moduleName: String?, filter: String? = null): FilteredTest? {
                if (path.isNullOrBlank() || moduleName.isNullOrBlank()) return null
                val isDirectory = !path.substringAfterLast('/').contains('.')

                return FilteredTest(path, moduleName, isDirectory, filter)
            }

            fun from(virtualFile: VirtualFile, project: Project, filter: String? = null): FilteredTest? {
                val psiManager = PsiManager.getInstance(project)

                if (virtualFile.isDirectory) {
                    val psiDirectory = psiManager.findDirectory(virtualFile) ?: return null
                    return from(psiDirectory, filter)
                } else {
                    val psiFile = psiManager.findFile(virtualFile) ?: return null
                    return from(psiFile, filter)
                }
            }

            fun from(element: PsiElement, filter: String? = null): FilteredTest? {
                return when (element) {
                    is PsiDirectory -> from(element, filter)
                    else -> element.containingFile?.let { from(it, filter) }
                }
            }

            fun from(psiFile: PsiFile, filter: String? = null): FilteredTest? {
                val elmFile = psiFile as? ElmFile ?: return null
                val moduleName = elmFile.getModuleDecl()?.name ?: return null

                return FilteredTest(psiFile.virtualFile.path, moduleName, false, filter)
            }

            fun from(psiDirectory: PsiDirectory, filter: String? = null): FilteredTest? {
                return FilteredTest(psiDirectory.virtualFile.path, psiDirectory.name, true, filter)
            }
        }

        fun runnableFilePath(): String {
            return if (testIsDirectory) "$filePath/**/*.elm" else filePath
        }
    }

    override fun getIcon() = RUN_ICON

    @Throws(InvalidDataException::class)
    override fun readExternal(element: Element) {
        options = readOptions(element)
    }

    @Throws(WriteExternalException::class)
    override fun writeExternal(element: Element) {
        writeOptions(options, element)
    }

    override fun suggestedName(): String? {
        val elmFolder = options.elmFolder

        if (!options.filteredTestConfig?.moduleName.isNullOrBlank()) {
            return if (options.filteredTestConfig?.filter.isNullOrBlank()) {
                "Tests in ${options.filteredTestConfig?.moduleName}"
            } else {
                "Tests in ${options.filteredTestConfig?.moduleName}: ${options.filteredTestConfig?.filter}"
            }
        }

        if (elmFolder != null) {
            return "Tests in ${Paths.get(elmFolder).fileName}"
        }

        return null
    }

    companion object {

        // <ElmTestRunConfiguration elm-folder="" />

        fun writeOptions(options: Options, element: Element) {
            val name = ElmTestRunConfiguration::class.java.simpleName
            val e = element.getChild(name) ?: Element(name).also { element.addContent(it) }

            e.setAttribute("elm-folder", options.elmFolder ?: "")
            if (options.filteredTestConfig != null) {
                e.setAttribute("test-file-path", options.filteredTestConfig?.filePath)
                e.setAttribute("test-file-module", options.filteredTestConfig?.moduleName)
                if (!options.filteredTestConfig?.filter.isNullOrBlank()) {
                    e.setAttribute("test-filter", options.filteredTestConfig?.filter)
                }
            }
        }

        fun readOptions(element: Element): Options {
            return Options().apply {
                val name = ElmTestRunConfiguration::class.java.simpleName
                val child = element.getChild(name)
                elmFolder = child?.getAttribute("elm-folder")?.value
                filteredTestConfig = FilteredTest.from(
                    child?.getAttribute("test-file-path")?.value,
                    child?.getAttribute("test-file-module")?.value,
                    child?.getAttribute("test-filter")?.value
                )

            }
        }
    }
}
