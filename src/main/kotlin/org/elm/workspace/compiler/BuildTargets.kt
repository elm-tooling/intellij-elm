package org.elm.workspace.compiler

import com.intellij.openapi.util.SystemInfo
import org.elm.workspace.ElmProject
import java.nio.file.Path
import java.nio.file.Paths

enum class ElmCompilerKind {
    ELM,
    LAMDERA,
    WRAP;

    override fun toString(): String =
        when (this) {
            ELM -> "Elm"
            LAMDERA -> "Lamdera"
            WRAP -> "Wrap"
        }
}

enum class ElmBuildMode {
    NONE,
    DEBUG,
    OPTIMIZE;

    override fun toString(): String =
        when (this) {
            NONE -> "Default"
            DEBUG -> "Debug"
            OPTIMIZE -> "Optimize"
        }

    fun asFlag(): String? =
        when (this) {
            NONE -> null
            DEBUG -> "--debug"
            OPTIMIZE -> "--optimize"
        }
}

data class ElmBuildTargetConfig(
    val name: String = "",
    val inputPath: String = "",
    val outputPath: String = "",
    val mode: ElmBuildMode = ElmBuildMode.NONE,
    val compilerKind: ElmCompilerKind = ElmCompilerKind.ELM,
    val compilerPath: String = "",
    val compileOnSave: Boolean = false
)


data class ResolvedBuildTarget(
    val name: String,
    val inputPath: Path,
    val inputPathForCompiler: String,
    val outputPathForCompiler: String,
    val mode: ElmBuildMode,
    val compilerKind: ElmCompilerKind,
    val compilerPath: Path,
    val compileOnSave: Boolean,
    val offset: Int = 0
)

/**
 * The result of resolving a single configured build target. Exactly one of [resolved] / [error]
 * is non-null: [resolved] holds a runnable target, [error] holds a human-readable reason the
 * target could not be resolved. Keeping targets separate (rather than collapsing everything to
 * one error) lets the UI list valid and invalid targets side by side and surface why a target
 * failed instead of silently dropping it.
 *
 * [elmProject] is the Elm project (elm.json) that owns the target's input file, derived from the
 * file itself rather than chosen by the user. It is null when no attached Elm project claims the
 * file (which is also surfaced as an [error]); when non-null its directory is the working
 * directory for `elm make`.
 */
data class BuildTargetOutcome(
    val row: Int,
    val config: ElmBuildTargetConfig,
    val elmProject: ElmProject?,
    val resolved: ResolvedBuildTarget?,
    val error: String?
)

fun nullOutputTargetPathString(): String =
    if (SystemInfo.isWindows) "NUL" else "/dev/null"

fun String.toPathOrNull(): Path? =
    runCatching { Paths.get(this) }.getOrNull()
