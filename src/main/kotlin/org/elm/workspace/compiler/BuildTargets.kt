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

fun nullOutputTargetPathString(): String =
    if (SystemInfo.isWindows) "NUL" else "/dev/null"

fun String.toPathOrNull(): Path? =
    runCatching { Paths.get(this) }.getOrNull()
