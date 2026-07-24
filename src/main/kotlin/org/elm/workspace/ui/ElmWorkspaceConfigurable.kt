package org.elm.workspace.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.keymap.impl.ui.KeymapPanel
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.messages.MessageBusConnection
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBSplitter
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.util.ui.EditableModel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.update.Activatable
import com.intellij.util.ui.update.UiNotifyConnector
import org.elm.ide.actions.ElmExternalFormatAction
import org.elm.openapiext.Result
import org.elm.openapiext.UiDebouncer
import org.elm.openapiext.fileSystemPathTextField
import org.elm.openapiext.findFileByPathTestAware
import org.elm.utils.layout
import org.elm.workspace.ElmSuggest
import org.elm.workspace.ElmWorkspaceService
import org.elm.workspace.Version
import org.elm.workspace.elmCompilerTool
import org.elm.workspace.elmFormatTool
import org.elm.workspace.elmWrapCompilerTool
import org.elm.workspace.lamderaCompilerTool
import org.elm.workspace.elmReviewTool
import org.elm.workspace.elmTestTool
import org.elm.workspace.elmWorkspace
import org.elm.workspace.commandLineTools.ElmFormatCLI
import org.elm.workspace.commandLineTools.ElmReviewCLI
import org.elm.workspace.commandLineTools.ElmTestCLI
import org.elm.workspace.elmreview.ElmReviewCompilerSource
import org.elm.workspace.elmreview.resolveElmReviewCompiler
import org.elm.workspace.compiler.ElmBuildMode
import org.elm.workspace.compiler.ElmBuildTargetConfig
import org.elm.workspace.compiler.ElmCompilerKind
import org.elm.workspace.compiler.ElmProjectBuildTargetConfig
import org.elm.workspace.compiler.toPathOrNull
import java.awt.CardLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.DefaultListModel

