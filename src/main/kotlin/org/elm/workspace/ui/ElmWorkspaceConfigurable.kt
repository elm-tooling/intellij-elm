package org.elm.workspace.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.keymap.impl.ui.KeymapPanel
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.util.ui.update.Activatable
import com.intellij.util.ui.update.UiNotifyConnector
import org.elm.ide.actions.ElmExternalFormatAction
import org.elm.openapiext.Result
import org.elm.openapiext.UiDebouncer
import org.elm.openapiext.fileSystemPathTextField
import org.elm.utils.layout
import org.elm.workspace.*
import org.elm.workspace.commandLineTools.ElmCLI
import org.elm.workspace.commandLineTools.ElmFormatCLI
import org.elm.workspace.commandLineTools.ElmReviewCLI
import org.elm.workspace.commandLineTools.ElmTestCLI
import org.elm.workspace.commandLineTools.LamderaCLI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*

class ElmWorkspaceConfigurable(
        private val project: Project
) : Configurable, Disposable {

    init {
        Disposer.register(project, this)
    }

    private val uiDebouncer = UiDebouncer(this)

    private fun toolPathTextField(programName: String): TextFieldWithBrowseButton {
        return fileSystemPathTextField(this, "Select '$programName'",
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                        .withFileFilter { it.name in ElmSuggest.executableNamesFor(programName) }
                        .also { it.isForcedToUseIdeaFileChooser = true })
        { update(setOf(programName)) }
    }

    private val elmPathField = toolPathTextField(elmCompilerTool)
    private val lamderaPathField = toolPathTextField(lamderaCompilerTool)
    private val elmFormatPathField = toolPathTextField(elmFormatTool)
    private val elmTestPathField = toolPathTextField(elmTestTool)
    private val elmReviewPathField = toolPathTextField(elmReviewTool)

    private val elmVersionLabel = JLabel()
    private val lamderaVersionLabel = JLabel()
    private val elmFormatVersionLabel = JLabel()
    private val elmFormatOnSaveCheckbox = JCheckBox()
    private val elmFormatShortcutLabel = HyperlinkLabel()
    private val elmTestVersionLabel = JLabel()
    private val elmReviewVersionLabel = JLabel()
    private val elmReviewOnTheFlyCheckbox = JCheckBox()
    private val versionCache = ConcurrentHashMap<Pair<String, String>, Result<Version>>()
    private val latestResults = ConcurrentHashMap<String, Result<Version>>()

    override fun createComponent(): JComponent {
        elmFormatOnSaveCheckbox.addChangeListener { update(emptySet()) }
        elmFormatShortcutLabel.addHyperlinkListener {
            showActionShortcut(ElmExternalFormatAction.ID)
        }

        val panel = layout {
            block("Elm Compiler") {
                row("Location:", pathFieldPlusAutoDiscoverButton(elmPathField, elmCompilerTool))
                row("Version:", elmVersionLabel)
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
                row("Version:", elmReviewVersionLabel)
                row("Run when file saved?", elmReviewOnTheFlyCheckbox)
            }
            block("Lamdera Compiler") {
                row("Location:", pathFieldPlusAutoDiscoverButton(lamderaPathField, lamderaCompilerTool))
                row("Version:", lamderaVersionLabel)
            }
            block("") {
                val nvmUrl = "https://github.com/nvm-sh/nvm"
                val docsUrl = "https://github.com/elm-tooling/intellij-elm/blob/main/docs/nvm.md"
                noteRow("""Using <a href="$nvmUrl">nvm</a>? Please read <a href="$docsUrl">our troubleshooting tips</a>.""")
            }
        }

        // Whenever this panel appears, refresh just in case the user made changes on the Keymap settings screen.
        // For IntelliJ Platform >2022.2.4:
        //    UiNotifyConnector.installOn(panel, object : Activatable {
        UiNotifyConnector.installOn(panel, object : Activatable {
            override fun showNotify() = update(null)
        }, true)  // `true` for parentDisposable auto-registration

        return panel
    }

    private fun pathFieldPlusAutoDiscoverButton(field: TextFieldWithBrowseButton, executableName: String): JPanel {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        with(panel) {
            add(field)
            add(JButton("Auto Discover").apply { addActionListener { field.text = autoDiscoverPathTo(executableName) } })
        }
        return panel
    }

    private fun autoDiscoverPathTo(programName: String) =
            ElmSuggest.suggestTools(project)[programName]?.toString() ?: ""

    private fun showActionShortcut(actionId: String) {
        val dataContext = DataManager.getInstance().getDataContext(elmFormatShortcutLabel)
        val allSettings = Settings.KEY.getData(dataContext) ?: return
        val keymapPanel = allSettings.find(KeymapPanel::class.java) ?: return
        allSettings.select(keymapPanel).doWhenDone {
            keymapPanel.selectAction(actionId)
        }
    }
    private fun update(changedTools: Set<String>? = null) {
        val toolsToUpdate = changedTools ?: elmTools.toSet()
        if (toolsToUpdate.isNotEmpty()) {
            uiDebouncer.run(
                onPooledThread = {
                    toolsToUpdate.associateWith { tool ->
                        queryVersion(tool, getToolPathText(tool))
                    }
                },
                onUiThread = { queried ->
                    queried.forEach { (tool, result) -> latestResults[tool] = result }
                    queried.keys.forEach { renderToolVersion(it) }
                }
            )
        }
        val shortcuts = KeymapUtil.getActiveKeymapShortcuts(ElmExternalFormatAction.ID).shortcuts
        val shortcutStatus = when {
            shortcuts.isEmpty() -> "No Shortcut"
            else -> shortcuts.joinToString(", ") { KeymapUtil.getShortcutText(it) }
        }
        elmFormatShortcutLabel.setTextWithHyperlink("$shortcutStatus <hyperlink>Change</hyperlink>")
    }

    private fun renderToolVersion(toolName: String) {
        when (toolName) {
            elmCompilerTool -> {
                val result = latestResults[elmCompilerTool]
                val path = parsePath(elmPathField.text)
                renderVersionLabel(
                    elmVersionLabel,
                    result,
                    path,
                    elmCompilerTool,
                    ElmToolchain.MIN_SUPPORTED_COMPILER_VERSION
                )
            }

            lamderaCompilerTool -> {
                val result = latestResults[lamderaCompilerTool]
                val path = parsePath(lamderaPathField.text)
                renderVersionLabel(
                    lamderaVersionLabel,
                    result,
                    path,
                    lamderaCompilerTool,
                    ElmToolchain.MIN_SUPPORTED_LAMDERA_COMPILER_VERSION
                )
            }

            elmFormatTool -> {
                val result = latestResults[elmFormatTool]
                val path = parsePath(elmFormatPathField.text)
                renderVersionLabel(elmFormatVersionLabel, result, path, elmFormatTool)
            }

            elmTestTool -> {
                val result = latestResults[elmTestTool]
                val path = parsePath(elmTestPathField.text)
                renderVersionLabel(elmTestVersionLabel, result, path, elmTestTool)
            }

            elmReviewTool -> {
                val result = latestResults[elmReviewTool]
                val path = parsePath(elmReviewPathField.text)
                renderVersionLabel(elmReviewVersionLabel, result, path, elmReviewTool)
            }
        }
    }

    private fun renderVersionLabel(
        label: JLabel,
        result: Result<Version>?,
        configuredPath: Path?,
        programName: String,
        minSupportedVersion: Version? = null
    ) {
        when (result) {
            is Result.Ok -> {
                if (minSupportedVersion != null && result.value < minSupportedVersion) {
                    label.text = "${result.value} (not supported)"
                    label.foreground = JBColor.RED
                } else {
                    label.text = result.value.toString()
                    label.foreground = JBColor.foreground()
                }
            }

            is Result.Err -> {
                if (configuredPath == null || !configuredPath.isValidFor(programName)) {
                    label.text = ""
                    label.foreground = JBColor.foreground()
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
            val path = parsePath(pathText) ?: return@computeIfAbsent Result.Err("Invalid path")
            when (programName) {
                elmCompilerTool -> ElmCLI(path).queryVersion(project)
                lamderaCompilerTool -> LamderaCLI(path).queryVersion(project)
                elmFormatTool -> ElmFormatCLI(path).queryVersion(project)
                elmTestTool -> ElmTestCLI(path).queryVersion(project)
                elmReviewTool -> ElmReviewCLI(path).queryVersion(project)
                else -> Result.Err("Unknown tool: $programName")
            }
        }
    }

    private fun getToolPathText(programName: String): String =
        when (programName) {
            elmCompilerTool -> elmPathField.text
            lamderaCompilerTool -> lamderaPathField.text
            elmFormatTool -> elmFormatPathField.text
            elmTestTool -> elmTestPathField.text
            elmReviewTool -> elmReviewPathField.text
            else -> ""
        }

    private fun parsePath(pathText: String): Path? = runCatching { Paths.get(pathText) }.getOrNull()

    override fun dispose() {
        // needed for the UIDebouncer, but nothing needs to be done here
    }

    override fun disposeUIResources() {
        // needed for Configurable, but nothing needs to be done here
    }

    override fun reset() {
        val settings = project.elmWorkspace.rawSettings
        val elmCompilerPath = settings?.elmCompilerPath
        val lamderaCompilerPath = settings?.lamderaCompilerPath
        val elmFormatPath = settings?.elmFormatPath
        val isElmFormatOnSaveEnabled = settings?.isElmFormatOnSaveEnabled
        val isElmReviewOnTheFlyEnabled = settings?.isElmReviewOnTheFlyEnabled
        val elmTestPath = settings?.elmTestPath
        val elmReviewPath = settings?.elmReviewPath

        if (elmCompilerPath != null) {
            elmPathField.text = elmCompilerPath
        }
        if (lamderaCompilerPath != null) {
            lamderaPathField.text = lamderaCompilerPath
        }
        if (elmFormatPath != null) {
            elmFormatPathField.text = elmFormatPath
        }
        elmFormatOnSaveCheckbox.isSelected = isElmFormatOnSaveEnabled == true
        if (elmTestPath != null) {
            elmTestPathField.text = elmTestPath
        }
        if (elmReviewPath != null) {
            elmReviewPathField.text = elmReviewPath
        }
        elmReviewOnTheFlyCheckbox.isSelected = isElmReviewOnTheFlyEnabled != false

        update(null)
    }

    override fun apply() {
        project.elmWorkspace.modifySettings {
            it.copy(elmCompilerPath = elmPathField.text,
                    lamderaCompilerPath = lamderaPathField.text,
                    elmFormatPath = elmFormatPathField.text,
                    elmTestPath = elmTestPathField.text,
                    elmReviewPath = elmReviewPathField.text,
                    isElmFormatOnSaveEnabled = isOnSaveHookEnabledAndSelected(),
                    isElmReviewOnTheFlyEnabled = elmReviewOnTheFlyCheckbox.isSelected
            )
        }
    }

    private fun isOnSaveHookEnabledAndSelected() =
            elmFormatOnSaveCheckbox.isEnabled && elmFormatOnSaveCheckbox.isSelected

    override fun isModified(): Boolean {
        val settings = project.elmWorkspace.rawSettings
        return elmPathField.text != settings?.elmCompilerPath
                || lamderaPathField.text != settings.lamderaCompilerPath
                || elmFormatPathField.text != settings.elmFormatPath
                || elmTestPathField.text != settings.elmTestPath
                || elmReviewPathField.text != settings.elmReviewPath
                || elmReviewOnTheFlyCheckbox.isSelected != settings.isElmReviewOnTheFlyEnabled
                || isOnSaveHookEnabledAndSelected() != settings.isElmFormatOnSaveEnabled
    }

    override fun getDisplayName() = "Elm"

    override fun getHelpTopic() = null
}

private fun Path.isValidFor(programName: String) =
        fileName != null && fileName.toString() in ElmSuggest.executableNamesFor(programName)
