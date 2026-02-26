package org.elm.workspace

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.SystemInfo
import kotlin.io.path.isDirectory
import org.elm.openapiext.modules
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap


/**
 * Provides suggestions about where Elm tools may be installed
 */
object ElmSuggest {
    private const val SUGGESTION_CACHE_TTL_MS = 10_000L
    private const val NPM_SEARCH_MAX_DEPTH = 6
    private val suggestionsCache = ConcurrentHashMap<String, CachedSuggestions>()

    private data class CachedSuggestions(
        val timestampMs: Long,
        val suggestions: Map<String, Path?>
    )

    /**
     * Suggest paths to common Elm tools. This performs file I/O
     * in order to determine that the file exists and that it is executable.
     */
    fun suggestTools(project: Project): Map<String, Path?> {
        val cacheKey = project.basePath ?: project.name
        val now = System.currentTimeMillis()
        val cached = suggestionsCache[cacheKey]
        if (cached != null && now - cached.timestampMs <= SUGGESTION_CACHE_TTL_MS) {
            return cached.suggestions
        }

        val binDirs = binDirSuggestions(project).distinct().toList()
        val suggestions = elmTools.associateWith { programPath(it, binDirs.asSequence()) }
        suggestionsCache[cacheKey] = CachedSuggestions(now, suggestions)
        return suggestions
    }

    /**
     * Checks the system's path to verify whether the Elm compiler is present. The search locations are overridable so
     * this can be tested.
     *
     * This performs file I/O in order to determine that the file exists and that it is executable.
     */
    fun compilerIsOnPath(searchLocations: Sequence<Path> = emptySequence()): Boolean {
        val elmNameVariants = executableNamesFor("elm")
        return searchLocations.ifEmpty {
            sequenceOf(
                    suggestionsFromPath(),
                    suggestionsForMac(),
                    suggestionsForWindows(),
                    suggestionsForUnix()
            ).flatten()
        }
                .flatMap { binDir ->
                    elmNameVariants.map { filename ->
                        binDir.resolve(filename)
                    }
                }
                .filter { Files.isExecutable(it) }
                .any()
    }

    /**
     * Attempt to find the path to [programName].
     */
    private fun programPath(programName: String, binDirSuggestions: Sequence<Path>): Path? {
        val programNameVariants = executableNamesFor(programName)
        return binDirSuggestions
                .flatMap { binDir ->
                    programNameVariants.map { filename ->
                        binDir.resolve(filename)
                    }
                }.firstOrNull { Files.isExecutable(it) }
    }

    /**
     * Return one or more variants on program [name] based on OS-specific file extensions.
     */
    fun executableNamesFor(name: String) =
            if (SystemInfo.isWindows) sequenceOf("$name.exe", "$name.cmd", name)
            else sequenceOf(name)

    /**
     * Return a list of directories which may contain Elm binaries.
     */
    private fun binDirSuggestions(project: Project) =
            sequenceOf(
                    suggestionsFromNPM(project),
                    suggestionsFromPath(),
                    suggestionsForMac(),
                    suggestionsForWindows(),
                    suggestionsForUnix(),
                    suggestionsFromNVM()
            ).flatten()


    private fun suggestionsFromNPM(project: Project): Sequence<Path> {
        return project.modules
                .asSequence()
                .flatMap { ModuleRootManager.getInstance(it).contentRoots.asSequence() }
                .flatMap { contentRoot ->
                    val rootPath = Paths.get(contentRoot.path)
                    val bins = linkedSetOf<Path>()

                    val directBin = rootPath.resolve("node_modules").resolve(".bin")
                    if (directBin.isDirectory()) {
                        bins.add(directBin)
                    }

                    runCatching {
                        Files.walk(rootPath, NPM_SEARCH_MAX_DEPTH).use { walk ->
                            walk
                                .filter { Files.isDirectory(it) && it.fileName?.toString() == "node_modules" }
                                .forEach { nodeModulesDir ->
                                    val bin = nodeModulesDir.resolve(".bin")
                                    if (bin.isDirectory()) bins.add(bin)
                                }
                        }
                    }
                    bins.asSequence()
                }
    }

    private fun suggestionsFromNVM(): Sequence<Path> {
        // nvm (Node Version Manager): see https://github.com/intellij-elm/intellij-elm/issues/252
        // nvm is not available on Windows
        if (SystemInfo.isWindows) return emptySequence()
        return sequenceOf(
            Paths.get(
                System.getProperty("user.home"),
                ".config",
                "yarn",
                "global",
                "node_modules",
                "elm",
                "unpacked_bin"
            )
        )
    }

    private fun suggestionsFromPath(): Sequence<Path> {
        return System.getenv("PATH").orEmpty()
                .splitToSequence(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .map { Paths.get(it.trim()) }
                .filter { it.isDirectory() }
    }

    private fun suggestionsForMac(): Sequence<Path> {
        if (!SystemInfo.isMac) return emptySequence()
        return sequenceOf(
            Paths.get("/opt/homebrew/bin"),
            Paths.get("/usr/local/bin")
        )
    }

    private fun suggestionsForUnix(): Sequence<Path> {
        if (!SystemInfo.isUnix) return emptySequence()
        return sequenceOf(Paths.get("/usr/local/bin"))
    }

    private fun suggestionsForWindows(): Sequence<Path> {
        if (!SystemInfo.isWindows) return emptySequence()
        return sequenceOf("0.19.1", "0.19")
                .flatMap {
                    sequenceOf(
                            Paths.get("C:/Program Files (x86)/Elm Platform/$it/bin"), // npm install -g elm
                            Paths.get("C:/Program Files/Elm Platform/$it/bin"),
                            Paths.get("C:/Program Files (x86)/Elm/$it/bin"), // choco install elm-platform
                            Paths.get("C:/Program Files/Elm/$it/bin")
                    )
                }
    }
}
