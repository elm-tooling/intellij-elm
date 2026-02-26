package org.elm.ide.toolwindow

import com.intellij.ide.errorTreeView.NewErrorTreeViewPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.event.TreeSelectionListener

abstract class ElmErrorTreeViewPanel(project: Project, helpId: String?, createExitAction: Boolean, createToolbar: Boolean) :
    NewErrorTreeViewPanel(project, helpId, createExitAction, createToolbar) {

    val messages = mutableListOf<String>()

    init {
        connectFriendlyMessages(project)
    }

    fun addErrorMessage(type: Int, text: Array<String>, file: VirtualFile?, line: Int, column: Int, html: String) {
        super.addMessage(type, text, file, line, column, null)
        messages.add(html)
    }

    private fun addSelectionListener(tsl: TreeSelectionListener) {
        myTree.addTreeSelectionListener(tsl)
    }

    private fun connectFriendlyMessages(project: Project) {
        addSelectionListener(ErrorTreeSelectionListener(project, messages))
        ToolWindowManager.getInstance(project).getToolWindow("Friendly Messages")?.let {
            val reportUI = (it.contentManager.contents.firstOrNull()?.component as? ReportPanel)?.reportUI ?: return@let
            reportUI.background = background
            reportUI.text = ""
        }
    }

    override fun fillRightToolbarGroup(group: DefaultActionGroup) {
        val rerunAction = getRerunAction()
        if (rerunAction != null) {
            group.addSeparator()
            group.add(rerunAction)
        }
    }

    open fun getRerunAction(): AnAction? = null

    fun clearMessages() {
        errorViewStructure.clear()
        messages.clear()
    }

    override fun canHideWarnings(): Boolean = false
}
