package org.elm.ide.actions

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElmBuildTargetSelectionServiceTest {

    private fun roundTrip(service: ElmBuildTargetSelectionService): ElmBuildTargetSelectionService =
        ElmBuildTargetSelectionService().apply { loadState(service.state) }

    @Test
    fun `persists and restores the selected target across a state round-trip`() {
        val key = BuildTargetKey(
            workDir = "/home/me/pkg",
            name = "Tests (pkg)",
            inputPathForCompiler = "",
            outputPathForCompiler = ""
        )
        val service = ElmBuildTargetSelectionService().apply { selectedKey = key }

        assertEquals(key, roundTrip(service).selectedKey)
    }

    @Test
    fun `restores an empty selection as null`() {
        assertNull(roundTrip(ElmBuildTargetSelectionService()).selectedKey)
    }
}
