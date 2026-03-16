package org.elm.ide.toolwindow

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.errorTreeView.ErrorTreeNodeDescriptor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.impl.ContentImpl
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.MessageCategory
import com.intellij.util.ui.UIUtil
import org.elm.ide.actions.findActiveElmFile
import org.elm.openapiext.saveAllDocuments
import org.elm.workspace.ElmReviewService
import org.elm.workspace.elmReviewService
import org.elm.workspace.elmWorkspace
import org.elm.workspace.elmreview.Chunk
import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.ElmReviewErrorOrigin
import org.elm.workspace.elmreview.Region
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode

class ElmReviewToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val errorTreeViewPanel = ElmReviewErrorTreeViewPanel(project)
        val detailsPanel = ElmReviewDetailsPanel(project)
        errorTreeViewPanel.onIssueSelected = { issue ->
            detailsPanel.showIssueDetails(issue)
        }

        val splitPane = OnePixelSplitter(false, 0.58f).apply {
            firstComponent = errorTreeViewPanel
            secondComponent = detailsPanel
        }
        toolWindow.contentManager.addContent(ContentImpl(splitPane, "Elm Review Results", true))
        hydrateFromCachedMessages(project, toolWindow, errorTreeViewPanel)

        with(project.messageBus.connect()) {
            subscribe(ElmReviewService.ELM_REVIEW_WATCH_TOPIC, object : ElmReviewService.ElmReviewWatchListener {

                override fun update(baseDirPath: Path, messages: List<ElmReviewError>) {
                    invokeLater {
                        detailsPanel.clear()
                        errorTreeViewPanel.setIssues(toIssues(baseDirPath, messages), expand = toolWindow.isVisible)
                    }
                }
            })
        }
    }

    private fun hydrateFromCachedMessages(
        project: Project,
        toolWindow: ToolWindow,
        errorTreeViewPanel: ElmReviewErrorTreeViewPanel
    ) {
        val activeFile = findActiveElmFile(project) ?: return
        val activeElmProject = project.elmWorkspace.findProjectForFile(activeFile) ?: return
        val baseDirPath = activeElmProject.projectDirPath
        val cachedMessages = project.elmReviewService.messagesForCurrentProject(baseDirPath)
        errorTreeViewPanel.setIssues(toIssues(baseDirPath, cachedMessages), expand = toolWindow.isVisible)
    }
}

private fun toIssues(baseDirPath: Path, messages: List<ElmReviewError>): List<ElmReviewIssue> {
    val issues = mutableListOf<ElmReviewIssue>()
    messages.forEach { elmReviewError ->
        val sourceLocation = elmReviewError.path ?: return@forEach
        val virtualFile = baseDirPath.resolve(sourceLocation).let {
            LocalFileSystem.getInstance().findFileByPath(it.toString())
        }
        val issue = ElmReviewIssue(baseDirPath, sourceLocation, virtualFile, elmReviewError)
        issues += issue
    }
    return issues
}

private class ElmReviewDetailsPanel(project: Project) : JPanel(BorderLayout()) {
    private val colorTypeCache = ConcurrentHashMap<String, ConsoleViewContentType>()
    private val detailsConsole: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val cardLayout = CardLayout()
    private val detailsContainer = JPanel(cardLayout)
    private val emptyPanel = JPanel(BorderLayout()).apply {
        background = UIUtil.getPanelBackground()
        add(
            JBLabel("Select an elm-review issue to view details", SwingConstants.CENTER).apply {
                foreground = JBColor.GRAY
            },
            BorderLayout.CENTER
        )
    }

