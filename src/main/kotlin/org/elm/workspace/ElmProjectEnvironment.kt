package org.elm.workspace

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import org.elm.openapiext.isSuccess
import org.elm.workspace.compiler.nullOutputTargetPathString
import java.nio.file.Path
import java.nio.file.Paths

/** Environment handling for project attachment only; ordinary local builds are unchanged. */
internal class ElmProjectEnvironment private constructor(private val distribution: WSLDistribution?) {
    val nullOutput: String
        get() = if (distribution != null) "/dev/null" else nullOutputTargetPathString()

    /** Null preserves the repository's existing local ELM_HOME/platform-default lookup. */
    fun packageCacheHome(): Path? {
        val wsl = distribution ?: return null
        // getEnvironmentVariable is internal API; use the public login-shell environment map.
        val environment = wsl.environment
            ?: throw ProjectLoadException("Could not read the WSL user's environment")
        val home = environment["HOME"]?.takeIf { it.isNotBlank() }
            ?: throw ProjectLoadException("Could not determine the WSL user's home directory")
        val elmHome = resolveWslElmHome(environment["ELM_HOME"], home)
        return Paths.get(wsl.getWindowsPath(elmHome))
    }

    fun <T> withTempDirectory(action: (Path) -> T): T {
        val dir = createTempDirectory()
        try {
            return action(dir)
        } finally {
            FileUtil.delete(dir.toFile())
        }
    }

    private fun createTempDirectory(): Path {
        val wsl = distribution ?: return FileUtil.createTempDirectory("elm_deps_hack", null).toPath()
        // Create it in the distribution, not in the Windows host's java.io.tmpdir.
        val output = wsl.executeOnWsl(10_000, "mktemp", "-d", "/tmp/elm_deps_hack.XXXXXXXXXX")
        val path = output.stdout.trim()
        if (!output.isSuccess || !path.startsWith('/') || path.contains('\n')) {
            throw ProjectLoadException("Could not create a temporary directory in WSL: ${output.stderr}")
        }
        return Paths.get(wsl.getWindowsPath(path))
    }

    companion object {
        fun forManifest(manifestPath: Path): ElmProjectEnvironment = ElmProjectEnvironment(
            if (SystemInfo.isWindows) WslPath.getDistributionByWindowsUncPath(manifestPath.toString()) else null
        )
    }
}

/** Linux paths must not be resolved using the Windows host's Path implementation. */
internal fun resolveWslElmHome(elmHome: String?, userHome: String): String =
    when {
        elmHome.isNullOrBlank() -> "${userHome.trimEnd('/')}/.elm"
        elmHome.startsWith('/') -> elmHome
        // The dependency-install build runs in a throwaway directory, so a relative ELM_HOME
        // would put its cache there and delete it at cleanup. Do not silently use another cache.
        else -> throw ProjectLoadException("ELM_HOME in WSL must be an absolute Linux path: $elmHome")
    }
