package org.elm.ide.notifications

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import org.elm.lang.core.psi.isElmFile
import org.elm.workspace.*
import kotlin.io.path.exists
import java.util.function.Function

private val log = logger<ElmNeedsConfigNotificationProvider>()

/**
 * Presents actionable notifications at the top of an Elm file whenever the Elm plugin
 * needs configuration (e.g. the path to the Elm compiler).
 */
class ElmNeedsConfigNotificationProvider(
    private val project: Project
) : EditorNotificationProvider {

    private val notifications = EditorNotifications.getInstance(project)

    init {
        project.messageBus.connect(project.elmWorkspace).apply {
            subscribe(ElmWorkspaceService.WORKSPACE_TOPIC,
                object : ElmWorkspaceService.ElmWorkspaceListener {
                    override fun didUpdate() {
                        log.debug("Workspace did change; refreshing UI")
                        notifications.updateAllNotifications()
                    }
                })
        }
    }

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in com.intellij.openapi.fileEditor.FileEditor, out javax.swing.JComponent?> {
        val panel = createNotificationPanel(file)
        return if (panel == null) Function { null } else Function { panel }
    }

    private fun createNotificationPanel(file: VirtualFile): EditorNotificationPanel? {
        if (!file.isElmFile)
            return null

        val workspace = project.elmWorkspace
        if (!workspace.hasAtLeastOneValidProject()) {
            return noElmProjectPanel("No Elm projects found")
        }

        val elmProject = project.elmWorkspace.findProjectForFile(file)
            ?: return noElmProjectPanel("Could not find Elm project for this file")

        val toolchain = project.elmToolchain
        if (!toolchain.looksLikeValidToolchain()) {
            return badToolchainPanel("Elm toolchain compiler is not configured or executable")
        }

        if (toolchain.isElmFormatOnSaveEnabled && toolchain.elmFormatCLI == null) {
            return badToolchainPanel("elm-format on save is enabled, but elm-format is not configured")
        }

        val hasElmReviewConfig = elmProject.projectDirPath.resolve("review").exists()
        if (toolchain.isElmReviewOnTheFlyEnabled && hasElmReviewConfig) {
            if (toolchain.elmReviewPath == null) {
                return badToolchainPanel("elm-review on save is enabled, but elm-review is not configured")
            }
        }

        return null
    }

    private fun badToolchainPanel(message: String) =
        EditorNotificationPanel().apply {
            text = message
            createActionLabel("Setup toolchain") {
                project.elmWorkspace.showConfigureToolchainUI()
            }
        }


    private fun noElmProjectPanel(message: String) =
        EditorNotificationPanel().apply {
            text = message
            createActionLabel("Attach elm.json", "Elm.AttachElmProject")
        }

}
