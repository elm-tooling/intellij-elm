package org.elm.ide.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.impl.ContentImpl
import com.intellij.util.ui.MessageCategory
import org.elm.workspace.compiler.ELM_BUILD_ACTION_ID
import org.elm.workspace.compiler.ElmBuildAction
import org.elm.workspace.compiler.ElmError
import java.nio.file.Path

class ElmCompilerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val errorTreeViewPanel = object : ElmErrorTreeViewPanel(project, "Elm Compiler", createExitAction = false, createToolbar = true) {
            override fun getRerunAction(): AnAction? = ActionManager.getInstance().getAction(ELM_BUILD_ACTION_ID)
        }
        toolWindow.contentManager.addContent(ContentImpl(errorTreeViewPanel, "Compilation Result", true))

        
        with(project.messageBus.connect()) {
            subscribe(ElmBuildAction.ERRORS_TOPIC, object : ElmBuildAction.ElmErrorsListener {
                override fun update(baseDirPath: Path, messages: List<ElmError>, targetPath: String, offset: Int) {
                    errorTreeViewPanel.clearMessages()

                    messages.forEachIndexed { index, elmError ->
                        val sourceLocation = elmError.location
                        val virtualFile = sourceLocation?.let {
                            val fullPath = baseDirPath.resolve(it.path)
                            LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath.toString())
                        }

                        val encodedIndex = "\u200B".repeat(index)
                        errorTreeViewPanel.addMessage(
                            MessageCategory.ERROR, arrayOf("$encodedIndex${elmError.title}"),
                            virtualFile,
                            sourceLocation?.region?.start?.line?.minus(1) ?: 0,
                            sourceLocation?.region?.start?.column?.minus(1) ?: 0,
                            elmError.html
                        )
                    }

                    // Ensure UI updates happen on the Event Dispatch Thread
                    ToolWindowManager.getInstance(project).invokeLater {
                        toolWindow.contentManager.removeAllContents(true)
                        toolWindow.contentManager.addContent(
                            ContentImpl(errorTreeViewPanel, "Compilation Result", true)
                        )
                        errorTreeViewPanel.reload()
                        toolWindow.show(null)
                        errorTreeViewPanel.expandAll()
                        errorTreeViewPanel.requestFocus()
                        focusEditor(project)
                    }
                }
            })
        }
    }
}
