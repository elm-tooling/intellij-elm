package org.elm.ide.lineMarkers

import org.junit.Test


class ElmTestModuleLineMarkerProviderTest : ElmLineMarkerProviderTestBase() {


    @Test
    fun `test test module can run all tests`() = doTestByText(
            """
module Main exposing (..) --> Run all tests in this module

import Test exposing (..)

""")

    @Test
    fun `test non-test module can't run tests`() = doTestByText(
        """
module Main exposing (..)

""")

}
