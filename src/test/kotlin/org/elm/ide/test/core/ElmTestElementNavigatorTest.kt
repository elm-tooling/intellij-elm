package org.elm.ide.test.core

import org.elm.lang.ElmTestBase
import org.junit.Test

class ElmTestElementNavigatorTest : ElmTestBase() {
    @Test
    fun `find description from test`() {
        val psiFile = myFixture.configureByText("ElmTest.elm", getTestFile())
        val testElement = psiFile.findElementAt(psiFile.text.indexOf("test \"1 == 1\" <|"))
        val result = ElmTestElementNavigator.findTestDescription(testElement)

        assertEquals("1 == 1", result)
    }

    @Test
    fun `find description from describe`() {
        val psiFile = myFixture.configureByText("ElmTest.elm", getTestFile())
        val testElement = psiFile.findElementAt(psiFile.text.indexOf("describe \"my suite\""))
        val result = ElmTestElementNavigator.findTestDescription(testElement)

        assertEquals("my suite", result)
    }

    @Test
    fun `walk up to find first test`() {
        val psiFile = myFixture.configureByText("ElmTest.elm", getTestFile())
        val testElement = psiFile.findElementAt(psiFile.text.indexOf("\\_ -> Expect.equal 1 1"))
        val result = ElmTestElementNavigator.findTestDescription(testElement)

        assertEquals("1 == 1", result)
    }

    private fun getTestFile(): String {
        return """
    module Example exposing (..)
    
    import Test exposing (test)
    import Expect
    
    suite : Test
    suite =
        describe "my suite"
            [ test "1 == 1" <|
                \_ -> Expect.equal 1 1
            ]
    """.trimIndent()
    }
}