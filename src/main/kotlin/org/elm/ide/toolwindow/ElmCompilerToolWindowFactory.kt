package org.elm.ide.toolwindow

import com.google.gson.Gson
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.content.impl.ContentImpl
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.MessageCategory
import com.intellij.util.ui.UIUtil
import org.elm.ide.actions.buildTarget
import org.elm.ide.actions.buildTargetKeyOf
import org.elm.ide.actions.elmBuildTargetSelection
import org.elm.workspace.ElmProject
import org.elm.workspace.ElmWorkspaceService
import org.elm.workspace.compiler.*
import org.elm.workspace.elmWorkspace
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*

class ElmCompilerToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        var buildTargetsVisible = true
        var lastBuildTargetsProportion = 0.24f
        lateinit var buildTargetsPanel: ElmBuildTargetsPanel
        lateinit var root: OnePixelSplitter

        fun setBuildTargetsVisible(visible: Boolean) {
            if (visible == buildTargetsVisible) return
            buildTargetsVisible = visible
            if (visible) {
                root.firstComponent = buildTargetsPanel
                root.proportion = lastBuildTargetsProportion
            } else {
                lastBuildTargetsProportion = root.proportion
                root.firstComponent = null
            }
        }

        lateinit var buildTargetsPanelRef: ElmBuildTargetsPanel
        val errorTreeViewPanel = ElmCompilerErrorTreeViewPanel(
            project,
            onToggleBuildTargets = { setBuildTargetsVisible(!buildTargetsVisible) },
            isBuildTargetsVisible = { buildTargetsVisible },
            onBuildSelected = { buildTargetsPanelRef.buildSelectedTarget() },
            isBuildSelectedEnabled = { buildTargetsPanelRef.hasSelectedTarget() },
            onBuildAll = { org.elm.ide.actions.buildAllTargets(project) },
            isBuildAllEnabled = { buildTargetsPanelRef.hasTargets() },
            onRunTestsSelected = { buildTargetsPanelRef.runSelectedTestTarget() },
            isRunTestsSelectedVisible = { buildTargetsPanelRef.isTestTargetSelected() }
        )
        val outputPanel = ElmCompilerOutputPanel(project)
        val messagesAndOutputSplit = OnePixelSplitter(false, 0.56f).apply {
            firstComponent = errorTreeViewPanel
            secondComponent = outputPanel
        }
        buildTargetsPanel = ElmBuildTargetsPanel(
            project,
            onInvalidSelected = { message -> errorTreeViewPanel.showConfigError(message) },
            onValidSelected = { errorTreeViewPanel.clearConfigError() }
        )
        buildTargetsPanelRef = buildTargetsPanel
        root = OnePixelSplitter(false, 0.24f).apply {
            firstComponent = buildTargetsPanel
            secondComponent = messagesAndOutputSplit
        }
        toolWindow.contentManager.addContent(ContentImpl(root, "Compilation Result", true))

        with(project.messageBus.connect()) {
            subscribe(ERRORS_TOPIC, object : ElmErrorsListener {
                override fun update(baseDirPath: Path, messages: List<ElmError>, targetPath: String, offset: Int) {
                    errorTreeViewPanel.onBuildMessagesArrived()
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

                    ToolWindowManager.getInstance(project).invokeLater {
                        errorTreeViewPanel.reload()
                        errorTreeViewPanel.expandAll()
                    }
                }
            })

            subscribe(COMPILER_OUTPUT_TOPIC, object : ElmCompilerOutputListener {
                override fun update(toolName: String, commandLine: String, stdout: String, stderr: String, exitCode: Int) {
                    ToolWindowManager.getInstance(project).invokeLater {
                        outputPanel.showOutput(toolName, commandLine, stdout, stderr, exitCode)
                    }
                }
            })

            subscribe(ElmWorkspaceService.WORKSPACE_TOPIC, object : ElmWorkspaceService.ElmWorkspaceListener {
                override fun didUpdate() {
                    ApplicationManager.getApplication().invokeLater {
                        buildTargetsPanel.refreshTargets()
                    }
                }
            })
        }
    }
}

