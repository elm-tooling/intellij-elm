package org.elm.workspace.commandLineTools

import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.Location
import org.elm.workspace.elmreview.Region
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class ElmReviewCLITest {

    @Test
    fun `sortElmReviewErrors handles entries without regions`() {
        val noRegion = ElmReviewError(path = "src/Main.elm", message = "no region")
        val laterLine = ElmReviewError(path = "src/Main.elm", region = Region(Location(3, 1), Location(3, 2)))
        val earlierLine = ElmReviewError(path = "src/Main.elm", region = Region(Location(1, 5), Location(1, 6)))

        val sorted = sortElmReviewErrors(listOf(noRegion, laterLine, earlierLine))

        assertEquals(listOf(earlierLine, laterLine, noRegion), sorted)
    }

    @Test
    fun `elm-review command line uses console environment`() {
        val commandLine = buildReviewCommandLine(
            executablePath = Paths.get("/tmp/elm-review"),
            workDir = Paths.get("/tmp/project"),
            arguments = listOf("--report=json")
        )

        assertEquals(
            com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType.CONSOLE,
            commandLine.parentEnvironmentType
        )
    }
}
