package org.elm.workspace.compiler

import com.intellij.util.messages.Topic
import java.nio.file.Path

/**
 * Message-bus contract for the Elm compiler tool window. The CLI wrappers publish compiler
 * results here (see [ERRORS_TOPIC] / [COMPILER_OUTPUT_TOPIC]) and the tool window subscribes.
 */
interface ElmErrorsListener {
    fun update(baseDirPath: Path, messages: List<ElmError>, targetPath: String, offset: Int)
}

/** The console output of a single compiler invocation (one `elm make` / `lamdera make` / `elm-test make`). */
data class ElmCompilerOutput(
    val toolName: String,
    val commandLine: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

interface ElmCompilerOutputListener {
    /**
     * The output of one build. A single-target build posts one entry per command; "Build all"
     * posts every command's output across all targets so the tool window shows them all.
     */
    fun update(outputs: List<ElmCompilerOutput>)
}

val ERRORS_TOPIC = Topic("Elm compiler-messages", ElmErrorsListener::class.java)
val COMPILER_OUTPUT_TOPIC = Topic("Elm compiler output", ElmCompilerOutputListener::class.java)
