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

interface ElmCompilerOutputListener {
    fun update(toolName: String, commandLine: String, stdout: String, stderr: String, exitCode: Int)
}

val ERRORS_TOPIC = Topic("Elm compiler-messages", ElmErrorsListener::class.java)
val COMPILER_OUTPUT_TOPIC = Topic("Elm compiler output", ElmCompilerOutputListener::class.java)
