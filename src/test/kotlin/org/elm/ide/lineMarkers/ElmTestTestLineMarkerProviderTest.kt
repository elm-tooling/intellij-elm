package org.elm.ide.lineMarkers

import org.elm.workspace.ElmToolchain
import org.elm.workspace.elmWorkspace
import org.junit.Test


class ElmTestTestLineMarkerProviderTest : ElmLineMarkerProviderTestBase() {
    fun withElmTestRsEnabled(enabled: Boolean, test: () -> Unit) {
        val toolchain = ElmToolchain(
            elmCompilerPath = "",
            lamderaCompilerPath = "",
            elmFormatPath = "",
            elmTestPath = "",
            elmTestRsPath = "",
            elmReviewPath = "",
            isElmTestRsEnabled = enabled,
            isElmFormatOnSaveEnabled = false
        )

        project.elmWorkspace.useToolchain(toolchain)

        test()
    }

    @Test
    fun `test with elm-test properly adds markers`() {
        withElmTestRsEnabled(false) {
            doTestByText(
                """
module Main --> Run all tests in this module

import Test exposing (..)

myTest : Test
myTest =
    describe "my test"
        [ test "1 == 1" <| \_ -> Expect.equal 1 1
        ]
            """
            )
        }
    }

    @Test
    fun `test with elm-test-rs properly adds markers`() {
        withElmTestRsEnabled(true) {
            doTestByText(
                """
module Main --> Run all tests in this module

import Test exposing (..)

myTest : Test
myTest =
    describe "my test" --> Run describe
        [ test "1 == 1" <| \_ -> Expect.equal 1 1 --> Run test
        ]
            """
            )
        }
    }


    @Test
    fun `test non-test module can't run tests`() = doTestByText(
        """
module Main exposing (..)

""")

}
