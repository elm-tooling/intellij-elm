package org.elm.lang.core.resolve

import org.junit.Test


class ElmWildcardImportResolveTest : ElmResolveTestBase() {

    @Test
    fun `test explicit import shadowing wildcard`() = stubOnlyResolve(
            """
--@ main.elm
import Foo exposing (..)
import Bar exposing (bar)
main = bar
       --^Bar.elm

--@ Foo.elm
module Foo exposing (..)
bar = 42

--@ Bar.elm
module Bar exposing (..)
bar = 99
""")

    @Test
    fun `test explicit import shadowing wildcard 2`() = stubOnlyResolve(
            """
--@ main.elm
import Bar exposing (..)
import Foo exposing (bar)
main = bar
       --^Foo.elm

--@ Foo.elm
module Foo exposing (..)
bar = 42

--@ Bar.elm
module Bar exposing (..)
bar = 99
""")

    // https://github.com/elm/compiler/issues/2277
    // A type exposed explicitly must shadow one brought in by a wildcard import,
    // regardless of the order in which the imports are declared.
    @Test
    fun `test explicit type import shadowing wildcard`() = stubOnlyResolve(
            """
--@ main.elm
import Foo exposing (..)
import Bar exposing (Thing)
type alias Wrapper = Thing
                     --^Bar.elm

--@ Foo.elm
module Foo exposing (..)
type Thing = Thing

--@ Bar.elm
module Bar exposing (..)
type Thing = Thing
""")

    // Same as above, but with the explicit import first, to prove that the
    // explicit import wins regardless of the order in which they are declared.
    @Test
    fun `test explicit type import shadowing wildcard 2`() = stubOnlyResolve(
            """
--@ main.elm
import Foo exposing (Thing)
import Bar exposing (..)
type alias Wrapper = Thing
                     --^Foo.elm

--@ Foo.elm
module Foo exposing (..)
type Thing = Thing

--@ Bar.elm
module Bar exposing (..)
type Thing = Thing
""")
}
