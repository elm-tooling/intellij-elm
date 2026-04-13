package org.elm.workspace.commandLineTools

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.workspace.ElmProject
import org.elm.workspace.compiler.ElmCompilerKind
import org.elm.workspace.compiler.ResolvedBuildTarget

fun makeProject(
    elmProject: ElmProject,
    project: Project,
    entryPoints: List<ResolvedBuildTarget>,
    currentFileInEditor: VirtualFile?
): Boolean {
    if (entryPoints.isEmpty()) return true

    var allSucceeded = true
    val grouped = entryPoints.groupBy { it.compilerKind to it.compilerPath }
    for ((kindAndPath, targets) in grouped) {
        val (kind, path) = kindAndPath
        val succeeded = when (kind) {
            ElmCompilerKind.ELM ->
                ElmCLI(path).make(
                    project,
                    elmProject.projectDirPath,
                    elmProject,
                    targets,
                    jsonReport = true,
                    currentFile = currentFileInEditor
                )
            ElmCompilerKind.LAMDERA ->
                LamderaCLI(path).make(
                    project,
                    elmProject.projectDirPath,
                    elmProject,
                    targets,
                    jsonReport = true,
                    currentFile = currentFileInEditor
                )
            ElmCompilerKind.WRAP ->
                WrapCLI(path).make(
                    project,
                    elmProject.projectDirPath,
                    elmProject,
                    targets,
                    jsonReport = true,
                    currentFile = currentFileInEditor
                )
        }
        allSucceeded = allSucceeded && succeeded
    }
    return allSucceeded
}
