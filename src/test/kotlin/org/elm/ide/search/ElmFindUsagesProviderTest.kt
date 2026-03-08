/*
The MIT License (MIT)

Derived from intellij-rust
Copyright (c) 2015 Aleksey Kladov, Evgeny Kurbatsky, Alexey Kudinkin and contributors
Copyright (c) 2016 JetBrains

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package org.elm.ide.search

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.PsiElement
import org.elm.fileTreeFromText
import org.elm.lang.ElmTestBase
import org.elm.lang.core.psi.ElmNamedElement
import org.intellij.lang.annotations.Language
import org.junit.Test


private const val MARKER = "-- : "
private const val COMPARE_SEPARATOR = " | "

class ElmFindUsagesProviderTest : ElmTestBase() {


    @Test
    fun `test function parameter usage`() = doTestByText(
        """
foo x =
  --^
    let
        a = x -- : foobar
    in
        x -- : foobar
"""
    )


    @Test
    fun `test binary operator usage`() = doTestByText(
        """
power a b = List.product (List.repeat b a)
infix right 5 (**) = power
              --^

foo = 2 ** 3 -- : foobar
bar = (**) 2 -- : foobar
"""
    )

    @Test
    fun `test module declaration usages from second segment`() {
        fileTreeFromText(
            """
--@ Data/User.elm
module Data.User exposing (..)
            --^

--@ Main.elm
module Main exposing (..)
import Data.User -- : Main.elm:1
import Data.User as X -- : Main.elm:2
import Data.User exposing (..) -- : Main.elm:3
"""
        ).create()
        myFixture.configureFromTempProjectFile("Data/User.elm")
        val (_, _, offset) = findElementWithDataAndOffsetInEditor<PsiElement>()
        val source = TargetElementUtil.getInstance()
            .findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted, offset)
            as? ElmNamedElement
            ?: error("No ElmNamedElement at module declaration caret")

        val actual = myFixture.findUsages(source)
            .mapNotNull { it.element }
            .map { "${it.containingFile.name}:${it.line}" }
            .sorted()
        val expected = listOf("Main.elm:1", "Main.elm:2", "Main.elm:3")
        assertEquals(expected, actual)
    }


    private fun doTestByText(@Language("Elm") code: String) {
        addFileToFixture(code)
        val source = findElementInEditor<ElmNamedElement>()

        val actual = markersActual(source)
        val expected = markersFrom(code)
        assertEquals(expected.joinToString(COMPARE_SEPARATOR), actual.joinToString(COMPARE_SEPARATOR))
    }

    private fun markersActual(source: ElmNamedElement) =
        myFixture.findUsages(source)
            .filter { it.element != null }
            // TODO [kl] implement a UsageTypeProvider and replace "foobar" with the expected usage type
            // both here and in the test cases themselves.
            // .map { Pair(it.element?.line ?: -1, RsUsageTypeProvider.getUsageType(it.element).toString()) }
            .map { Pair(it.element?.line ?: -1, "foobar") }

    private fun markersFrom(text: String) =
        text.split('\n')
            .withIndex()
            .filter { it.value.contains(MARKER) }
            .map { Pair(it.index, it.value.substring(it.value.indexOf(MARKER) + MARKER.length).trim()) }

    val PsiElement.line: Int? get() = containingFile.viewProvider.document?.getLineNumber(textRange.startOffset)

}
