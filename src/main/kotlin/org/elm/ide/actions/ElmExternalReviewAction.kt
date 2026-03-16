package org.elm.ide.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.ide.notifications.showBalloon
import org.elm.lang.core.ElmFileType
import org.elm.openapiext.saveAllDocuments
import org.elm.workspace.elmReviewService
import org.elm.workspace.elmWorkspace

internal data class ElmReviewRunFailure(val message: String, val includeFixAction: Boolean = false)

internal fun findActiveElmFile(project: Project, preferredFile: VirtualFile? = null): VirtualFile? =
    preferredFile?.takeIf { it.fileType == ElmFileType }
        ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull { it.fileType == ElmFileType }

internal fun runElmReviewOnCurrentFile(
    project: Project,
    activeFile: VirtualFile
): ElmReviewRunFailure? {
    if (activeFile.canonicalPath?.contains("/review") == true) return null

    val elmProject = project.elmWorkspace.findProjectForFile(activeFile)
        ?: return ElmReviewRunFailure("Could not determine active Elm project")

    project.elmReviewService.runReviewFromManualAction(elmProject.projectDirPath, elmProject)
    return null
}

class ElmExternalReviewAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private fun showError(project: Project, message: String, includeFixAction: Boolean = false) {
        val actions = if (includeFixAction)
            arrayOf("Fix" to { project.elmWorkspace.showConfigureToolchainUI() })
        else
            emptyArray()
        project.showBalloon(message, NotificationType.ERROR, *actions)
    }

    override fun actionPerformed(e: AnActionEvent) {
        saveAllDocuments()
        val project = e.project ?: return

        val activeFile = findActiveElmFile(project, e.getData(CommonDataKeys.VIRTUAL_FILE))
            ?: return showError(project, "Could not determine active Elm file")

        val failure = runElmReviewOnCurrentFile(project, activeFile)
        if (failure != null) {
            showError(project, failure.message, includeFixAction = failure.includeFixAction)
        }
    }
}
