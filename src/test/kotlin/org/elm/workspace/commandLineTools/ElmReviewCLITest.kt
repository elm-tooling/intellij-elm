package org.elm.workspace.commandLineTools

import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.Location
import org.elm.workspace.elmreview.Region
import org.junit.Assert.assertEquals
import org.junit.Test

class ElmReviewCLITest {

    @Test
    fun `sortElmReviewErrors handles entries without regions`() {
        val noRegion = ElmReviewError(path = "src/Main.elm", message = "no region")
        val laterLine = ElmReviewError(path = "src/Main.elm", region = Region(Location(3, 1), Location(3, 2)))
        val earlierLine = ElmReviewError(path = "src/Main.elm", region = Region(Location(1, 5), Location(1, 6)))

        val sorted = sortElmReviewErrors(listOf(noRegion, laterLine, earlierLine))

        assertEquals(listOf(earlierLine, laterLine, noRegion), sorted)
    }
}
