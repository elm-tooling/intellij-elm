package org.elm.ide.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.elm.ide.notifications.showBalloon
import org.elm.openapiext.saveAllDocuments
import org.elm.workspace.commandLineTools.makeProject
import org.elm.workspace.compiler.ResolvedBuildTarget
import org.elm.workspace.elmWorkspace
import java.nio.file.Files

/**
 * Build the target currently selected in the Elm Compiler tool window's build-targets list,
 * falling back to the first target when nothing has been selected yet.
 *
 * Available from the command palette ("Find Action"). It has no default keyboard shortcut,
 * but the user can assign one via Settings | Keymap.
 */
class ElmBuildSelectedAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Open the tool window (unless already open) so the selection and any errors are visible.
        openElmCompilerToolWindow(project)

        val targets = resolveAllBuildTargets(project)
        if (targets.isEmpty()) return // Nothing to build; just leave the panel open.

        val selectedKey = project.elmBuildTargetSelection.selectedKey
        val target = targets.firstOrNull { buildTargetKeyOf(it) == selectedKey }
            ?: targets.first()
        // Keep the selection in sync so the panel reflects what we built.
        project.elmBuildTargetSelection.selectedKey = buildTargetKeyOf(target)
        buildTarget(project, target)
    }
}

/**
 * Identifies a resolved build target well enough to re-find it after the targets are
 * re-resolved (e.g. when the tool window refreshes). Deliberately does not include the
 * ordinal row index, which can shift as targets are added or removed.
 */
data class BuildTargetKey(
    val workDir: String,
    val name: String,
    val inputPathForCompiler: String,
    val outputPathForCompiler: String
)

fun buildTargetKeyOf(target: ResolvedBuildTarget): BuildTargetKey =
    BuildTargetKey(
        workDir = target.workDir.toString(),
        name = target.name,
        inputPathForCompiler = target.inputPathForCompiler,
        outputPathForCompiler = target.outputPathForCompiler
    )

/**
 * Remembers which build target is selected in the Elm Compiler tool window, so the
 * "Build Selected Elm Target" command can build it even when triggered from outside the
 * tool window (e.g. via a keyboard shortcut while editing).
 */
@Service(Service.Level.PROJECT)
class ElmBuildTargetSelectionService {
    var selectedKey: BuildTargetKey? = null
}

val Project.elmBuildTargetSelection: ElmBuildTargetSelectionService
    get() = service()

/** All successfully resolved build targets, in the tool window's display order. Targets that
 * fail to resolve are omitted. */
fun resolveAllBuildTargets(project: Project): List<ResolvedBuildTarget> =
    project.elmWorkspace.resolveBuildTargetsDetailed().mapNotNull { it.resolved }

fun openElmCompilerToolWindow(project: Project) {
    ToolWindowManager.getInstance(project).getToolWindow(ELM_COMPILER_TOOL_WINDOW_ID)?.let { toolWindow ->
        if (!toolWindow.isVisible) toolWindow.show(null)
    }
}

/**
 * Save all documents and build a single target, reporting a balloon if its input file has
 * gone missing. Must be called on the EDT; the compilation itself runs on a pooled thread.
 */
fun buildTarget(project: Project, target: ResolvedBuildTarget) {
    saveAllDocuments()
    val currentFileInEditor = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
    ApplicationManager.getApplication().executeOnPooledThread {
        if (!Files.exists(target.inputPath)) {
            ApplicationManager.getApplication().invokeLater {
                project.showBalloon(
                    "Cannot build target '${target.displayName()}': input file not found.",
                    NotificationType.ERROR
                )
            }
            return@executeOnPooledThread
        }
        makeProject(project, listOf(target), currentFileInEditor)
    }
}

private fun ResolvedBuildTarget.displayName(): String =
    name.ifBlank { inputPathForCompiler.ifBlank { "the selected target" } }
