package org.elm.workspace.commandLineTools

import org.elm.openapiext.Result
import org.elm.workspace.Version
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElmTestCLITest {

    @Test
    fun `parseVersionLine parses elm-test-rs output`() {
        val result = ElmTestCLI.parseVersionLine("elm-test-rs 3.0.1")
        assertTrue(result is Result.Ok)
        assertEquals(Version(3, 0, 1), result.value)
    }

    @Test
    fun `parseVersionLine preserves prerelease versions`() {
        val result = ElmTestCLI.parseVersionLine("0.19.0-beta9")
        assertTrue(result is Result.Ok)
        assertEquals("0.19.0-beta9", result.value.toString())
    }
}