private class ElmBuildTargetsPanel(
    private val project: Project,
    private val onInvalidSelected: (String) -> Unit,
    private val onValidSelected: () -> Unit
) : JPanel(BorderLayout()) {
    private val targetListModel = DefaultListModel<BuildTargetItem>()
    private val targetList = JBList(targetListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 10
        emptyText.text = "No build targets configured"
        cellRenderer = object : ColoredListCellRenderer<BuildTargetItem>() {
            override fun customizeCellRenderer(
                list: JList<out BuildTargetItem>,
                value: BuildTargetItem,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value.error != null) {
                    icon = AllIcons.General.Error
                    append(value.displayName, SimpleTextAttributes.ERROR_ATTRIBUTES)
                } else {
                    append(value.displayName)
                }
            }
        }
    }
    private val addBuildTargetAction = AddBuildTargetAction()
    private val editBuildTargetAction = EditBuildTargetAction()
    private val actionToolbar = ActionManager.getInstance().createActionToolbar(
        "Elm Compiler Build Targets",
        DefaultActionGroup(addBuildTargetAction, editBuildTargetAction),
        false
    )

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 0, 1),
            JBUI.Borders.empty(6, 8)
        )
        add(JBLabel("Build Targets").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.emptyBottom(4)
        }, BorderLayout.NORTH)
        actionToolbar.targetComponent = targetList
        add(JPanel(BorderLayout()).apply {
            add(actionToolbar.component, BorderLayout.WEST)
            add(JScrollPane(targetList), BorderLayout.CENTER)
        }, BorderLayout.CENTER)

        targetList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val item = targetList.selectedValue
                project.elmBuildTargetSelection.selectedKey =
                    item?.target?.let { buildTargetKeyOf(it) }
                updateSelectionMessages()
            }
        }
        targetList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && targetList.selectedIndex >= 0) {
                    buildSelectedTarget()
                }
            }
        })

        refreshTargets()
    }

    fun refreshTargets() {
        val previousKey = project.elmBuildTargetSelection.selectedKey
        targetListModel.clear()
        for ((row, config, resolved, error) in project.elmWorkspace.resolveBuildTargetsDetailed()) {
            val displayName = resolved?.let { displayTargetName(it, row) }
                ?: displayConfigName(config, row)
            targetListModel.addElement(
                BuildTargetItem(
                    row, displayName, resolved, error, config
                )
            )
        }
        // Always keep one target selected (defaulting to the first), preserving the previous
        // selection when it still exists. Setting the index updates the selection service.
        if (!targetListModel.isEmpty) {
            val matchIndex = (0 until targetListModel.size()).firstOrNull {
                val item = targetListModel.getElementAt(it)
                item.target != null && buildTargetKeyOf(item.target) == previousKey
            } ?: 0
            targetList.selectedIndex = matchIndex
        }
        // Reflect the current selection's validity in the messages box (a refresh may have
        // changed which target is selected, or cleared the list entirely).
        updateSelectionMessages()
    }

    /** Show the selected target's config error in the messages box, or clear a shown one. */
    private fun updateSelectionMessages() {
        val error = targetList.selectedValue?.error
        if (error != null) onInvalidSelected(error) else onValidSelected()
    }

    fun hasSelectedTarget(): Boolean = targetList.selectedIndex >= 0

    fun hasTargets(): Boolean = !targetListModel.isEmpty

    fun buildSelectedTarget() {
        val item = targetList.selectedValue ?: return
        val target = item.target
        if (target == null) {
            // The selected target is misconfigured; show why instead of trying to build it.
            item.error?.let { onInvalidSelected(it) }
            return
        }
        buildTarget(project, target)
    }

    /** True when the selected target is a runnable (resolved) test target. */
    fun isTestTargetSelected(): Boolean =
        targetList.selectedValue?.target?.type == ElmBuildTargetType.TEST

    /** Run the tests for the selected test target; no-op if the selection is not a test target. */
    fun runSelectedTestTarget() {
        val target = targetList.selectedValue?.target ?: return
        if (target.type != ElmBuildTargetType.TEST) return
        org.elm.ide.actions.runElmTestsForTarget(project, target)
    }

    private fun editSelectedTarget() {
        val item = targetList.selectedValue ?: return
        // Locate the row by its configured name/input path so invalid targets can be edited (and fixed) too.
        val name = item.target?.name ?: item.config.name
        val inputPath = item.target?.inputPathForCompiler ?: item.config.inputPath
        project.elmWorkspace.showConfigureBuildTargetUI(name, inputPath)
    }

    private inner class EditBuildTargetAction : DumbAwareAction(
        "Edit build target",
        "Open settings for the selected build target",
        AllIcons.Actions.Edit
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            // Automatic test targets are not user-configured, so there is nothing to edit.
            val item = targetList.selectedValue
            e.presentation.isEnabled = item != null && !item.isAutomatic
        }

        override fun actionPerformed(e: AnActionEvent) {
            editSelectedTarget()
        }
    }

    private inner class AddBuildTargetAction : DumbAwareAction(
        "Add build target",
        "Open settings to add a build target",
        AllIcons.General.Add
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            project.elmWorkspace.showConfigureToolchainUI()
        }
    }
}

