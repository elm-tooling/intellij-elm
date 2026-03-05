package org.elm.workspace.elmreview

import org.elm.workspace.compiler.ElmBuildTargetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory

class ElmReviewCompilerResolverTest {

    @Test
    fun `global compiler wins over all fallbacks`() {
        val root = createTempDirectory("elm-review-resolver-global")
        val global = createExecutable(root, "global-elm")
        val targetCompiler = createExecutable(root, "target-elm")
        val discoveredElm = createExecutable(root, "discovered-elm")

        val result = resolveElmReviewCompiler(
            projectBasePath = root,
            toolchainCompilerPath = global,
            buildTargets = listOf(
                ElmBuildTargetConfig(name = "Target A", compilerPath = targetCompiler.toString())
            ),
            suggestedTools = mapOf("elm" to discoveredElm)
        )

        assertEquals(ElmReviewCompilerSource.GLOBAL, result.source)
        assertEquals(global, result.path)
    }

    @Test
    fun `uses first valid build target compiler when global is unavailable`() {
        val root = createTempDirectory("elm-review-resolver-target")
        val firstInvalid = root.resolve("missing-compiler")
        val secondValid = createExecutable(root, "target-compiler")

        val result = resolveElmReviewCompiler(
            projectBasePath = root,
            toolchainCompilerPath = null,
            buildTargets = listOf(
                ElmBuildTargetConfig(name = "Invalid", compilerPath = firstInvalid.toString()),
                ElmBuildTargetConfig(name = "Valid", compilerPath = secondValid.toString())
            ),
            suggestedTools = emptyMap()
        )

        assertEquals(ElmReviewCompilerSource.BUILD_TARGET, result.source)
        assertEquals(secondValid, result.path)
        assertEquals("Valid", result.buildTargetName)
    }

    @Test
    fun `supports relative build target compiler path`() {
        val root = createTempDirectory("elm-review-resolver-relative")
        val relativeDir = root.resolve("tools").createDirectories()
        val compiler = createExecutable(relativeDir, "elm")

        val result = resolveElmReviewCompiler(
            projectBasePath = root,
            toolchainCompilerPath = null,
            buildTargets = listOf(
                ElmBuildTargetConfig(name = "Relative", compilerPath = "tools/elm")
            ),
            suggestedTools = emptyMap()
        )

        assertEquals(ElmReviewCompilerSource.BUILD_TARGET, result.source)
        assertEquals(compiler, result.path)
    }

    @Test
    fun `falls back to discovered compiler in elm lamdera wrap order`() {
        val root = createTempDirectory("elm-review-resolver-discovered")
        val lamdera = createExecutable(root, "lamdera")
        val wrap = createExecutable(root, "wrap")

        val result = resolveElmReviewCompiler(
            projectBasePath = root,
            toolchainCompilerPath = null,
            buildTargets = emptyList(),
            suggestedTools = mapOf(
                "lamdera" to lamdera,
                "wrap" to wrap
            )
        )

        assertEquals(ElmReviewCompilerSource.DISCOVERED, result.source)
        assertEquals(lamdera, result.path)
        assertEquals("lamdera", result.discoveredToolName)
    }

    @Test
    fun `returns none when no executable compiler is available`() {
        val root = createTempDirectory("elm-review-resolver-none")
        val result = resolveElmReviewCompiler(
            projectBasePath = root,
            toolchainCompilerPath = null,
            buildTargets = emptyList(),
            suggestedTools = emptyMap()
        )

        assertEquals(ElmReviewCompilerSource.NONE, result.source)
        assertEquals(null, result.path)
    }

    private fun createExecutable(dir: Path, name: String): Path {
        val file = dir.resolve(name).createFile()
        val ok = file.toFile().setExecutable(true)
        assertTrue("failed to mark executable: $file", ok)
        assertTrue(Files.isExecutable(file))
        return file
    }
}
