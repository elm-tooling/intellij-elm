package org.elm.workspace.elmreview

import org.elm.workspace.elmCompilerTool
import org.elm.workspace.lamderaCompilerTool
import org.elm.workspace.compiler.ElmBuildTargetConfig
import org.elm.workspace.compiler.toPathOrNull
import org.elm.workspace.elmWrapCompilerTool
import java.nio.file.Files
import java.nio.file.Path

enum class ElmReviewCompilerSource {
    GLOBAL,
    BUILD_TARGET,
    DISCOVERED,
    NONE
}

data class ElmReviewCompilerResolution(
    val source: ElmReviewCompilerSource,
    val path: Path?,
    val buildTargetName: String? = null,
    val discoveredToolName: String? = null
) {
    fun asDisplayText(): String =
        when (source) {
            ElmReviewCompilerSource.GLOBAL -> "Global: ${path ?: "<none>"}"
            ElmReviewCompilerSource.BUILD_TARGET -> {
                val prefix = if (buildTargetName.isNullOrBlank()) "Build target" else "Build target '$buildTargetName'"
                "$prefix: ${path ?: "<none>"}"
            }
            ElmReviewCompilerSource.DISCOVERED -> "Discovered (${discoveredToolName.orEmpty()}): ${path ?: "<none>"}"
            ElmReviewCompilerSource.NONE -> "None"
        }
}

fun resolveElmReviewCompiler(
    projectBasePath: Path,
    toolchainCompilerPath: Path?,
    buildTargets: List<ElmBuildTargetConfig>,
    suggestedTools: Map<String, Path?>
): ElmReviewCompilerResolution {
    if (toolchainCompilerPath != null && Files.isExecutable(toolchainCompilerPath)) {
        return ElmReviewCompilerResolution(
            source = ElmReviewCompilerSource.GLOBAL,
            path = toolchainCompilerPath
        )
    }

    buildTargets
        .asSequence()
        .mapNotNull { target ->
            val raw = target.compilerPath.trim()
            if (raw.isBlank()) return@mapNotNull null
            val parsed = raw.toPathOrNull() ?: return@mapNotNull null
            val candidate = when {
                parsed.isAbsolute -> parsed
                else -> projectBasePath.resolve(parsed).normalize()
            }
            if (!Files.isExecutable(candidate)) return@mapNotNull null
            candidate to target
        }
        .firstOrNull()
        ?.let { (path, target) ->
            return ElmReviewCompilerResolution(
                source = ElmReviewCompilerSource.BUILD_TARGET,
                path = path,
                buildTargetName = target.name.ifBlank { null }
            )
        }

    sequenceOf(elmCompilerTool, lamderaCompilerTool, elmWrapCompilerTool)
        .mapNotNull { toolName ->
            val path = suggestedTools[toolName] ?: return@mapNotNull null
            if (!Files.isExecutable(path)) return@mapNotNull null
            toolName to path
        }
        .firstOrNull()
        ?.let { (toolName, path) ->
            return ElmReviewCompilerResolution(
                source = ElmReviewCompilerSource.DISCOVERED,
                path = path,
                discoveredToolName = toolName
            )
        }

    return ElmReviewCompilerResolution(
        source = ElmReviewCompilerSource.NONE,
        path = null
    )
}