class ElmWorkspaceConfigurable(
    private val project: Project
) : Configurable, Disposable {
    private companion object {
        const val BUILD_TARGETS_PANEL_PREFERRED_HEIGHT = 240
    }

    private val uiDebouncer = UiDebouncer(this)

    private fun toolPathTextField(programName: String): TextFieldWithBrowseButton {
        return fileSystemPathTextField(
            this,
            "Select '$programName'",
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withFileFilter { it.name in ElmSuggest.executableNamesFor(programName) }
                .also { it.isForcedToUseIdeaFileChooser = true }
        ) { update(setOf(programName)) }
    }

    private val elmFormatPathField = toolPathTextField(elmFormatTool)
    private val elmTestPathField = toolPathTextField(elmTestTool)
    private val elmReviewPathField = toolPathTextField(elmReviewTool)
    private val elmReviewConfigPathField = fileSystemPathTextField(
        this,
        "Select elm-review config folder",
        FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .also { it.isForcedToUseIdeaFileChooser = true }
    )
    private val toolchainCompilerPathField = fileSystemPathTextField(
        this,
        "Select '$elmCompilerTool'",
        FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withFileFilter { it.name in ElmSuggest.executableNamesFor(elmCompilerTool) }
            .also { it.isForcedToUseIdeaFileChooser = true }
    ) { updateReviewCompilerStatusLabel() }

    private val elmFormatVersionLabel = JLabel()
    private val elmFormatOnSaveCheckbox = JCheckBox()
    private val elmFormatShortcutLabel = HyperlinkLabel()
    private val elmTestVersionLabel = JLabel()
    private val elmReviewVersionLabel = JLabel()
    private val elmReviewCompilerStatusLabel = JLabel()
    private val elmReviewOnTheFlyCheckbox = JCheckBox()

    private val versionCache = ConcurrentHashMap<Pair<String, String>, Result<Version>>()
    private val latestResults = ConcurrentHashMap<String, Result<Version>>()

    private data class ProjectChoice(val label: String, val manifestPath: String) {
        override fun toString(): String = label
    }

    private val projectSelector = ComboBox<ProjectChoice>()

    private val targetListModel = TargetListModel()
    private val targetList = JBList(targetListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private val targetName = JTextField()
    private val targetInputPath = TextFieldWithBrowseButton()
    private val targetOutputPath = TextFieldWithBrowseButton()
    private val targetMode = ComboBox(ElmBuildMode.entries.toTypedArray())
    private val targetCompilerKind = ComboBox(ElmCompilerKind.entries.toTypedArray())
    private val targetCompilerPath = TextFieldWithBrowseButton()
    private val targetDetailsLayout = CardLayout()
    private val targetDetailsPanel = JPanel(targetDetailsLayout)

    private val buildTargetsByManifest = mutableMapOf<String, MutableList<ElmBuildTargetConfig>>()
    private var lastSelectedTargetIndex = -1
    private var isReorderingTargets = false
    private var isLoadingTargetDetails = false
    private var isLoadingProjectChoices = false
    private var workspaceBusConnection: MessageBusConnection? = null

    override fun createComponent(): JComponent {
        elmFormatOnSaveCheckbox.addChangeListener { update(emptySet()) }
        elmFormatShortcutLabel.addHyperlinkListener {
            showActionShortcut()
        }

        projectSelector.addActionListener {
            if (isLoadingProjectChoices) return@addActionListener
            persistCurrentProjectTargets()
            loadSelectedProjectTargets()
            updateReviewCompilerStatusLabel()
        }

        targetList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            // While a drag/up-down reorder is in progress the target that owns the current
            // form has moved, so persisting against the (now stale) index would corrupt data.
            // reorderTargets() persists once up front and syncs the editor afterwards.
            if (isReorderingTargets) return@addListSelectionListener
            persistTarget(lastSelectedTargetIndex)
            lastSelectedTargetIndex = targetList.selectedIndex
            loadTargetDetails(targetList.selectedIndex)
        }

        targetInputPath.textField.addActionListener {
            persistTarget(targetList.selectedIndex)
            refreshTargetListLabels(select = targetList.selectedIndex)
        }
        targetName.addActionListener {
            persistTarget(targetList.selectedIndex)
            refreshTargetListLabels(select = targetList.selectedIndex)
        }
        targetOutputPath.textField.addActionListener { persistTarget(targetList.selectedIndex) }
        targetMode.addActionListener { persistTarget(targetList.selectedIndex) }
        targetCompilerKind.addActionListener { persistTarget(targetList.selectedIndex) }
        targetCompilerPath.textField.addActionListener { persistTarget(targetList.selectedIndex) }

        targetInputPath.addActionListener {
            val path = selectProjectRelativeFile("Select Elm entry file", elmOnly = true) ?: return@addActionListener
            targetInputPath.text = path
            persistTarget(targetList.selectedIndex)
            refreshTargetListLabels(select = targetList.selectedIndex)
        }
        targetOutputPath.addActionListener {
            val path = selectProjectRelativeFile("Select output file", elmOnly = false) ?: return@addActionListener
            targetOutputPath.text = path
            persistTarget(targetList.selectedIndex)
        }
        targetCompilerPath.addActionListener {
            val path = selectCompilerExecutable() ?: return@addActionListener
            targetCompilerPath.text = path
            persistTarget(targetList.selectedIndex)
        }

        val panel = layout {
            block("Toolchain Compiler") {
                row("Location:", pathFieldPlusAutoDiscoverButton(toolchainCompilerPathField, elmCompilerTool))
                noteRow("Path to the compiler used by other tools")
            }
            block("Build") {
                row("Project:", projectSelector)
                noteRow("Targets:")
                row(buildTargetsPanel())
            }
            block(elmFormatTool) {
                row("Location:", pathFieldPlusAutoDiscoverButton(elmFormatPathField, elmFormatTool))
                row("Version:", elmFormatVersionLabel)
                row("Keyboard shortcut:", elmFormatShortcutLabel)
                row("Run when file saved?", elmFormatOnSaveCheckbox)
            }
            block(elmTestTool) {
                row("Location:", pathFieldPlusAutoDiscoverButton(elmTestPathField, elmTestTool))
                row("Version:", elmTestVersionLabel)
            }
            block(elmReviewTool) {
                row("Location:", pathFieldPlusAutoDiscoverButton(elmReviewPathField, elmReviewTool))
                row("Config folder:", elmReviewConfigPathField)
                noteRow("Blank uses ./review (project-relative). Relative and absolute paths are supported.")
                row("Version:", elmReviewVersionLabel)
                row("Compiler used:", elmReviewCompilerStatusLabel)
                row("Run when file saved?", elmReviewOnTheFlyCheckbox)
            }
            block("") {
                val nvmUrl = "https://github.com/nvm-sh/nvm"
                val docsUrl = "https://github.com/elm-tooling/intellij-elm/blob/main/docs/nvm.md"
                noteRow("""Using <a href="$nvmUrl">nvm</a>? Please read <a href="$docsUrl">our troubleshooting tips</a>.""")
            }
        }

        UiNotifyConnector.installOn(panel, object : Activatable {
            override fun showNotify() = update(null)
        }, true)

        workspaceBusConnection = project.messageBus.connect(this).also { connection ->
            connection.subscribe(
                ElmWorkspaceService.WORKSPACE_TOPIC,
                object : ElmWorkspaceService.ElmWorkspaceListener {
                    override fun didUpdate() {
                        reloadProjectChoices(preserveSelection = true)
                        updateReviewCompilerStatusLabel()
                    }
                }
            )
        }

        return panel
    }

    private fun buildTargetsPanel(): JComponent {
        val leftPanel = ToolbarDecorator.createDecorator(targetList)
            .setToolbarPosition(ActionToolbarPosition.TOP)
            .setMoveUpAction {
                val idx = targetList.selectedIndex
                if (idx > 0) {
                    reorderTargets(idx, idx - 1)
                    targetList.selectedIndex = idx - 1
                }
            }
            .setMoveDownAction {
                val idx = targetList.selectedIndex
                if (idx in 0 until targetListModel.size() - 1) {
                    reorderTargets(idx, idx + 1)
                    targetList.selectedIndex = idx + 1
                }
            }
            .setAddAction {
                val manifestPath = selectedManifestPath() ?: return@setAddAction
                val targets = buildTargetsByManifest.getOrPut(manifestPath) { mutableListOf() }
                val defaultCompilerPath = ElmSuggest.suggestTools(project)[elmCompilerTool]?.toString().orEmpty()
                targets += ElmBuildTargetConfig(
                    name = "Target ${targets.size + 1}",
                    compileOnSave = true,
                    compilerPath = defaultCompilerPath
                )
                refreshTargetListLabels(select = targets.lastIndex)
            }
            .setRemoveAction {
                val manifestPath = selectedManifestPath() ?: return@setRemoveAction
                val idx = targetList.selectedIndex
                if (idx < 0) return@setRemoveAction
                val targets = buildTargetsByManifest.getOrPut(manifestPath) { mutableListOf() }
                if (idx in targets.indices) {
                    targets.removeAt(idx)
                }
                refreshTargetListLabels(select = (idx - 1).coerceAtLeast(0))
            }
            .createPanel()

        val formPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(labeledField("Name", targetName))
            add(labeledField("Compiler", targetCompilerKind))
            add(labeledField("Compiler Path", targetCompilerPathWithAutoDiscoverButton()))
            add(labeledField("Input Elm File", targetInputPath))
            add(labeledField("Output", targetOutputPath))
            add(labeledField("Mode", targetMode))
        }

        val emptyPanel = JPanel(BorderLayout()).apply {
            add(JLabel("No build target selected"), BorderLayout.NORTH)
        }

        targetDetailsPanel.add(emptyPanel, "empty")
        targetDetailsPanel.add(
            JScrollPane(formPanel).apply {
                border = JBUI.Borders.empty()
            },
            "form"
        )
        targetDetailsLayout.show(targetDetailsPanel, "empty")

        return JBSplitter(false, 0.25f).apply {
            setHonorComponentsMinimumSize(false)
            leftPanel.minimumSize = Dimension(0, 0)
            targetDetailsPanel.minimumSize = Dimension(0, 0)
            firstComponent = leftPanel
            secondComponent = targetDetailsPanel
            preferredSize = Dimension(0, JBUI.scale(BUILD_TARGETS_PANEL_PREFERRED_HEIGHT))
        }
    }

    private fun labeledField(label: String, component: JComponent): JComponent =
        JPanel(BorderLayout(0, 2)).apply {
            minimumSize = Dimension(0, 0)
            border = JBUI.Borders.empty(2, 0, 6, 0)
            component.minimumSize = Dimension(0, component.minimumSize.height)
            add(JLabel(label), BorderLayout.NORTH)
            add(component, BorderLayout.CENTER)
        }

    private fun selectedManifestPath(): String? =
        (projectSelector.selectedItem as? ProjectChoice)?.manifestPath

    private fun currentTargets(): MutableList<ElmBuildTargetConfig> {
        val manifestPath = selectedManifestPath() ?: return mutableListOf()
        return buildTargetsByManifest.getOrPut(manifestPath) { mutableListOf() }
    }

    private fun refreshTargetListLabels(select: Int = -1) {
        targetListModel.clear()
        val targets = currentTargets()
        for ((index, target) in targets.withIndex()) {
            val name = target.name.ifBlank { target.inputPath.ifBlank { "Target ${index + 1}" } }
            targetListModel.addElement(name)
        }
        if (targets.isEmpty()) {
            lastSelectedTargetIndex = -1
            clearTargetEditor()
            targetDetailsLayout.show(targetDetailsPanel, "empty")
            return
        }
        val nextSelection = if (select in targets.indices) select else 0
        targetList.selectedIndex = nextSelection
        updateReviewCompilerStatusLabel()
    }

    private fun persistTarget(index: Int) {
        if (isLoadingTargetDetails) return
        val targets = currentTargets()
        if (index !in targets.indices) return
        targets[index] = ElmBuildTargetConfig(
            name = targetName.text.trim(),
            inputPath = targetInputPath.text.trim(),
            outputPath = targetOutputPath.text.trim(),
            mode = targetMode.selectedItem as? ElmBuildMode ?: ElmBuildMode.NONE,
            compilerKind = targetCompilerKind.selectedItem as? ElmCompilerKind ?: ElmCompilerKind.ELM,
            compilerPath = targetCompilerPath.text.trim(),
            compileOnSave = true
        )
        updateReviewCompilerStatusLabel()
    }

    private fun persistCurrentProjectTargets() {
        persistTarget(lastSelectedTargetIndex)
    }

    /**
     * A [DefaultListModel] whose rows can be reordered via the toolbar up/down buttons and by
     * drag-and-drop. [ToolbarDecorator] wires those up automatically once the model implements
     * [EditableModel]; adding and removing rows are still handled by the decorator's explicit
     * add/remove actions.
     */
    private inner class TargetListModel : DefaultListModel<String>(), EditableModel {
        override fun addRow() {} // handled by ToolbarDecorator.setAddAction
        override fun removeRow(index: Int) {} // handled by ToolbarDecorator.setRemoveAction

        override fun canExchangeRows(oldIndex: Int, newIndex: Int): Boolean {
            val count = currentTargets().size
            return oldIndex != newIndex && oldIndex in 0 until count && newIndex in 0 until count
        }

        override fun exchangeRows(oldIndex: Int, newIndex: Int) = reorderTargets(oldIndex, newIndex)
    }

    private fun reorderTargets(oldIndex: Int, newIndex: Int) {
        val targets = currentTargets()
        if (oldIndex !in targets.indices || newIndex !in targets.indices) return

        if (!isReorderingTargets) {
            // Save any in-progress edits of the selected target before it moves, then suppress
            // the selection listener until the reorder (and the framework's follow-up selection
            // change) has settled, at which point we re-sync the editor to the new selection.
            persistTarget(lastSelectedTargetIndex)
            isReorderingTargets = true
            ApplicationManager.getApplication().invokeLater {
                isReorderingTargets = false
                lastSelectedTargetIndex = targetList.selectedIndex
                loadTargetDetails(targetList.selectedIndex)
                updateReviewCompilerStatusLabel()
            }
        }

        val movedTarget = targets.removeAt(oldIndex)
        targets.add(newIndex, movedTarget)
        val movedLabel = targetListModel.getElementAt(oldIndex)
        targetListModel.removeElementAt(oldIndex)
        targetListModel.add(newIndex, movedLabel)
    }

    private fun loadSelectedProjectTargets() {
        lastSelectedTargetIndex = -1
        refreshTargetListLabels(select = 0)
    }

    private fun loadTargetDetails(index: Int) {
        val targets = currentTargets()
        if (index !in targets.indices) {
            clearTargetEditor()
            targetDetailsLayout.show(targetDetailsPanel, "empty")
            return
        }
        val target = targets[index]
        isLoadingTargetDetails = true
        try {
            targetName.text = target.name
            targetInputPath.text = target.inputPath
            targetOutputPath.text = target.outputPath
            targetMode.selectedItem = target.mode
            targetCompilerKind.selectedItem = target.compilerKind
            targetCompilerPath.text = target.compilerPath
        } finally {
            isLoadingTargetDetails = false
        }
        targetDetailsLayout.show(targetDetailsPanel, "form")
    }

    private fun clearTargetEditor() {
        targetName.text = ""
        targetInputPath.text = ""
        targetOutputPath.text = ""
        targetMode.selectedItem = ElmBuildMode.NONE
        targetCompilerKind.selectedItem = ElmCompilerKind.ELM
        targetCompilerPath.text = ""
    }

    private fun selectProjectRelativeFile(title: String, elmOnly: Boolean): String? {
        val manifestPath = selectedManifestPath() ?: return null
        val root = findFileByPathTestAware(Paths.get(manifestPath).parent) ?: return null
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withTitle(title)
            .also { it.isForcedToUseIdeaFileChooser = true }
        if (elmOnly) descriptor.withFileFilter { it.extension == "elm" }
        val file = FileChooser.chooseFile(descriptor, project, root) ?: return null
        return VfsUtilCore.getRelativePath(file, root) ?: file.path
    }

    private fun selectCompilerExecutable(): String? {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .also { it.isForcedToUseIdeaFileChooser = true }
            .withTitle("Select compiler executable")
        return FileChooser.chooseFile(descriptor, project, null)?.path
    }

    private fun pathFieldPlusAutoDiscoverButton(field: TextFieldWithBrowseButton, executableName: String): JPanel {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        panel.minimumSize = Dimension(0, 0)
        field.minimumSize = Dimension(0, field.minimumSize.height)
        panel.add(field)
        panel.add(
            JButton("Auto Discover").apply {
                addActionListener {
                    field.text = ElmSuggest.suggestTools(project)[executableName]?.toString() ?: ""
                    if (field === toolchainCompilerPathField) {
                        updateReviewCompilerStatusLabel()
                    }
                }
            }
        )
        return panel
    }

    private fun targetCompilerPathWithAutoDiscoverButton(): JPanel {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        panel.minimumSize = Dimension(0, 0)
        targetCompilerPath.minimumSize = Dimension(0, targetCompilerPath.minimumSize.height)
        panel.add(targetCompilerPath)
        panel.add(
            JButton("Auto Discover").apply {
                addActionListener {
                    val executableName = when (targetCompilerKind.selectedItem as? ElmCompilerKind ?: ElmCompilerKind.ELM) {
                        ElmCompilerKind.ELM -> elmCompilerTool
                        ElmCompilerKind.LAMDERA -> lamderaCompilerTool
                        ElmCompilerKind.WRAP -> elmWrapCompilerTool
                    }
                    targetCompilerPath.text = ElmSuggest.suggestTools(project)[executableName]?.toString().orEmpty()
                    persistTarget(targetList.selectedIndex)
                }
            }
        )
        return panel
    }

    private fun showActionShortcut() {
        val dataContext = DataManager.getInstance().getDataContext(elmFormatShortcutLabel)
        val allSettings = Settings.KEY.getData(dataContext) ?: return
        val keymapPanel = allSettings.find(KeymapPanel::class.java) ?: return
        allSettings.select(keymapPanel).doWhenDone {
            keymapPanel.selectAction(ElmExternalFormatAction.ID)
        }
    }

    private fun update(changedTools: Set<String>? = null) {
        val toolsToUpdate = changedTools ?: setOf(elmFormatTool, elmTestTool, elmReviewTool)
        if (toolsToUpdate.isNotEmpty()) {
            val queryInputs = toolsToUpdate.associateWith { getToolPathText(it) }
            uiDebouncer.run(
                onPooledThread = {
                    queryInputs.mapValues { (tool, inputPath) ->
                        runCatching { queryVersion(tool, inputPath) }
                            .getOrElse { Result.Err("Failed to query version: ${it.message}") }
                    }
                },
                onUiThread = { queried ->
                    queried.forEach { (tool, result) -> latestResults[tool] = result }
                    queried.keys.forEach { renderToolVersion(it) }
                }
            )
        }
        updateReviewCompilerStatusLabel()

        val shortcuts = KeymapUtil.getActiveKeymapShortcuts(ElmExternalFormatAction.ID).shortcuts
        val shortcutStatus = when {
            shortcuts.isEmpty() -> "No Shortcut"
            else -> shortcuts.joinToString(", ") { KeymapUtil.getShortcutText(it) }
        }
        elmFormatShortcutLabel.setTextWithHyperlink("$shortcutStatus <hyperlink>Change</hyperlink>")
    }

    private fun updateReviewCompilerStatusLabel() {
        val manifestPath = selectedManifestPath()
        val projectBasePath = manifestPath
            ?.let { runCatching { Paths.get(it).parent }.getOrNull() }
        if (projectBasePath == null) {
            elmReviewCompilerStatusLabel.text = "None"
            elmReviewCompilerStatusLabel.foreground = JBColor.GRAY
            return
        }

        val toolchainCompilerPath = toolchainCompilerPathField.text.trim().toPathOrNull()
        val buildTargets = buildTargetsByManifest[manifestPath].orEmpty()
        val resolution = resolveElmReviewCompiler(
            projectBasePath = projectBasePath,
            toolchainCompilerPath = toolchainCompilerPath,
            buildTargets = buildTargets,
            suggestedTools = ElmSuggest.suggestTools(project)
        )
        elmReviewCompilerStatusLabel.text = resolution.asDisplayText()
        elmReviewCompilerStatusLabel.foreground = when (resolution.source) {
            ElmReviewCompilerSource.NONE -> JBColor.GRAY
            else -> JBColor.foreground()
        }
    }

    private fun renderToolVersion(toolName: String) {
        when (toolName) {
            elmFormatTool -> renderVersionLabel(elmFormatVersionLabel, latestResults[elmFormatTool])
            elmTestTool -> renderVersionLabel(elmTestVersionLabel, latestResults[elmTestTool])
            elmReviewTool -> renderVersionLabel(elmReviewVersionLabel, latestResults[elmReviewTool])
        }
    }

    private fun renderVersionLabel(label: JLabel, result: Result<Version>?) {
        when (result) {
            is Result.Ok -> {
                label.text = result.value.toString()
                label.foreground = JBColor.foreground()
            }
            is Result.Err -> {
                if (result.reason == "Not configured") {
                    label.text = "Not configured"
                    label.foreground = JBColor.GRAY
                } else {
                    label.text = result.reason
                    label.foreground = JBColor.RED
                }
            }
            null -> {
                label.text = ""
                label.foreground = JBColor.foreground()
            }
        }
    }

    private fun queryVersion(programName: String, pathText: String): Result<Version> {
        if (pathText.isBlank()) return Result.Err("Not configured")
        val key = programName to pathText
        return versionCache.computeIfAbsent(key) {
            val path = runCatching { Paths.get(pathText) }.getOrNull() ?: return@computeIfAbsent Result.Err("Invalid path")
            when (programName) {
                elmFormatTool -> ElmFormatCLI(path).queryVersion(project)
                elmTestTool -> ElmTestCLI(path).queryVersion(project)
                elmReviewTool -> ElmReviewCLI(path).queryVersion(project)
                else -> Result.Err("Unknown tool: $programName")
            }
        }
    }

    private fun getToolPathText(programName: String): String =
        when (programName) {
            elmFormatTool -> elmFormatPathField.text
            elmTestTool -> elmTestPathField.text
            elmReviewTool -> elmReviewPathField.text
            else -> ""
        }

    override fun dispose() {}

    override fun disposeUIResources() {}

    override fun reset() {
        val settings = project.elmWorkspace.rawSettings
        val elmCompilerPath = settings?.elmCompilerPath
        val elmFormatPath = settings?.elmFormatPath
        val isElmFormatOnSaveEnabled = settings?.isElmFormatOnSaveEnabled
        val isElmReviewOnTheFlyEnabled = settings?.isElmReviewOnTheFlyEnabled
        val elmTestPath = settings?.elmTestPath
        val elmReviewPath = settings?.elmReviewPath
        val elmReviewConfigPath = settings?.elmReviewConfigPath

        if (elmCompilerPath != null) toolchainCompilerPathField.text = elmCompilerPath
        if (elmFormatPath != null) elmFormatPathField.text = elmFormatPath
        elmFormatOnSaveCheckbox.isSelected = isElmFormatOnSaveEnabled == true
        if (elmTestPath != null) elmTestPathField.text = elmTestPath
        if (elmReviewPath != null) elmReviewPathField.text = elmReviewPath
        elmReviewConfigPathField.text = elmReviewConfigPath ?: ""
        elmReviewOnTheFlyCheckbox.isSelected = isElmReviewOnTheFlyEnabled != false

        buildTargetsByManifest.clear()
        settings?.buildTargetsByManifest?.forEach { cfg ->
            buildTargetsByManifest[cfg.manifestPath] = cfg.targets.toMutableList()
        }

        reloadProjectChoices(preserveSelection = false)
        applyPendingBuildTargetSelection()

        update(null)
    }

    private fun applyPendingBuildTargetSelection() {
        val pending = project.elmWorkspace.consumePendingBuildTargetSelection() ?: return
        val projectIndex = (0 until projectSelector.itemCount).firstOrNull { idx ->
            projectSelector.getItemAt(idx).manifestPath == pending.manifestPath
        } ?: return

        projectSelector.selectedIndex = projectIndex
        val targets = currentTargets()
        val targetIndex = targets.indexOfFirst { target ->
            target.inputPath.trim() == pending.targetInputPath ||
                (pending.targetName.isNotBlank() && target.name.trim() == pending.targetName)
        }
        if (targetIndex >= 0) {
            targetList.selectedIndex = targetIndex
        }
    }

    private fun reloadProjectChoices(preserveSelection: Boolean) {
        val selectedManifestPath = if (preserveSelection) selectedManifestPath() else null
        isLoadingProjectChoices = true
        try {
            projectSelector.removeAllItems()
            project.elmWorkspace.allProjects
                .sortedBy { it.presentableName }
                .forEach { elmProject ->
                    val manifestPath = elmProject.manifestPath.toString()
                    projectSelector.addItem(ProjectChoice("${elmProject.presentableName} ($manifestPath)", manifestPath))
                }

            if (projectSelector.itemCount > 0) {
                val preserveIdx = selectedManifestPath
                    ?.let { wanted -> (0 until projectSelector.itemCount).firstOrNull { idx ->
                        projectSelector.getItemAt(idx).manifestPath == wanted
                    } }
                projectSelector.selectedIndex = preserveIdx ?: 0
                loadSelectedProjectTargets()
            } else {
                targetListModel.clear()
                clearTargetEditor()
                targetDetailsLayout.show(targetDetailsPanel, "empty")
                updateReviewCompilerStatusLabel()
            }
        } finally {
            isLoadingProjectChoices = false
        }
    }

    override fun apply() {
        persistCurrentProjectTargets()
        val buildTargets = buildTargetsByManifest.entries
            .sortedBy { it.key }
            .map { (manifestPath, targets) ->
                ElmProjectBuildTargetConfig(manifestPath = manifestPath, targets = targets.toList())
            }
        project.elmWorkspace.modifySettings {
            it.copy(
                elmCompilerPath = toolchainCompilerPathField.text,
                elmFormatPath = elmFormatPathField.text,
                elmTestPath = elmTestPathField.text,
                elmReviewPath = elmReviewPathField.text,
                elmReviewConfigPath = elmReviewConfigPathField.text,
                isElmFormatOnSaveEnabled = isOnSaveHookEnabledAndSelected(),
                isElmReviewOnTheFlyEnabled = elmReviewOnTheFlyCheckbox.isSelected,
                buildTargetsByManifest = buildTargets
            )
        }
    }

    private fun isOnSaveHookEnabledAndSelected() =
        elmFormatOnSaveCheckbox.isEnabled && elmFormatOnSaveCheckbox.isSelected

    override fun isModified(): Boolean {
        persistCurrentProjectTargets()
        val settings = project.elmWorkspace.rawSettings ?: ElmWorkspaceService.RawSettings()
        val currentTargets = buildTargetsByManifest.entries
            .sortedBy { it.key }
            .map { (manifestPath, targets) ->
                ElmProjectBuildTargetConfig(manifestPath = manifestPath, targets = targets.toList())
            }
        return toolchainCompilerPathField.text != settings.elmCompilerPath
            || elmFormatPathField.text != settings.elmFormatPath
            || elmTestPathField.text != settings.elmTestPath
            || elmReviewPathField.text != settings.elmReviewPath
            || elmReviewConfigPathField.text != settings.elmReviewConfigPath
            || elmReviewOnTheFlyCheckbox.isSelected != settings.isElmReviewOnTheFlyEnabled
            || isOnSaveHookEnabledAndSelected() != settings.isElmFormatOnSaveEnabled
            || currentTargets != settings.buildTargetsByManifest
    }

    override fun getDisplayName() = "Elm"

    override fun getHelpTopic() = null
}
