package org.elm.ide.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.ide.notifications.showBalloon
import org.elm.openapiext.saveAllDocuments
import org.elm.workspace.commandLineTools.makeAllTargets
import java.nio.file.Files

const val ELM_BUILD_ALL_ACTION_ID = "Elm.BuildAll"
const val ELM_COMPILER_TOOL_WINDOW_ID = "Elm Compiler"

/**
 * Build every build target across all Elm projects and show the combined, deduplicated
 * errors in the Elm Compiler tool window.
 *
 * Available from the command palette ("Find Action"). It has no default keyboard shortcut,
 * but the user can assign one via Settings | Keymap.
 */
class ElmBuildAllAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Open the Elm Compiler tool window (unless it is already open) before building, so
        // the build output and errors are visible even when there is nothing to build.
        openElmCompilerToolWindow(project)
        buildAllTargets(project)
    }
}

/**
 * Resolve and build all targets across every Elm project, then post the combined,
 * deduplicated errors to the Elm Compiler tool window. Does nothing (beyond saving open
 * documents) when there are no targets configured.
 *
 * Must be called on the EDT; the actual compilation runs on a pooled thread.
 */
fun buildAllTargets(project: Project) {
    saveAllDocuments()
    val currentFileInEditor: VirtualFile? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
    val targets = resolveAllBuildTargets(project)
    if (targets.isEmpty()) return

    ApplicationManager.getApplication().executeOnPooledThread {
        val existing = targets.filter { Files.exists(it.inputPath) }
        if (existing.isEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                project.showBalloon(
                    "Cannot build targets: no build target input files were found.",
                    NotificationType.ERROR
                )
            }
            return@executeOnPooledThread
        }
        // makeAllTargets groups by working directory so each target compiles in its own project.
        makeAllTargets(project, existing, currentFileInEditor)
    }
}