    init {
        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(8, 10)
            )
            add(
                JBLabel("Details").apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = UIUtil.getLabelForeground()
                },
                BorderLayout.WEST
            )
        }
        val content = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            add(header, BorderLayout.NORTH)
            detailsContainer.add(emptyPanel, "empty")
            detailsContainer.add(detailsConsole.component, "details")
            add(detailsContainer, BorderLayout.CENTER)
        }
        add(content, BorderLayout.CENTER)
        cardLayout.show(detailsContainer, "empty")
    }

    fun showIssueDetails(issue: ElmReviewIssue?) {
        val detailsText = issue?.error?.let { error ->
            val message = error.message?.takeIf { it.isNotBlank() }
            val detailLines = error.details.orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val formatted = error.formattedText?.takeIf { it.isNotBlank() }
            when {
                message != null && detailLines.isNotEmpty() && formatted != null ->
                    listOf(message, detailLines.joinToString("\n\n"), formatted).joinToString("\n\n")
                message != null && detailLines.isNotEmpty() ->
                    listOf(message, detailLines.joinToString("\n\n")).joinToString("\n\n")
                message != null && formatted != null ->
                    listOf(message, formatted).joinToString("\n\n")
                message != null -> message
                formatted != null -> formatted
                detailLines.isNotEmpty() -> detailLines.joinToString("\n\n")
                else -> null
            }
        }

        if (detailsText.isNullOrBlank()) {
            clear()
            return
        }
        detailsConsole.clear()
        val chunks = issue.error.formattedChunks.orEmpty()
        if (chunks.isNotEmpty()) {
            renderChunks(chunks)
        } else {
            detailsConsole.print(detailsText, ConsoleViewContentType.NORMAL_OUTPUT)
        }
        cardLayout.show(detailsContainer, "details")
    }

    fun clear() {
        detailsConsole.clear()
        cardLayout.show(detailsContainer, "empty")
    }

    private fun renderChunks(chunks: List<Chunk>) {
        chunks.forEach { chunk ->
            when (chunk) {
                is Chunk.Unstyled -> {
                    if (chunk.str.isNotEmpty()) {
                        detailsConsole.print(chunk.str, ConsoleViewContentType.NORMAL_OUTPUT)
                    }
                }
                is Chunk.Styled -> {
                    val text = chunk.string.orEmpty()
                    if (text.isEmpty()) return@forEach
                    detailsConsole.print(text, contentTypeFor(chunk))
                }
            }
        }
    }

    private fun contentTypeFor(chunk: Chunk.Styled): ConsoleViewContentType {
        return ElmConsoleChunkStyling.contentTypeFor(
            prefix = "ELM_REVIEW",
            cache = colorTypeCache,
            color = chunk.color,
            bold = chunk.bold == true,
            underline = chunk.underline == true
        )
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

internal fun elmReviewLocation(error: ElmReviewError): Pair<Int, Int>? {
    val start = error.region?.start ?: return null
    return (start.line - 1) to (start.column - 1)
}

internal fun elmReviewTreeMessage(error: ElmReviewError): String {
    val message = error.message.orEmpty()
    return if (error.origin == ElmReviewErrorOrigin.COMPILER) {
        message.substringBefore('\n')
    } else {
        message
    }
}

private class ElmReviewErrorTreeViewPanel(project: Project) : ElmErrorTreeViewPanel(project, "elm-review", false, true) {
    private val projectRef = project
    private var allIssues: List<ElmReviewIssue> = emptyList()
    private var issues: List<ElmReviewIssue> = emptyList()
    private var showSuppressed: Boolean = false
    var onIssueSelected: (ElmReviewIssue?) -> Unit = {}

    init {
        myTree.addTreeSelectionListener { onIssueSelected(selectedIssue()) }
    }

    fun setIssues(issues: List<ElmReviewIssue>, expand: Boolean) {
        this.allIssues = issues
        renderIssues(expand)
    }

    private fun renderIssues(expand: Boolean) {
        clearMessages()
        issues = if (showSuppressed) {
            allIssues
        } else {
            allIssues.filter { it.error.suppressed != true }
        }
        onIssueSelected(null)
        issues.forEachIndexed { index, issue ->
            // Encode 1-based index so the first issue has a marker too.
            val encodedIndex = "\u200B".repeat(index + 1)
            val elmReviewError = issue.error
            val ruleText = (elmReviewError.rule ?: "") + if (issue.isFixable) " (auto-fix)" else ""
            val treeMessage = elmReviewTreeMessage(elmReviewError)
            val location = elmReviewLocation(elmReviewError)
            if (location == null) {
                addErrorMessage(
                    MessageCategory.SIMPLE,
                    arrayOf("$encodedIndex$ruleText:", treeMessage),
                    issue.virtualFile,
                    0,
                    0
                )
            } else {
                addErrorMessage(
                    MessageCategory.SIMPLE,
                    arrayOf("$encodedIndex$ruleText:", treeMessage),
                    issue.virtualFile,
                    location.first,
                    location.second
                )
            }
        }
        reload()
        if (expand) expandAll()
    }

    override fun fillRightToolbarGroup(group: DefaultActionGroup) {
        super.fillRightToolbarGroup(group)
        group.add(RunOnProjectAction())
        group.add(ExpandAllAction())
        group.add(CollapseAllAction())
        group.addSeparator()
        group.add(ShowSuppressedAction())
        group.add(FixSelectedIssueAction())
        group.add(FixAllIssuesAction())
    }

    private inner class RunOnProjectAction : DumbAwareAction(
        "Run elm-review",
        "Run elm-review on the project for the current editor file",
        AllIcons.Actions.RunAll
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = activeEditorElmProject(e) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val elmProject = activeEditorElmProject(e) ?: return
            saveAllDocuments()
            projectRef.elmReviewService.runReviewFromManualAction(elmProject.projectDirPath, elmProject)
        }
    }

    private inner class ExpandAllAction : DumbAwareAction(
        { "Expand all" },
        AllIcons.Actions.Expandall
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.description = "Expand all elm-review messages"
            e.presentation.isEnabled = issues.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            expandAll()
        }
    }

    private inner class CollapseAllAction : DumbAwareAction(
        { "Collapse all" },
        AllIcons.Actions.Collapseall
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.description = "Collapse all elm-review messages"
            e.presentation.isEnabled = issues.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            collapseAllRows()
        }
    }

    private fun findActiveEditorElmFile(e: AnActionEvent): VirtualFile? =
        findActiveElmFile(projectRef, e.getData(CommonDataKeys.VIRTUAL_FILE))

    private fun activeEditorElmProject(e: AnActionEvent) =
        findActiveEditorElmFile(e)?.let { projectRef.elmWorkspace.findProjectForFile(it) }

    private inner class ShowSuppressedAction : ToggleAction(
        "Show Suppressed",
        "Show suppressed elm-review findings",
        AllIcons.Actions.ToggleVisibility
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            super.update(e)
            val isShowing = isSelected(e)
            e.presentation.text = if (isShowing) "Hide Suppressed" else "Show Suppressed"
            e.presentation.icon = if (isShowing) AllIcons.Actions.Unshare else AllIcons.Actions.ToggleVisibility
        }

        override fun isSelected(e: AnActionEvent): Boolean = showSuppressed

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (showSuppressed == state) return
            showSuppressed = state
            renderIssues(expand = true)
        }
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
        var node = myTree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        while (true) {
            val descriptor = node.userObject as? ErrorTreeNodeDescriptor
            val prefixedErrorMessage = descriptor?.element?.text?.firstOrNull().orEmpty()
            val encoded = prefixedErrorMessage.count { it == '\u200B' }
            if (encoded > 0) {
                return issues.getOrNull(encoded - 1)
            }
            node = node.parent as? DefaultMutableTreeNode ?: break
        }
        return null
    }

    private fun collapseAllRows() {
        for (row in myTree.rowCount - 1 downTo 0) {
            myTree.collapseRow(row)
        }
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
