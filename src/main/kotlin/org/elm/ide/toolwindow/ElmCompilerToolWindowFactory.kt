package org.elm.ide.toolwindow

import com.google.gson.Gson
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.content.impl.ContentImpl
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.MessageCategory
import com.intellij.util.ui.UIUtil
import org.elm.ide.notifications.showBalloon
import org.elm.workspace.ElmProject
import org.elm.workspace.ElmWorkspaceService
import org.elm.workspace.commandLineTools.makeProject
import org.elm.workspace.compiler.*
import org.elm.workspace.elmWorkspace
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*

class ElmCompilerToolWindowFactory : ToolWindowFactory {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun isApplicable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val errorTreeViewPanel = ElmErrorTreeViewPanel(project, "Elm Compiler", false, true)
        val outputPanel = ElmCompilerOutputPanel(project)
        val splitPane = OnePixelSplitter(false, 0.58f).apply {
            firstComponent = errorTreeViewPanel
            secondComponent = outputPanel
        }
        val buildTargetsPanel = ElmBuildTargetsPanel(project)
        val root = JPanel(BorderLayout()).apply {
            add(buildTargetsPanel, BorderLayout.NORTH)
            add(splitPane, BorderLayout.CENTER)
        }
        toolWindow.contentManager.addContent(ContentImpl(root, "Compilation Result", true))

        with(project.messageBus.connect()) {
            subscribe(ERRORS_TOPIC, object : ElmBuildAction.ElmErrorsListener {
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

                    ToolWindowManager.getInstance(project).invokeLater {
                        errorTreeViewPanel.reload()
                        errorTreeViewPanel.expandAll()
                    }
                }
            })

            subscribe(COMPILER_OUTPUT_TOPIC, object : ElmBuildAction.ElmCompilerOutputListener {
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

private class ElmBuildTargetsPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val targetListModel = DefaultListModel<BuildTargetItem>()
    private val targetList = JBList(targetListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 4
        emptyText.text = "No build targets configured"
    }
    private val buildButton = JButton("Build Selected").apply {
        isEnabled = false
        addActionListener {
            buildSelectedTarget()
        }
    }

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 8)
        )
        add(JBLabel("Build Targets").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.emptyBottom(4)
        }, BorderLayout.NORTH)
        add(JScrollPane(targetList), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
            add(buildButton, BorderLayout.WEST)
        }, BorderLayout.SOUTH)

        targetList.addListSelectionListener {
            buildButton.isEnabled = targetList.selectedIndex >= 0
        }
        targetList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && targetList.selectedIndex >= 0) {
                    buildSelectedTarget()
                }
            }
        })
        val buildSelectedShortcutAction = object : DumbAwareAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                buildSelectedTarget()
            }
        }
        val buildShortcutSet = ActionManager.getInstance().getAction(ELM_BUILD_ACTION_ID)?.shortcutSet
            ?: CustomShortcutSet.fromString("alt shift P")
        buildSelectedShortcutAction.registerCustomShortcutSet(buildShortcutSet, this)

        refreshTargets()
    }

    fun refreshTargets() {
        targetListModel.clear()
        for (elmProject in project.elmWorkspace.allProjects.sortedBy { it.presentableName }) {
            val resolved = when (val result = project.elmWorkspace.resolveBuildTargets(elmProject)) {
                is org.elm.openapiext.Result.Ok -> result.value
                is org.elm.openapiext.Result.Err -> emptyList()
            }
            for ((index, target) in resolved.withIndex()) {
                targetListModel.addElement(BuildTargetItem(elmProject, target, index + 1))
            }
        }
        buildButton.isEnabled = targetList.selectedIndex >= 0
    }

    private fun buildSelectedTarget() {
        val item = targetList.selectedValue ?: return
        val currentFileInEditor: VirtualFile? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        ApplicationManager.getApplication().executeOnPooledThread {
            val stillExists = Files.exists(item.target.inputPath)
            if (!stillExists) {
                ApplicationManager.getApplication().invokeLater {
                    project.showBalloon(
                        "Cannot build target '${displayTargetName(item.target, item.index)}': input file not found.",
                        NotificationType.ERROR
                    )
                }
                return@executeOnPooledThread
            }
            makeProject(item.elmProject, project, listOf(item.target), currentFileInEditor)
        }
    }
}

