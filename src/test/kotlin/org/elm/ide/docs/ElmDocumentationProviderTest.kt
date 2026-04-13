package org.elm.ide.docs

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.PsiElement
import org.elm.lang.ElmTestBase
import org.intellij.lang.annotations.Language

abstract class ElmDocumentationProviderTest : ElmTestBase() {
    protected fun doTest(
            @Language("Elm") code: String,
            @Language("Html") expected: String
    ) {
        addFileToFixture(code)

        val (originalElement, _, offset) = findElementWithDataAndOffsetInEditor<PsiElement>()
        val element = TargetElementUtil.getInstance()
            .findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted, offset)
            ?: originalElement

        val actual = ElmDocumentationProvider().generateDoc(element, originalElement)?.trim()!!
        assertSameLines(expected.trimIndent(), actual)
    }
}
