package org.elm.workspace.commandLineTools

import com.intellij.openapi.util.SystemInfo
import org.elm.workspace.elmCompilerTool
import org.elm.workspace.elmWrapCompilerTool
import org.elm.workspace.lamderaCompilerTool
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal fun augmentPathForNodeBackedTool(
    env: MutableMap<String, String>,
    executablePath: Path,
    compilerPath: Path?,
    suggestedTools: Map<String, Path?>
) {
    val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    val existing = env[pathKey]
        .takeUnless { it.isNullOrBlank() }
        ?: System.getenv(pathKey).orEmpty().ifBlank { System.getenv("PATH").orEmpty() }
    val separator = File.pathSeparator
    val extraDirs = linkedSetOf<String>()

    extraDirs += "/opt/homebrew/bin"
    executablePath.parent?.toString()?.let(extraDirs::add)
    compilerPath?.parent?.toString()?.let(extraDirs::add)
    suggestedTools[elmCompilerTool]?.parent?.toString()?.let(extraDirs::add)
    suggestedTools[lamderaCompilerTool]?.parent?.toString()?.let(extraDirs::add)
    suggestedTools[elmWrapCompilerTool]?.parent?.toString()?.let(extraDirs::add)
    suggestedTools["node"]?.parent?.toString()?.let(extraDirs::add)
    findNodeExecutable(existing)?.parent?.toString()?.let(extraDirs::add)

    val prefix = extraDirs.filter { it.isNotBlank() }.joinToString(separator)
    env[pathKey] = if (existing.isBlank()) prefix else "$prefix$separator$existing"
}

private fun findNodeExecutable(existingPath: String): Path? {
    val names = if (SystemInfo.isWindows) listOf("node.exe", "node.cmd", "node") else listOf("node")

    fun search(directories: Sequence<Path>): Path? =
        directories
            .flatMap { dir -> names.asSequence().map { name -> dir.resolve(name) } }
            .firstOrNull { Files.isExecutable(it) }

    fun dirsFromPath(pathValue: String): Sequence<Path> =
        pathValue
            .splitToSequence(File.pathSeparator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Paths.get(it) }
            .filter { Files.isDirectory(it) }

    search(dirsFromPath(existingPath))?.let { return it }
    search(dirsFromPath(System.getenv("PATH").orEmpty()))?.let { return it }

    if (!SystemInfo.isWindows) {
        return search(
            sequenceOf(
                Paths.get("/opt/homebrew/bin"),
                Paths.get("/usr/local/bin"),
                Paths.get("/usr/bin")
            )
        )
    }

    val windowsCandidates = linkedSetOf<Path>()
    System.getenv("NVM_SYMLINK")?.takeIf { it.isNotBlank() }?.let { windowsCandidates.add(Paths.get(it)) }
    System.getenv("ProgramFiles")?.takeIf { it.isNotBlank() }?.let { windowsCandidates.add(Paths.get(it).resolve("nodejs")) }
    System.getenv("ProgramFiles(x86)")?.takeIf { it.isNotBlank() }?.let { windowsCandidates.add(Paths.get(it).resolve("nodejs")) }
    System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { windowsCandidates.add(Paths.get(it).resolve("Programs").resolve("nodejs")) }
    System.getenv("NVM_HOME")?.takeIf { it.isNotBlank() }?.let { nvmHome ->
        val home = Paths.get(nvmHome)
        windowsCandidates.add(home)
        runCatching {
            Files.list(home).use { children ->
                children
                    .filter { Files.isDirectory(it) }
                    .forEach { windowsCandidates.add(it) }
            }
        }
    }

    return search(windowsCandidates.asSequence())
}
