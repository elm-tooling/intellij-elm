package org.elm.ide.toolwindow

import com.intellij.ide.DataManager
import com.intellij.ide.errorTreeView.ErrorTreeNodeDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.util.TextRange
import com.intellij.ui.content.impl.ContentImpl
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.util.ui.MessageCategory
import org.elm.workspace.ElmReviewService
import org.elm.workspace.elmReviewService
import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.Region
import java.nio.file.Path

class ElmReviewToolWindowFactory : ToolWindowFactory {
    override suspend fun isApplicableAsync(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val errorTreeViewPanel = ElmReviewErrorTreeViewPanel(project)
        toolWindow.contentManager.addContent(ContentImpl(errorTreeViewPanel, "Elm Review Results", true))

        with(project.messageBus.connect()) {
            subscribe(ElmReviewService.ELM_REVIEW_WATCH_TOPIC, object : ElmReviewService.ElmReviewWatchListener {

                override fun update(baseDirPath: Path, messages: List<ElmReviewError>) {
                    invokeLater {
                        errorTreeViewPanel.clearMessages()

                        val renderedDetails = mutableListOf<String>()
                        val issues = mutableListOf<ElmReviewIssue>()
                        messages.forEachIndexed { index, elmReviewError ->
                            val sourceLocation = elmReviewError.path ?: return@forEachIndexed
                            val virtualFile = baseDirPath.resolve(sourceLocation).let {
                                LocalFileSystem.getInstance().findFileByPath(it.toString())
                            }
                            renderedDetails += elmReviewError.html ?: (elmReviewError.message ?: "")
                            val encodedIndex = "\u200B".repeat(index)
                            val issue = ElmReviewIssue(baseDirPath, sourceLocation, virtualFile, elmReviewError)
                            issues += issue
                            updateErrorTree(errorTreeViewPanel, encodedIndex, issue)
                        }

                        errorTreeViewPanel.setIssues(issues)
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
        issue: ElmReviewIssue
    ) {
        val elmReviewError = issue.error
        val ruleText = (elmReviewError.rule ?: "") + if (issue.isFixable) " (auto-fix)" else ""
        if (elmReviewError.region == null) {
            errorTreeViewPanel.addErrorMessage(
                MessageCategory.SIMPLE, arrayOf("$encodedIndex$ruleText:", elmReviewError.message ?: ""),
                issue.virtualFile,
                0,
                0,
                elmReviewError.html ?: "General Error !"
            )
        } else {
            errorTreeViewPanel.addErrorMessage(
                MessageCategory.SIMPLE, arrayOf("$encodedIndex$ruleText:", "${elmReviewError.message}"),
                issue.virtualFile,
                elmReviewError.region!!.start.let { it!!.line - 1 },
                elmReviewError.region!!.start.let { it!!.column - 1 },
                elmReviewError.html!!
            )
        }
    }
}

private data class ElmReviewIssue(
    val baseDirPath: Path,
    val sourceLocation: String,
    val virtualFile: VirtualFile?,
    val error: ElmReviewError
) {
    val fixes: List<org.elm.workspace.elmreview.Fix> get() = error.fix.orEmpty()
    val isFixable: Boolean get() = fixes.isNotEmpty()
}

private class ElmReviewErrorTreeViewPanel(project: Project) : ElmErrorTreeViewPanel(project, "elm-review", false, true) {
    private val projectRef = project
    private var issues: List<ElmReviewIssue> = emptyList()

    fun setIssues(issues: List<ElmReviewIssue>) {
        this.issues = issues
    }

    override fun fillRightToolbarGroup(group: DefaultActionGroup) {
        super.fillRightToolbarGroup(group)
        group.addSeparator()
        group.add(FixSelectedIssueAction())
        group.add(FixAllIssuesAction())
    }

    private inner class FixSelectedIssueAction : DumbAwareAction("Fix", "Apply fix to selected issue", AllIcons.Actions.Lightning) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedIssue()?.isFixable == true
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedIssue()?.let { applyFixes(listOf(it)) }
        }
    }

    private inner class FixAllIssuesAction : DumbAwareAction("Fix All", "Apply fixes to all auto-fixable issues", AllIcons.Actions.RefactoringBulb) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = issues.any { it.isFixable }
        }

        override fun actionPerformed(e: AnActionEvent) {
            applyFixes(issues.filter { it.isFixable })
        }
    }

    private fun selectedIssue(): ElmReviewIssue? {
        val selected = TreeUtil.collectSelectedUserObjects(myTree).firstOrNull() as? ErrorTreeNodeDescriptor
            ?: return null
        val prefixedErrorMessage = selected.element.text.firstOrNull() ?: return null
        val index = prefixedErrorMessage.count { it == '\u200B' }
        return issues.getOrNull(index)
    }

    private fun applyFixes(targetIssues: List<ElmReviewIssue>) {
        if (targetIssues.isEmpty()) return

        val patchesByDocument = linkedMapOf<Document, MutableList<Pair<String, Region>>>()
        var firstFileToFocus: VirtualFile? = null
        targetIssues.forEach { issue ->
            if (!issue.isFixable) return@forEach
            val file = issue.virtualFile ?: LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(issue.baseDirPath.resolve(issue.sourceLocation).toString())
                ?: return@forEach
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@forEach
            if (firstFileToFocus == null) firstFileToFocus = file
            val patches = issue.fixes.map { it.string to it.range }
            patchesByDocument.getOrPut(document) { mutableListOf() }.addAll(patches)
        }
        if (patchesByDocument.isEmpty()) return

        WriteCommandAction.writeCommandAction(projectRef)
            .withName(if (targetIssues.size == 1) "Apply Elm Review Fix" else "Apply All Elm Review Fixes")
            .run<RuntimeException> {
                patchesByDocument.forEach { (document, patches) ->
                    patches
                        .mapNotNull { (replacement, region) ->
                            PatchLocation.fromRegion(document, region)?.let { replacement to it }
                        }
                        .sortedByDescending { (_, location) -> location.startOffset() }
                        .forEach { (replacement, location) ->
                            when (location) {
                                is PatchLocation.Range -> document.replaceString(location.value.startOffset, location.value.endOffset, replacement)
                                is PatchLocation.Point -> document.insertString(location.value, replacement)
                            }
                        }
                    FileDocumentManager.getInstance().saveDocument(document)
                }
            }

        firstFileToFocus?.let { OpenFileDescriptor(projectRef, it).navigate(true) }
        projectRef.elmReviewService.runReview(targetIssues.first().baseDirPath)
    }
}

private sealed class PatchLocation : Comparable<PatchLocation> {
    abstract fun startOffset(): Int

    override fun compareTo(other: PatchLocation): Int = startOffset().compareTo(other.startOffset())

    class Range(val value: TextRange) : PatchLocation() {
        override fun startOffset(): Int = value.startOffset
    }

    class Point(val value: Int) : PatchLocation() {
        override fun startOffset(): Int = value
    }

    companion object {
        fun fromRegion(document: Document, region: Region): PatchLocation? {
            return if (region.start?.line == region.end?.line && region.start?.column == region.end?.column) {
                Region.toOffset(document, region.start?.line ?: return null, region.start?.column ?: return null)?.let { Point(it) }
            } else {
                region.toTextRange(document)?.let { Range(it) }
            }
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
