package org.elm.workspace.compiler

import com.intellij.openapi.util.SystemInfo
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

data class ElmProjectBuildTargetConfig(
    val manifestPath: String,
    val targets: List<ElmBuildTargetConfig> = emptyList()
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
 * target could not be resolved. Keeping targets separate (rather than collapsing a whole project
 * to one error) lets the UI list valid and invalid targets side by side and surface why a target
 * failed instead of silently dropping it.
 */
data class BuildTargetOutcome(
    val row: Int,
    val config: ElmBuildTargetConfig,
    val resolved: ResolvedBuildTarget?,
    val error: String?
)

fun nullOutputTargetPathString(): String =
    if (SystemInfo.isWindows) "NUL" else "/dev/null"

fun String.toPathOrNull(): Path? =
    runCatching { Paths.get(this) }.getOrNull()
