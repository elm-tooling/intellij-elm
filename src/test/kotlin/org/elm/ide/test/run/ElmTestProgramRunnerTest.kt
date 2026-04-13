package org.elm.ide.test.run

import org.jdom.Element
import org.junit.Test

class ElmTestProgramRunnerTest {

    @Test
    fun `settings read and write external do not throw`() {
        val settings = Settings()
        val root = Element("runner")

        settings.writeExternal(root)
        settings.readExternal(root)
    }
}
