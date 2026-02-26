package org.elm.ide.livetemplates

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiUtilCore
import org.elm.lang.core.ElmLanguage
import org.elm.lang.core.psi.ElmExpressionTag
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.ancestors
import org.elm.lang.core.psi.elements.ElmLetInExpr
import org.elm.lang.core.psi.elements.ElmStringConstantExpr
import org.elm.lang.core.psi.elements.ElmTypeExpression

@Suppress("DEPRECATION")
sealed class ElmLiveTemplateContext(
        id: String,
        presentableName: String
) : TemplateContextType(id, presentableName) {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file
        val offset = templateActionContext.startOffset
        if (!PsiUtilCore.getLanguageAtOffset(file, offset).isKindOf(ElmLanguage)) {
            return false
        }

        val element = file.findElementAt(offset)
        if (element == null ||
                element is PsiComment ||
                element is ElmStringConstantExpr ||
                element.parent is ElmStringConstantExpr) {
            return false
        }

        return isInContext(element)
    }

    protected abstract fun isInContext(element: PsiElement): Boolean

    class Generic : ElmLiveTemplateContext("ELM", "Elm") {
        override fun isInContext(element: PsiElement): Boolean = true
    }

    class TopLevel : ElmLiveTemplateContext("ELM_TOP_LEVEL", "Top level statement") {
        override fun isInContext(element: PsiElement): Boolean {
            return isTopLevel(element)
        }
    }

    class ValueDecl : ElmLiveTemplateContext("ELM_VALUE_DECL", "Function declaration") {
        override fun isInContext(element: PsiElement): Boolean {
            return isTopLevel(element)
                    || element.parent is ElmLetInExpr
        }
    }

    class Expression : ElmLiveTemplateContext("ELM_EXPRESSION", "Expression") {
        override fun isInContext(element: PsiElement): Boolean {
            if (element.parent is ElmLetInExpr) return false

            return element.ancestors
                    .takeWhile { it !is ElmTypeExpression && it !is ElmFile }
                    .any { it is ElmExpressionTag }
        }
    }
}

private fun isTopLevel(element: PsiElement): Boolean {
    return element.parent is ElmFile ||
            element.parent is PsiErrorElement && element.parent?.parent is ElmFile
}
