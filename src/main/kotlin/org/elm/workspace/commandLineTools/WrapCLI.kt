package org.elm.workspace.commandLineTools

import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.openapiext.GeneralCommandLine
import org.elm.openapiext.Result
import org.elm.openapiext.execute
import org.elm.workspace.ElmProject
import org.elm.workspace.ParseException
import org.elm.workspace.Version
import org.elm.workspace.compiler.ResolvedBuildTarget
import org.elm.workspace.elmCompilerTool
import java.nio.file.Path

/**
 * Interact with external `wrap` process.
 */
class WrapCLI(private val wrapExecutablePath: Path) {

    fun make(
        project: Project,
        workDir: Path,
        elmProject: ElmProject?,
        entryPoints: List<ResolvedBuildTarget>,
        jsonReport: Boolean = false,
        currentFile: VirtualFile? = null
    ): Boolean =
        ElmCLI(wrapExecutablePath).make(project, workDir, elmProject, entryPoints, jsonReport, currentFile)

    fun queryVersion(project: Project): Result<Version> {
        val firstLine = try {
            GeneralCommandLine(wrapExecutablePath)
                .withParameters("-V")
                .execute(elmCompilerTool, project)
                .stdoutLines
                .firstOrNull()
        } catch (e: ExecutionException) {
            return Result.Err("failed to run wrap: ${e.message}")
        } ?: return Result.Err("no output from wrap")

        val versionString = VERSION_REGEX.find(firstLine)?.value ?: firstLine
        return try {
            Result.Ok(Version.parse(versionString))
        } catch (e: ParseException) {
            Result.Err("could not parse Elm Wrap version: ${e.message}")
        }
    }

    companion object {
        private val VERSION_REGEX = Regex("""\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.\-]+)?""")
    }
}