private data class BuildTargetItem(
    val index: Int,
    val displayName: String,
    /** The resolved target, or null when the target is misconfigured (see [error]). */
    val target: ResolvedBuildTarget?,
    /** Non-null when the target could not be resolved; the reason to surface to the user. */
    val error: String?,
    val config: ElmBuildTargetConfig
) {
    /** True for the automatic, non-editable test targets appended after the configured ones. */
    val isAutomatic: Boolean get() = config.type == ElmBuildTargetType.TEST
}

private fun displayTargetName(target: ResolvedBuildTarget, index: Int): String =
    target.name.ifBlank {
        target.inputPathForCompiler.ifBlank {
            "Target $index"
        }
    }

private fun displayConfigName(config: ElmBuildTargetConfig, index: Int): String =
    config.name.ifBlank {
        config.inputPath.ifBlank {
            "Target $index"
        }
    }

private class ElmCompilerErrorTreeViewPanel(
    project: Project,
    private val onToggleBuildTargets: () -> Unit,
    private val isBuildTargetsVisible: () -> Boolean,
    private val onBuildSelected: () -> Unit,
    private val isBuildSelectedEnabled: () -> Boolean,
    private val onBuildAll: () -> Unit,
    private val isBuildAllEnabled: () -> Boolean,
    private val onRunTestsSelected: () -> Unit,
    private val isRunTestsSelectedVisible: () -> Boolean
) : ElmErrorTreeViewPanel(project, "Elm Compiler", false, true) {
    /** True while the messages tree is showing a build-target config error (not compiler output). */
    private var showingConfigError = false

    /** Show a misconfigured target's error in the messages box (where it otherwise says "No messages"). */
    fun showConfigError(message: String) {
        clearMessages()
        for (line in message.lines()) {
            addMessage(MessageCategory.ERROR, arrayOf(line), null, -1, -1, null)
        }
        showingConfigError = true
        reload()
        expandAll()
    }

    /** Clear a previously shown config error, leaving real build output (if any) untouched. */
    fun clearConfigError() {
        if (!showingConfigError) return
        clearMessages()
        showingConfigError = false
        reload()
    }

    /** Called when real compiler output replaces the messages, so we stop treating it as a config error. */
    fun onBuildMessagesArrived() {
        showingConfigError = false
    }

    override fun fillRightToolbarGroup(group: DefaultActionGroup) {
        super.fillRightToolbarGroup(group)
        group.add(BuildSelectedAction())
        group.add(BuildAllAction())
        group.add(RunTestsSelectedAction())
        group.addSeparator()
        group.add(ToggleBuildTargetsAction())
        group.addSeparator()
        group.add(ExpandAllAction())
        group.add(CollapseAllAction())
    }

    private inner class BuildSelectedAction : DumbAwareAction(
        "Build selected",
        "Build the selected target",
        AllIcons.Actions.Execute
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = isBuildSelectedEnabled()
        }

        override fun actionPerformed(e: AnActionEvent) {
            onBuildSelected()
        }
    }

    private inner class BuildAllAction : DumbAwareAction(
        "Build all",
        "Build all targets and show their combined errors",
        AllIcons.Actions.RunAll
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = isBuildAllEnabled()
        }

        override fun actionPerformed(e: AnActionEvent) {
            onBuildAll()
        }
    }

    private inner class RunTestsSelectedAction : DumbAwareAction(
        "Run tests",
        "Run the tests for the selected test target",
        AllIcons.RunConfigurations.TestState.Run
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            // Only offer this for the automatic test targets; hidden for regular build targets.
            e.presentation.isVisible = isRunTestsSelectedVisible()
        }

        override fun actionPerformed(e: AnActionEvent) {
            onRunTestsSelected()
        }
    }

    private inner class ToggleBuildTargetsAction : DumbAwareAction(
        "Hide build targets",
        "Hide build targets panel",
        AllIcons.General.LayoutEditorOnly
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            val visible = isBuildTargetsVisible()
            e.presentation.text = if (visible) "Hide build targets" else "Show build targets"
            e.presentation.description = if (visible) "Hide build targets panel" else "Show build targets panel"
        }

        override fun actionPerformed(e: AnActionEvent) {
            onToggleBuildTargets()
        }
    }

    private inner class ExpandAllAction : DumbAwareAction(
        { "Expand all" },
        AllIcons.Actions.Expandall
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.description = "Expand all compiler messages"
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
            e.presentation.description = "Collapse all compiler messages"
        }

        override fun actionPerformed(e: AnActionEvent) {
            collapseAll()
        }
    }
}

private class ElmCompilerOutputPanel(project: Project) : JPanel(BorderLayout()) {
    private val projectRef = project
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val colorTypeCache = ConcurrentHashMap<String, ConsoleViewContentType>()
    private val cardLayout = CardLayout()
    private val content = JPanel(cardLayout)
    private val empty = JPanel(BorderLayout()).apply {
        background = UIUtil.getPanelBackground()
        add(
            JBLabel("Run Elm Build to view compiler output", JBLabel.CENTER).apply {
                foreground = JBColor.GRAY
            },
            BorderLayout.CENTER
        )
    }

