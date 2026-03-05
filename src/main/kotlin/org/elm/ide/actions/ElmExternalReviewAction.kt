package org.elm.ide.actions

import com.intellij.execution.ExecutionException
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.ide.notifications.showBalloon
import org.elm.lang.core.ElmFileType
import org.elm.openapiext.saveAllDocuments
import org.elm.workspace.commandLineTools.makeProject
import org.elm.workspace.elmreview.resolveElmReviewCompiler
import org.elm.workspace.elmToolchain
import org.elm.workspace.elmWorkspace

internal data class ElmReviewRunFailure(val message: String, val includeFixAction: Boolean = false)

internal fun findActiveElmFile(project: Project, preferredFile: VirtualFile? = null): VirtualFile? =
    preferredFile?.takeIf { it.fileType == ElmFileType }
        ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull { it.fileType == ElmFileType }

internal fun runElmReviewOnCurrentFile(
    project: Project,
    activeFile: VirtualFile,
    currentFileInEditor: VirtualFile? = activeFile
): ElmReviewRunFailure? {
    if (activeFile.canonicalPath?.contains("/review") == true) return null

    val elmReviewCLI = project.elmToolchain.elmReviewCLI
        ?: return ElmReviewRunFailure("Please set the path to the 'elm-review' binary", includeFixAction = true)

    val elmProject = project.elmWorkspace.findProjectForFile(activeFile)
        ?: return ElmReviewRunFailure("Could not determine active Elm project")

    return try {
        when (val result = project.elmWorkspace.resolveBuildTargets(elmProject)) {
            is org.elm.openapiext.Result.Ok -> {
                val entryPoints = result.value
                val compiledSuccessfully = makeProject(elmProject, project, entryPoints, currentFileInEditor)
                if (!compiledSuccessfully) return null
            }
            is org.elm.openapiext.Result.Err -> {
                // Keep review action working even when build targets are absent/invalid.
                // Build targets are optional for running elm-review on the current file.
            }
        }
        val reviewCompilerPath = resolveElmReviewCompiler(
            project = project,
            projectBasePath = elmProject.projectDirPath,
            elmProjectHint = elmProject
        ).path
        elmReviewCLI.runReview(project, elmProject, reviewCompilerPath, currentFileInEditor)
        null
    } catch (_: ExecutionException) {
        ElmReviewRunFailure(
            "Failed to 'make' or 'review'. Are the path settings correct ?",
            includeFixAction = true
        )
    }
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

        val failure = runElmReviewOnCurrentFile(project, activeFile, e.getData(PlatformDataKeys.VIRTUAL_FILE))
        if (failure != null) {
            showError(project, failure.message, includeFixAction = failure.includeFixAction)
        }
    }
}
