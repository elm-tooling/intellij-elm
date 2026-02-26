package org.elm.ide.toolwindow

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.impl.ContentImpl
import com.intellij.util.ui.MessageCategory
import org.elm.workspace.ElmReviewService
import org.elm.workspace.elmreview.ElmReviewError
import java.nio.file.Path

@Suppress("DEPRECATION")
class ElmReviewToolWindowFactory : ToolWindowFactory {
    override suspend fun isApplicableAsync(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val errorTreeViewPanel = object : ElmErrorTreeViewPanel(project, "elm-review", createExitAction = false, createToolbar = true) {}
        toolWindow.contentManager.addContent(ContentImpl(errorTreeViewPanel, "elm-review Results", true))

        with(project.messageBus.connect()) {
            subscribe(ElmReviewService.ELM_REVIEW_WATCH_TOPIC, object : ElmReviewService.ElmReviewWatchListener {

                override fun update(baseDirPath: Path, messages: List<ElmReviewError>) {
                    invokeLater {
                        errorTreeViewPanel.clearMessages()

                        val renderedDetails = mutableListOf<String>()
                        messages.forEachIndexed { index, elmReviewError ->
                            val sourceLocation = elmReviewError.path ?: return@forEachIndexed
                            val virtualFile = baseDirPath.resolve(sourceLocation).let {
                                LocalFileSystem.getInstance().findFileByPath(it.toString())
                            }
                            renderedDetails += elmReviewError.html ?: (elmReviewError.message ?: "")
                            val encodedIndex = "\u200B".repeat(index)
                            updateErrorTree(errorTreeViewPanel, encodedIndex, elmReviewError, virtualFile)
                        }

                        errorTreeViewPanel.reload()
                        ToolWindowManager.getInstance(project).getToolWindow("Friendly Messages")?.let { friendly ->
                            val reportPanel = friendly.contentManager.contents.firstOrNull()?.component as? ReportPanel
                            reportPanel?.reportUI?.apply {
                                text = renderedDetails.firstOrNull().orEmpty()
                                caretPosition = 0
                            }
                        }
                        if (toolWindow.isVisible) {
                            toolWindow.show(null)
                            errorTreeViewPanel.expandAll()
                            errorTreeViewPanel.requestFocus()
                            focusEditor(project)
                        }
                    }
                }
            })
        }
    }

    private fun updateErrorTree(
        errorTreeViewPanel: ElmErrorTreeViewPanel,
        encodedIndex: String,
        elmReviewError: ElmReviewError,
        virtualFile: VirtualFile?
    ) {
        if (elmReviewError.region == null) {
            errorTreeViewPanel.addErrorMessage(
                MessageCategory.SIMPLE, arrayOf("$encodedIndex${elmReviewError.rule ?: ""}:", elmReviewError.message ?: ""),
                virtualFile,
                0,
                0,
                elmReviewError.html ?: "General Error !"
            )
        } else {
            errorTreeViewPanel.addErrorMessage(
                MessageCategory.SIMPLE, arrayOf("$encodedIndex${elmReviewError.rule}:", "${elmReviewError.message}"),
                virtualFile,
                elmReviewError.region!!.start.let { it!!.line - 1 },
                elmReviewError.region!!.start.let { it!!.column - 1 },
                elmReviewError.html!!
            )
        }
    }
}

fun focusEditor(project: Project) {
    DataManager.getInstance().dataContextFromFocusAsync.then {
        val editor = it.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
        }
    }
}
