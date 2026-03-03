package org.elm.ide.statusbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.AsyncProcessIcon
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

private const val ELM_TASK_STATUS_WIDGET_ID = "org.elm.statusBar.tasks"

class ElmTaskStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ELM_TASK_STATUS_WIDGET_ID

    override fun getDisplayName(): String = "Elm Task Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): CustomStatusBarWidget = ElmTaskStatusBarWidget(project)

    override fun disposeWidget(widget: com.intellij.openapi.wm.StatusBarWidget) {
        Disposer.dispose(widget as ElmTaskStatusBarWidget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

private class ElmTaskStatusBarWidget(project: Project) : CustomStatusBarWidget, Disposable {

    private val spinner = AsyncProcessIcon("ElmTaskStatus").apply { suspend() }
    private val label = JBLabel("Elm idle")
    private val panel = JPanel(HorizontalLayout(6)).apply {
        isOpaque = false
        add(spinner)
        add(label)
    }
    private val root = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(panel, BorderLayout.CENTER)
        isVisible = false
    }

    private val connection: MessageBusConnection = project.messageBus.connect(this).apply {
        subscribe(ElmTaskStatusService.TOPIC, object : ElmTaskStatusService.Listener {
            override fun statusChanged(state: ElmTaskStatusService.State) {
                render(state)
            }
        })
    }

    init {
        render(project.elmTaskStatus.currentState())
    }

    override fun ID(): String = ELM_TASK_STATUS_WIDGET_ID

    override fun install(statusBar: StatusBar) {}

    override fun dispose() {
        connection.disconnect()
    }

    override fun getComponent(): JComponent = root

    private fun render(state: ElmTaskStatusService.State) {
        when {
            !state.isRunning -> {
                spinner.suspend()
                root.isVisible = false
            }
            state.isCompilerRunning && state.isReviewRunning -> {
                label.text = "elm compiler + elm-review"
                spinner.resume()
                root.isVisible = true
            }
            state.isCompilerRunning -> {
                label.text = "elm compiler"
                spinner.resume()
                root.isVisible = true
            }
            else -> {
                label.text = "elm-review"
                spinner.resume()
                root.isVisible = true
            }
        }
        root.revalidate()
        root.repaint()
    }
}
