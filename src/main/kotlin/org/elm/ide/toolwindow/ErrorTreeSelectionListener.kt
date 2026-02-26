package org.elm.ide.toolwindow

import com.intellij.ide.errorTreeView.ErrorTreeNodeDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import javax.swing.event.TreeSelectionEvent
import javax.swing.event.TreeSelectionListener

class ErrorTreeSelectionListener(
    private val project: Project,
    private val messages: List<String>
) : TreeSelectionListener {

    override fun valueChanged(e: TreeSelectionEvent) {
        val collectSelectedUserObject = TreeUtil.collectSelectedUserObjects(e.source as Tree).firstOrNull() as? ErrorTreeNodeDescriptor
            ?: return
        val prefixedErrorMessage = collectSelectedUserObject.element.text[0]
        val index = prefixedErrorMessage.count { it == '​' }
        if (index >= 0 && index < messages.size) {
            val friendlyToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Friendly Messages") ?: return
            val reportUI = (friendlyToolWindow.contentManager.contents.firstOrNull()?.component as? ReportPanel)?.reportUI ?: return
            reportUI.text = messages[index]
            reportUI.caretPosition = 0
            friendlyToolWindow.show(null)
        }
    }
}
