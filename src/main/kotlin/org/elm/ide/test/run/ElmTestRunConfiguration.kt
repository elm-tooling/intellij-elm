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

    data class Options(var elmFolder: String? = null, var testFile: FilteredTest? = null)

    data class FilteredTest(val filePath: String, val label: String) {
        companion object {
            fun from(path: String?, project: Project): FilteredTest? {
                if (path == null || path.isEmpty())  return null

                val virtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$path") ?: return null

                return from(virtualFile, project)
            }

            fun from(path: String?, label: String?): FilteredTest? {
                if (path.isNullOrBlank() || label.isNullOrBlank()) return null

                return FilteredTest(path, label)
            }

            fun from(virtualFile: VirtualFile, project: Project): FilteredTest? {
                val psiManager = PsiManager.getInstance(project)
                val psiFile = psiManager.findFile(virtualFile) ?: return null

                return from(psiFile)
            }

            fun from(element: PsiElement): FilteredTest? {
                return when (element) {
                    is PsiDirectory -> from(element)
                    else -> element.containingFile?.let { from(it) }
                }
            }

            fun from(psiFile: PsiFile): FilteredTest? {
                val elmFile = psiFile as? ElmFile ?: return null
                val moduleName = elmFile.getModuleDecl()?.name ?: return null

                return FilteredTest(psiFile.virtualFile.path, moduleName)
            }

            fun from(psiDirectory: PsiDirectory): FilteredTest? {
                return FilteredTest(psiDirectory.virtualFile.path, psiDirectory.name)
            }
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

        if (!options.testFile?.label.isNullOrBlank()) {
            return "Tests in ${options.testFile?.label}"
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
            if (options.testFile != null) {
                e.setAttribute("test-file-path", options.testFile?.filePath)
                e.setAttribute("test-file-module", options.testFile?.label)
            }
        }

        fun readOptions(element: Element): Options {
            return Options().apply {
                val name = ElmTestRunConfiguration::class.java.simpleName
                val child = element.getChild(name)
                elmFolder = child?.getAttribute("elm-folder")?.value
                testFile = FilteredTest.from(
                    child?.getAttribute("test-file-path")?.value,
                    child?.getAttribute("test-file-module")?.value
                )

            }
        }
    }
}