private data class BuildTargetItem(
    val elmProject: ElmProject,
    val target: ResolvedBuildTarget,
    val index: Int
) {
    override fun toString(): String {
        val targetName = displayTargetName(target, index)
        return "$targetName (${elmProject.presentableName})"
    }
}

private fun displayTargetName(target: ResolvedBuildTarget, index: Int): String =
    target.name.ifBlank {
        target.inputPathForCompiler.ifBlank {
            "Target $index"
        }
    }

private class ElmCompilerOutputPanel(project: Project) : JPanel(BorderLayout()) {
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
        console.print("$label:\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        val parsed = parseCompilerOutput(text)
        if (parsed == null) {
            console.print(text, defaultType)
            if (!text.endsWith("\n")) {
                console.print("\n", defaultType)
            }
            return
        }

        console.print("Formatted compiler report:\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        renderReport(parsed.report)
        console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
    }

    private fun parseCompilerOutput(text: String): ParsedCompilerOutput? {
        val jsonPayload = extractJsonPayload(text) ?: return null
        val report = runCatching { Gson().fromJson(jsonPayload, Report::class.java) }.getOrNull() ?: return null
        return ParsedCompilerOutput(report)
    }

    private fun renderReport(report: Report) {
        when (report) {
            is Report.General -> {
                console.print("${report.title}\n", ConsoleViewContentType.NORMAL_OUTPUT)
                console.print("${report.path ?: "General"}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                renderChunks(report.message)
            }

            is Report.Specific -> {
                report.errors.forEachIndexed { errorIndex, badModule ->
                    badModule.problems.forEachIndexed { problemIndex, problem ->
                        if (errorIndex > 0 || problemIndex > 0) {
                            console.print("\n\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        }
                        console.print("${problem.title}\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        console.print(
                            "${badModule.path}:${problem.region.start.line}:${problem.region.start.column}\n",
                            ConsoleViewContentType.SYSTEM_OUTPUT
                        )
                        renderChunks(problem.message)
                    }
                }
            }
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
        val fg = parseElmColor(chunk.color)
        val key = listOf(fg.rgb, chunk.bold, chunk.underline).joinToString("|")
        return colorTypeCache.computeIfAbsent(key) {
            val effectType = if (chunk.underline) EffectType.LINE_UNDERSCORE else null
            val attrs = TextAttributes(
                fg,
                null,
                if (effectType != null) fg else null,
                effectType,
                if (chunk.bold) Font.BOLD else Font.PLAIN
            )
            ConsoleViewContentType("ELM_COMPILER_$key", attrs)
        }
    }

    private fun parseElmColor(raw: String?): Color {
        if (raw.isNullOrBlank()) return UIUtil.getLabelForeground()
        val normalized = raw.trim()
        if (normalized.startsWith("#")) {
            return runCatching {
                val c = Color.decode(normalized)
                JBColor(c, c)
            }.getOrElse { UIUtil.getLabelForeground() }
        }
        return when (normalized.uppercase()) {
            "RED" -> fixedColor(0xFF, 0x59, 0x59)
            "YELLOW" -> fixedColor(0xFA, 0xCF, 0x5A)
            "GREEN" -> fixedColor(0x5A, 0xD6, 0x7D)
            "BLUE" -> fixedColor(0x6C, 0xA0, 0xFF)
            "MAGENTA", "PURPLE" -> fixedColor(0xC5, 0x7B, 0xFF)
            "CYAN" -> fixedColor(0x4F, 0x9D, 0xA6)
            "BLACK" -> JBColor.BLACK
            "WHITE" -> JBColor.WHITE
            "GRAY", "GREY" -> JBColor.GRAY
            else -> UIUtil.getLabelForeground()
        }
    }

    private fun fixedColor(r: Int, g: Int, b: Int): JBColor {
        val rgb = (r shl 16) or (g shl 8) or b
        return JBColor(rgb, rgb)
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
