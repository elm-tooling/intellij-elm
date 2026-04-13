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
import org.elm.lang.core.psi.ElmFile
import org.elm.openapiext.saveAllDocuments
import org.elm.workspace.*
import org.elm.workspace.commandLineTools.makeProject
import java.nio.file.Path

const val ELM_BUILD_ACTION_ID = "Elm.Build"

internal data class ElmBuildRunFailure(val message: String, val includeFixAction: Boolean = false)

internal fun runElmBuildForFile(
    project: Project,
    activeFile: VirtualFile,
    currentFileInEditor: VirtualFile? = activeFile
): ElmBuildRunFailure? {
    if (ElmFile.fromVirtualFile(activeFile, project)?.isInTestsDirectory == true) {
        return ElmBuildRunFailure(
            "To check tests for compile errors, use the elm-test run configuration instead."
        )
    }

    val elmProject = project.elmWorkspace.findProjectForFile(activeFile)
        ?: return ElmBuildRunFailure("Could not determine active Elm project")

    val entryPoints = when (val result = project.elmWorkspace.resolveBuildTargets(elmProject)) {
        is org.elm.openapiext.Result.Ok -> result.value
        is org.elm.openapiext.Result.Err -> {
            val suffix = if (result.reason.isBlank()) "" else "\n${result.reason}"
            return ElmBuildRunFailure("Invalid build target configuration.$suffix", includeFixAction = true)
        }
    }

    return try {
        makeProject(elmProject, project, entryPoints, currentFileInEditor)
        null
    } catch (_: ExecutionException) {
        ElmBuildRunFailure(
            "Failed to 'make'. Are the path settings correct?",
            includeFixAction = true
        )
    }
}

class ElmBuildAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        saveAllDocuments()
        val project = e.project ?: return

        val activeFile = findActiveFile(e, project)
            ?: return showError(project, "Could not determine active Elm file")

        if (ElmFile.fromVirtualFile(activeFile, project)?.isInTestsDirectory == true)
            return showError(project, "To check tests for compile errors, use the elm-test run configuration instead.")

        val failure = runElmBuildForFile(
            project = project,
            activeFile = activeFile,
            currentFileInEditor = e.getData(PlatformDataKeys.VIRTUAL_FILE)
        )
        if (failure != null) {
            return showError(project, failure.message, includeFixAction = failure.includeFixAction)
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
