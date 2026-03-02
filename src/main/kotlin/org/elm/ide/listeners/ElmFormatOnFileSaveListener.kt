package org.elm.ide.listeners

import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VfsUtil
import org.elm.ide.notifications.showBalloon
import org.elm.lang.core.psi.isElmFile
import org.elm.workspace.commandLineTools.ElmFormatCLI
import org.elm.workspace.commandLineTools.ElmFormatCLI.ElmFormatResult
import org.elm.workspace.commandLineTools.makeProject
import org.elm.workspace.compiler.findEntrypoints
import org.elm.workspace.elmReviewService
import org.elm.workspace.elmSettings
import org.elm.workspace.elmToolchain
import org.elm.workspace.elmWorkspace


class ElmFormatOnFileSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!vFile.isElmFile) return
        val seenReviewProjectRoots = mutableSetOf<java.nio.file.Path>()
        val seenBuildProjectRoots = mutableSetOf<java.nio.file.Path>()
        for (project in ProjectLocator.getInstance().getProjectsForFile(vFile).filterNotNull()) {
            if (project.elmSettings.toolchain.isElmFormatOnSaveEnabled) {
                val elmVersion = ElmFormatCLI.getElmVersion(project, vFile)
                val elmFormat = project.elmToolchain.elmFormatCLI
                if (elmVersion != null && elmFormat != null) {
                    val result = elmFormat.formatDocumentAndSetText(project, document, elmVersion, addToUndoStack = false)
                    when (result) {
                        is ElmFormatResult.BadSyntax ->
                            project.showBalloon(result.msg, NotificationType.WARNING)

                        is ElmFormatResult.FailedToStart ->
                            project.showBalloon(
                                result.msg,
                                NotificationType.ERROR,
                                "Configure" to { project.elmWorkspace.showConfigureToolchainUI() }
                            )

                        is ElmFormatResult.UnknownFailure ->
                            project.showBalloon(result.msg, NotificationType.ERROR)

                        is ElmFormatResult.Success -> Unit
                    }
                }
            }

            if (project.elmSettings.toolchain.isElmReviewOnTheFlyEnabled) {
                val elmProject = project.elmWorkspace.findProjectForFile(vFile) ?: continue
                if (seenReviewProjectRoots.add(elmProject.projectDirPath)) {
                    project.elmReviewService.runReview(elmProject.projectDirPath)
                }
            }

            if (project.elmSettings.toolchain.isElmBuildOnSaveEnabled) {
                val elmProject = project.elmWorkspace.findProjectForFile(vFile) ?: continue
                if (seenBuildProjectRoots.add(elmProject.projectDirPath)) {
                    val projectDir = VfsUtil.findFile(elmProject.projectDirPath, true) ?: continue
                    val entryPoints = findEntrypoints(elmProject, project, projectDir, vFile)
                    makeProject(elmProject, project, entryPoints, vFile)
                }
            }
        }
    }
}
