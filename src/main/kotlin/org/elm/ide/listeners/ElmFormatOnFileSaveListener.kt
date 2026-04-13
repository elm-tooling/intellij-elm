package org.elm.ide.listeners

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.Key
import org.elm.ide.notifications.showBalloon
import org.elm.lang.core.psi.isElmFile
import org.elm.workspace.commandLineTools.ElmFormatCLI
import org.elm.workspace.commandLineTools.ElmFormatCLI.ElmFormatResult
import org.elm.workspace.elmReviewService
import org.elm.workspace.elmSettings
import org.elm.workspace.elmToolchain
import org.elm.workspace.elmWorkspace
import java.util.concurrent.ConcurrentHashMap


class ElmFormatOnFileSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!vFile.isElmFile) return
        val seenReviewProjectRoots = mutableSetOf<java.nio.file.Path>()
        for (project in ProjectLocator.getInstance().getProjectsForFile(vFile).filterNotNull()) {
            if (project.elmSettings.toolchain.isElmFormatOnSaveEnabled) {
                val elmVersion = ElmFormatCLI.getElmVersion(project, vFile)
                val elmFormat = project.elmToolchain.elmFormatCLI
                if (elmFormat == null) {
                    project.showMissingToolWarningOnce(
                        key = "elm-format",
                        message = "elm-format on save is enabled, but elm-format is not configured",
                        actions = arrayOf("Configure" to { project.elmWorkspace.showConfigureToolchainUI() })
                    )
                    continue
                }
                if (elmVersion == null) {
                    project.showMissingToolWarningOnce(
                        key = "elm-format-version",
                        message = "Could not determine Elm version; skipping elm-format on save for this file"
                    )
                    continue
                }

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

            if (project.elmSettings.toolchain.isElmReviewOnTheFlyEnabled) {
                val elmProject = project.elmWorkspace.findProjectForFile(vFile) ?: continue
                if (seenReviewProjectRoots.add(elmProject.projectDirPath)) {
                    val projectPath = elmProject.projectDirPath
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            project.elmReviewService.runReview(projectPath, elmProject)
                        }
                    }
                }
            }
        }
    }
}

private val warnedMissingToolsKey = Key.create<MutableSet<String>>("org.elm.ide.listeners.missingToolsWarned")

private fun com.intellij.openapi.project.Project.showMissingToolWarningOnce(
    key: String,
    message: String,
    actions: Array<Pair<String, () -> Unit>> = emptyArray()
) {
    val shown = getUserData(warnedMissingToolsKey)
        ?: ConcurrentHashMap.newKeySet<String>().also { putUserData(warnedMissingToolsKey, it) }
    if (shown.add(key)) {
        showBalloon(message, NotificationType.ERROR, *actions)
    }
}
