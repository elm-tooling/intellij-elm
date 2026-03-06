package org.elm.ide.toolwindow

import org.elm.workspace.elmreview.ElmReviewError
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
}
