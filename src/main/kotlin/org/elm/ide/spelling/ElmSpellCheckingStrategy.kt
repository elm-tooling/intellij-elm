package org.elm.ide.spelling

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.inspections.PlainTextSplitter
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenizerBase
import com.intellij.spellchecker.tokenizer.Tokenizer
import org.elm.lang.core.ElmLanguage
import org.elm.lang.core.psi.ElmTypes
import org.elm.lang.core.psi.elementType


class ElmSpellCheckingStrategy : SpellcheckingStrategy() {

    override fun isMyContext(element: PsiElement): Boolean {
        return element.language.`is`(ElmLanguage)
    }

    override fun getTokenizer(element: PsiElement?): Tokenizer<*> {
        return if (element?.elementType == ElmTypes.REGULAR_STRING_PART) {
            ELM_STRING_TOKENIZER
        } else {
            super.getTokenizer(element)
        }
    }
}

private val ELM_STRING_TOKENIZER: Tokenizer<PsiElement> =
    TokenizerBase(PlainTextSplitter.getInstance())
