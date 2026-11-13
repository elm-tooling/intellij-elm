package org.elm.workspace.commandLineTools

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import org.elm.openapiext.*
import org.elm.workspace.*
import java.nio.file.Path


/**
 * Interact with external `elm-review` process.
 */
class ElmReviewCLI(private val elmReviewExecutablePath: Path) {

    fun queryVersion(project: Project): Result<Version> {
        val firstLine = try {
            val arguments: List<String> = listOf("--version")
            GeneralCommandLine(elmReviewExecutablePath)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .apply {
                    augmentPathForNodeBackedTool(
                        env = environment,
                        executablePath = elmReviewExecutablePath,
                        compilerPath = null,
                        suggestedTools = ElmSuggest.suggestTools(project)
                    )
                }
                .withParameters(arguments)
                .execute(elmReviewTool, project)
                .stdoutLines
                .firstOrNull()
        } catch (e: ExecutionException) {
            return Result.Err("failed to run elm-review: ${e.message}")
        } ?: return Result.Err("no output from elm-review")

        return try {
            Result.Ok(Version.parse(firstLine))
        } catch (e: ParseException) {
            Result.Err("invalid elm-review version: ${e.message}")
        }
    }
}

internal fun buildReviewCommandLine(
    executablePath: Path,
    workDir: Path,
    arguments: List<String>,
    compilerPath: Path?,
    suggestedTools: Map<String, Path?>
): GeneralCommandLine {
    return GeneralCommandLine(executablePath)
        .withWorkDirectory(workDir.toString())
        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        .apply {
            augmentPathForNodeBackedTool(
                env = environment,
                executablePath = executablePath,
                compilerPath = compilerPath,
                suggestedTools = suggestedTools
            )
        }
        .withParameters(arguments)
}
