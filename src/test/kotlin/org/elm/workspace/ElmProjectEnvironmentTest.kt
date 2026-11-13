package org.elm.workspace

import org.elm.workspace.compiler.nullOutputTargetPathString
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElmProjectEnvironmentTest {
    @Test
    fun `local attachment keeps the local null device and cache lookup`() {
        val manifest = Paths.get(System.getProperty("user.home"), "project", "elm.json")
        val environment = ElmProjectEnvironment.forManifest(manifest)
        assertEquals(nullOutputTargetPathString(), environment.nullOutput)
        assertEquals(null, environment.packageCacheHome())
    }

    @Test
    fun `temporary project is removed after success`() {
        val environment = ElmProjectEnvironment.forManifest(Paths.get("elm.json"))
        val dir = environment.withTempDirectory { dir ->
            Files.createDirectories(dir.resolve("src"))
            Files.writeString(dir.resolve("src/Main.elm"), "module Main exposing (..)")
            assertTrue(Files.exists(dir.resolve("src/Main.elm")))
            dir
        }
        assertFalse(Files.exists(dir))
    }

    @Test
    fun `temporary project is removed if compilation throws`() {
        val environment = ElmProjectEnvironment.forManifest(Paths.get("elm.json"))
        lateinit var dir: Path
        assertFailsWith<IllegalStateException> {
            environment.withTempDirectory {
                dir = it
                Files.writeString(dir.resolve("elm.json"), "{}")
                error("Process launch failed")
            }
        }
        assertFalse(Files.exists(dir))
    }

    @Test
    fun `WSL uses Linux home rather than Windows home`() {
        assertEquals("/home/user/.elm", resolveWslElmHome(null, "/home/user"))
        assertEquals("/home/user/.elm", resolveWslElmHome("", "/home/user/"))
    }

    @Test
    fun `WSL respects absolute ELM_HOME and rejects a temporary relative cache`() {
        assertEquals("/cache/elm", resolveWslElmHome("/cache/elm", "/home/user"))
        assertFailsWith<ProjectLoadException> { resolveWslElmHome(".cache/elm", "/home/user") }
    }

    @Test
    fun `repository uses the supplied environment cache`() {
        val home = Paths.get("target-home", ".elm")
        val repo = ElmPackageRepository(Version(0, 19, 1), home)
        assertEquals(home.toString(), repo.elmHomePath)
        assertEquals(home.resolve("0.19.1/packages/elm/core/1.0.5/elm.json"),
            repo.findPackageManifest("elm/core", Version(1, 0, 5)))
    }
}
