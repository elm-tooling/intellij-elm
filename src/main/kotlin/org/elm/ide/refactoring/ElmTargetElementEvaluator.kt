package org.elm.ide.refactoring

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.parentOfType
import org.elm.lang.core.psi.elements.ElmModuleDeclaration

/**
 * Make all segments of module declaration QIDs act as the module declaration target.
 *
 * This enables caret-based actions (Find Usages / Rename) from any segment in:
 *   module Foo.Bar.Baz exposing (..)
 */
class ElmTargetElementEvaluator : TargetElementEvaluatorEx2() {

    override fun getNamedElement(element: PsiElement): PsiElement? {
        val moduleDecl = element.parentOfType<ElmModuleDeclaration>(strict = false) ?: return null

        val moduleQidRange = moduleDecl.upperCaseQID.textRange
        return if (moduleQidRange.contains(element.textRange)) moduleDecl else null
    }
}
