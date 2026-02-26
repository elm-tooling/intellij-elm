package org.elm.ide.status

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.ClickListener
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.elm.ide.icons.ElmIcons
import org.elm.workspace.ElmReviewService
import org.elm.workspace.elmSettings
import org.elm.workspace.elmWorkspace
import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.ui.ElmWorkspaceConfigurable
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JComponent

class ElmReviewWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ElmReviewWidget.ID
    override fun getDisplayName(): String = "Elm Review"
    override fun isAvailable(project: Project): Boolean = project.elmWorkspace.allProjects.isNotEmpty()
    override fun createWidget(project: Project): StatusBarWidget = ElmReviewWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class ElmReviewWidgetUpdater(private val project: Project) : ElmReviewService.ElmReviewWatchListener {
    override fun update(baseDirPath: Path, messages: List<ElmReviewError>) {
        project.service<StatusBarWidgetsManager>().updateWidget(ElmReviewWidgetFactory::class.java)
    }
}

class ElmReviewWidget(private val project: Project) : TextPanel.WithIconAndArrows(), CustomStatusBarWidget {
    private var statusBar: StatusBar? = null

    init {
        setTextAlignment(CENTER_ALIGNMENT)
        border = JBUI.CurrentTheme.StatusBar.Widget.border()
    }

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar

        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ElmWorkspaceConfigurable::class.java)
                return true
            }
        }.installOn(this, true)

        update()
    }

    override fun dispose() {
        statusBar = null
        UIUtil.dispose(this)
    }

    override fun getComponent(): JComponent = this

    private fun update() {
        if (project.isDisposed) return
        UIUtil.invokeLaterIfNeeded {
            if (project.isDisposed) return@invokeLaterIfNeeded
            val enabled = project.elmSettings.toolchain.isElmReviewOnTheFlyEnabled
            text = "Elm Review"
            toolTipText = if (enabled) {
                "elm-review watcher highlights are enabled"
            } else {
                "elm-review watcher highlights are disabled"
            }
            icon = if (enabled) ElmIcons.COLORFUL else AllIcons.Actions.OfflineMode
            repaint()
        }
    }

    companion object {
        const val ID: String = "elmReviewWidget"
    }
}
