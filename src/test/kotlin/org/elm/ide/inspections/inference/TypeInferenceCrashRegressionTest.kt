package org.elm.ide.inspections.inference

import org.elm.lang.ElmTestBase
import org.elm.lang.core.psi.descendantsOfType
import org.elm.lang.core.psi.elements.ElmValueDeclaration
import org.elm.lang.core.types.findInference
import org.junit.Test

class TypeInferenceCrashRegressionTest : ElmTestBase() {
    override fun getProjectDescriptor() = ElmWithStdlibDescriptor

    @Test
    fun `test incomplete binary expression does not crash inference`() {
        addFileToFixture("""
module Main exposing (..)

main =
    let
        x =
            1 +
    in
    x
""")

        myFixture.file.descendantsOfType<ElmValueDeclaration>().forEach { declaration ->
            declaration.findInference()
        }
    }

    @Test
    fun `test chained incomplete binary expression does not crash inference`() {
        addFileToFixture("""
module Main exposing (..)

main =
    let
        x =
            1 + 2 *
    in
    x
""")

        myFixture.file.descendantsOfType<ElmValueDeclaration>().forEach { declaration ->
            declaration.findInference()
        }
    }
}
