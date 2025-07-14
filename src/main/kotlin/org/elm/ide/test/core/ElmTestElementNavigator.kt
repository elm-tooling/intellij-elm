package org.elm.ide.test.core

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.elm.lang.core.psi.elements.ElmBinOpExpr
import org.elm.lang.core.psi.elements.ElmFunctionCallExpr
import org.elm.lang.core.psi.elements.ElmStringConstantExpr

object ElmTestElementNavigator {
    /**
     * Find the test or describe description text without surrounding quotes
     *
     * This will only match
     *   - test "this works"
     *   - describe "this test"
     *
     * Exactly and not any programatic calls
     */
    fun findTestDescription(element: PsiElement?): String? {
        if (element == null) return null
        val callExpr = getFunctionCallExpr(element) ?: return null

        // look for a string directly after the test or describe function call
        return (callExpr
            .children
            .filterNot { it is PsiWhiteSpace || it is PsiComment }
            .getOrNull(1) as? ElmStringConstantExpr)
            ?.text
            ?.removeSurrounding("\"")
    }

    /**
     * Find the next parent function call given a list of target names
     */
    private fun getFunctionCallExpr(element: PsiElement?, targets: List<String> = listOf("test", "describe")): ElmFunctionCallExpr? {
        var current = element
        while (current != null) {
            val unwrapped = if (current is ElmBinOpExpr) {
                // handles creating a run config from in a test body
                current.children.firstOrNull { it is ElmFunctionCallExpr } ?: current
            } else {
                current
            }
            if (unwrapped is ElmFunctionCallExpr && unwrapped.target.text.substringAfterLast(".") in targets) return unwrapped
            current = current.parent
        }
        return null
    }
}