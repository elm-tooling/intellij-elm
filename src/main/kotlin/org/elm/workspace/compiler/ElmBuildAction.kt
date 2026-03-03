package org.elm.workspace.compiler

import com.intellij.execution.ExecutionException
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import org.elm.ide.notifications.showBalloon
import org.elm.lang.core.ElmFileType
import org.elm.lang.core.lookup.ClientLocation
import org.elm.lang.core.psi.ElmFile
import org.elm.openapiext.saveAllDocuments
import org.elm.workspace.*
import org.elm.workspace.commandLineTools.makeProject
import java.nio.file.Path

const val ELM_BUILD_ACTION_ID = "Elm.Build"

class ElmBuildAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        saveAllDocuments()
        val project = e.project ?: return

        val activeFile = findActiveFile(e, project)
            ?: return showError(project, "Could not determine active Elm file")

        if (ElmFile.fromVirtualFile(activeFile, project)?.isInTestsDirectory == true)
            return showError(project, "To check tests for compile errors, use the elm-test run configuration instead.")

        val elmProject = project.elmWorkspace.findProjectForFile(activeFile)
            ?: return showError(project, "Could not determine active Elm project")
        val entryPoints = when (val result = project.elmWorkspace.resolveBuildTargets(elmProject)) {
            is org.elm.openapiext.Result.Ok -> result.value
            is org.elm.openapiext.Result.Err -> {
                val suffix = if (result.reason.isBlank()) "" else "\n${result.reason}"
                return showError(project, "Invalid build target configuration.$suffix", includeFixAction = true)
            }
        }

        try {
            val currentFileInEditor: VirtualFile? = e.getData(PlatformDataKeys.VIRTUAL_FILE)
            makeProject(elmProject, project, entryPoints, currentFileInEditor)
        } catch (e: ExecutionException) {
            return showError(
                project,
                "Failed to 'make'. Are the path settings correct ?",
                includeFixAction = true
            )
        }
    }

    private fun findActiveFile(e: AnActionEvent, project: Project): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull { it.fileType == ElmFileType }

interface ElmErrorsListener {
    @Suppress("unused")
    fun update(baseDirPath: Path, messages: List<ElmError>, targetPath: String, offset: Int)
}

interface ElmCompilerOutputListener {
    @Suppress("unused")
    fun update(toolName: String, commandLine: String, stdout: String, stderr: String, exitCode: Int)
}

    data class LookupClientLocation(
        override val intellijProject: Project,
        override val elmProject: ElmProject?,
        override val isInTestsDirectory: Boolean = false
    ) : ClientLocation
}

private fun showError(project: Project, message: String, includeFixAction: Boolean = false) {
    val actions = if (includeFixAction)
        arrayOf("Fix" to { project.elmWorkspace.showConfigureToolchainUI() })
    else
        emptyArray()
    project.showBalloon(message, NotificationType.ERROR, *actions)
}

val ERRORS_TOPIC = Topic("Elm compiler-messages", ElmBuildAction.ElmErrorsListener::class.java)
val COMPILER_OUTPUT_TOPIC = Topic("Elm compiler output", ElmBuildAction.ElmCompilerOutputListener::class.java)
