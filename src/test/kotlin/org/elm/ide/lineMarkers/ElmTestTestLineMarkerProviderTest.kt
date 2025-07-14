package org.elm.ide.lineMarkers

import org.junit.Test


class ElmTestTestLineMarkerProviderTest : ElmLineMarkerProviderTestBase() {


    @Test
    fun `test test module properly adds markers`() = doTestByText(
            """
module Main --> Run all tests in this module

import Test exposing (..)

myTest : Test
myTest =
    describe "my test" --> Run describe
        [ test "1 == 1" <| \_ -> Expect.equal 1 1 --> Run test
        ]

""")

    @Test
    fun `test non-test module can't run tests`() = doTestByText(
        """
module Main exposing (..)

""")

}
