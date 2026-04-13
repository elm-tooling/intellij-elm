package org.elm.ide.toolwindow

import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.ElmReviewErrorOrigin
import org.elm.workspace.elmreview.Location
import org.elm.workspace.elmreview.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElmReviewToolWindowFactoryTest {

    @Test
    fun `elmReviewLocation returns null when start is missing`() {
        val error = ElmReviewError(region = Region(start = null, end = Location(1, 1)))
        assertNull(elmReviewLocation(error))
    }

    @Test
    fun `elmReviewLocation converts location to zero based coordinates`() {
        val error = ElmReviewError(region = Region(start = Location(2, 3), end = Location(2, 4)))
        assertEquals(1 to 2, elmReviewLocation(error))
    }

    @Test
    fun `elmReviewTreeMessage truncates compiler error at first newline`() {
        val error = ElmReviewError(message = "Line one\nLine two").apply {
            origin = ElmReviewErrorOrigin.COMPILER
        }
        assertEquals("Line one", elmReviewTreeMessage(error))
    }

    @Test
    fun `elmReviewTreeMessage preserves multiline text for review rule errors`() {
        val error = ElmReviewError(message = "Line one\nLine two").apply {
            origin = ElmReviewErrorOrigin.REVIEW
        }
        assertEquals("Line one\nLine two", elmReviewTreeMessage(error))
    }

    @Test
    fun `elmReviewTreeRuleLabel uses fix marker format`() {
        val error = ElmReviewError(rule = "NoDebug.Log", suppressed = false)
        assertEquals("NoDebug.Log (fix)", elmReviewTreeRuleLabel(error, isFixable = true, showSuppressed = false))
    }

    @Test
    fun `elmReviewTreeRuleLabel adds unsuppressed marker only in mixed view`() {
        val error = ElmReviewError(rule = "NoDebug.Log", suppressed = false)
        assertEquals("NoDebug.Log", elmReviewTreeRuleLabel(error, isFixable = false, showSuppressed = false))
        assertEquals("NoDebug.Log (unsuppressed)", elmReviewTreeRuleLabel(error, isFixable = false, showSuppressed = true))
    }
}