    init {
        val header = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(8, 10)
            )
            add(
                JBLabel("Compiler Output").apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = UIUtil.getLabelForeground()
                },
                BorderLayout.WEST
            )
        }
        val panel = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            content.add(empty, "empty")
            content.add(console.component, "output")
            add(header, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
        add(panel, BorderLayout.CENTER)
        cardLayout.show(content, "empty")
    }

    fun showOutput(toolName: String, commandLine: String, stdout: String, stderr: String, exitCode: Int) {
        console.clear()
        console.print("Tool: $toolName\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("Command: $commandLine\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print("Exit Code: $exitCode\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        if (stdout.isNotBlank()) {
            renderOutputStream("STDOUT", stdout, ConsoleViewContentType.NORMAL_OUTPUT)
            console.print("\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }

        if (stderr.isNotBlank()) {
            renderOutputStream("STDERR", stderr, ConsoleViewContentType.ERROR_OUTPUT)
        }

        if (stdout.isBlank() && stderr.isBlank()) {
            console.print("(no output)\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }
        cardLayout.show(content, "output")
    }

    private fun renderOutputStream(label: String, text: String, defaultType: ConsoleViewContentType) {
        val parsed = parseCompilerOutput(text)
        if (parsed != null) {
            renderReport(parsed.report)
            console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
            return
        }

        console.print("$label:\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.print(text, defaultType)
        if (!text.endsWith("\n")) {
            console.print("\n", defaultType)
        }
    }

    private fun parseCompilerOutput(text: String): ParsedCompilerOutput? {
        val jsonPayload = extractJsonPayload(text) ?: return null
        val report = runCatching { Gson().fromJson(jsonPayload, Report::class.java) }.getOrNull() ?: return null
        return ParsedCompilerOutput(report)
    }

    private fun renderReport(report: Report) {
        when (report) {
            is Report.General -> {
                console.print("${report.title}\n", titleContentType(report.title))
                console.print("${displayPath(report.path)}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                renderChunks(report.message)
            }

            is Report.Specific -> {
                report.errors.forEachIndexed { errorIndex, badModule ->
                    badModule.problems.forEachIndexed { problemIndex, problem ->
                        if (errorIndex > 0 || problemIndex > 0) {
                            console.print("\n\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        }
                        console.print("${problem.title}\n", titleContentType(problem.title))
                        console.print(
                            "${displayPath(badModule.path)}:${problem.region.start.line}:${problem.region.start.column}\n",
                            ConsoleViewContentType.SYSTEM_OUTPUT
                        )
                        renderChunks(problem.message)
                    }
                }
            }
        }
    }

    private fun titleContentType(title: String): ConsoleViewContentType {
        val color = if (title.contains("ERROR", ignoreCase = true)) "RED" else null
        return ElmConsoleChunkStyling.contentTypeFor(
            prefix = "ELM_COMPILER_TITLE",
            cache = colorTypeCache,
            color = color,
            bold = true,
            underline = false
        )
    }

    private fun displayPath(rawPath: String?): String {
        if (rawPath.isNullOrBlank()) return "General"
        val path = runCatching { Path.of(rawPath).normalize() }.getOrNull() ?: return rawPath
        if (!path.isAbsolute) return rawPath
        val projectBase = projectRef.basePath?.let { runCatching { Path.of(it).normalize() }.getOrNull() } ?: return rawPath
        return if (path.startsWith(projectBase)) {
            projectBase.relativize(path).toString()
        } else {
            rawPath
        }
    }

    private fun renderChunks(chunks: List<Chunk>) {
        chunks.forEach { chunk ->
            when (chunk) {
                is Chunk.Unstyled -> {
                    if (chunk.string.isNotEmpty()) {
                        console.print(chunk.string, ConsoleViewContentType.NORMAL_OUTPUT)
                    }
                }

                is Chunk.Styled -> {
                    if (chunk.string.isNotEmpty()) {
                        console.print(chunk.string, contentTypeFor(chunk))
                    }
                }
            }
        }
    }

    private fun contentTypeFor(chunk: Chunk.Styled): ConsoleViewContentType {
        return ElmConsoleChunkStyling.contentTypeFor(
            prefix = "ELM_COMPILER",
            cache = colorTypeCache,
            color = chunk.color,
            bold = chunk.bold,
            underline = chunk.underline
        )
    }

    private fun extractJsonPayload(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start in 0..<end) {
            return trimmed.substring(start, end + 1)
        }
        return null
    }

    private data class ParsedCompilerOutput(
        val report: Report
    )
}
