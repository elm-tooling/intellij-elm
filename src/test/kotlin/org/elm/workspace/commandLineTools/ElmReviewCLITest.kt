package org.elm.workspace.commandLineTools

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class ElmReviewCLITest {

    @Test
    fun `elm-review command line uses console environment`() {
        val commandLine = buildReviewCommandLine(
            executablePath = Paths.get("/tmp/elm-review"),
            workDir = Paths.get("/tmp/project"),
            arguments = listOf("--report=json"),
            compilerPath = null,
            suggestedTools = emptyMap()
        )

        assertEquals(
            com.intellij.execution.configurations.GeneralCommandLine.ParentEnvironmentType.CONSOLE,
            commandLine.parentEnvironmentType
        )
    }
}
