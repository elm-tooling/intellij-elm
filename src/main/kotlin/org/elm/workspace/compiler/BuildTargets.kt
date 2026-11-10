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

enum class ElmBuildTargetType {
    /** Compile a single entry `.elm` file to an output (the usual case). */
    APPLICATION,

    /** Type-check a package by running `elm make` with no arguments in the package's directory. */
    PACKAGE,

    /**
     * Type-check a project's tests by running `elm-test make` in the project's directory. These
     * targets are generated automatically (one per Elm project that has a tests directory), not
     * configured by the user, and are executed via [org.elm.workspace.commandLineTools.ElmTestCLI]
     * rather than the compiler CLIs.
     */
    TEST;

    override fun toString(): String =
        when (this) {
            APPLICATION -> "Application"
            PACKAGE -> "Package"
            TEST -> "Test"
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

/**
 * A configured build target.
 *
 * For [ElmBuildTargetType.APPLICATION], [inputPath] is the absolute path to the entry `.elm` file
 * and [outputPath]/[mode] apply. For [ElmBuildTargetType.PACKAGE], [inputPath] is the absolute
 * path to the package's `elm.json` and [outputPath]/[mode] are unused (the package is type-checked
 * by running `elm make` with no arguments).
 */
data class ElmBuildTargetConfig(
    val name: String = "",
    val type: ElmBuildTargetType = ElmBuildTargetType.APPLICATION,
    val inputPath: String = "",
    val outputPath: String = "",
    val mode: ElmBuildMode = ElmBuildMode.NONE,
    val compilerKind: ElmCompilerKind = ElmCompilerKind.ELM,
    val compilerPath: String = "",
    val compileOnSave: Boolean = false
)


data class ResolvedBuildTarget(
    val name: String,
    val type: ElmBuildTargetType,
    /** The directory `elm make` runs in (the directory containing the target's `elm.json`). */
    val workDir: Path,
    val inputPath: Path,
    val inputPathForCompiler: String,
    val outputPathForCompiler: String,
    val mode: ElmBuildMode,
    val compilerKind: ElmCompilerKind,
    val compilerPath: Path,
    val compileOnSave: Boolean,
    val offset: Int = 0,
    /**
     * For [ElmBuildTargetType.TEST], the absolute path to the `elm-test` executable used to run
     * the build. Null for compiler targets, which are run via their [compilerKind]'s CLI instead.
     */
    val testExecutablePath: Path? = null,
    /**
     * For [ElmBuildTargetType.TEST] projects that keep their tests in a non-default directory, the
     * relative directory to pass to `elm-test` (mirroring how the test runner supplies it). Null
     * when tests live in the default `tests` directory (or for non-test targets).
     */
    val testsCustomDir: String? = null
) {
    /** The arguments to pass to `elm make` (after the compiler executable) for this target. */
    fun makeParameters(): List<String> {
        // Test targets are executed via ElmTestCLI (which builds its own `elm-test make`
        // command line), not through the compiler CLIs that call this.
        check(type != ElmBuildTargetType.TEST) {
            "Test targets are executed via ElmTestCLI, not makeParameters()"
        }
        val params = mutableListOf("make")
        // A package is type-checked by running `elm make` with no input/output/mode.
        if (type == ElmBuildTargetType.PACKAGE) return params
        if (inputPathForCompiler.isNotBlank()) params += inputPathForCompiler
        params += "--output=$outputPathForCompiler"
        mode.asFlag()?.let { params += it }
        return params
    }
}

/**
 * The result of resolving a single configured build target. Exactly one of [resolved] / [error]
 * is non-null: [resolved] holds a runnable target, [error] holds a human-readable reason the
 * target could not be resolved. Keeping targets separate (rather than collapsing everything to
 * one error) lets the UI list valid and invalid targets side by side and surface why a target
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
