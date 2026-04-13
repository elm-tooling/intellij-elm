package org.elm.ide.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import org.elm.ide.presentation.getPresentationForStructure
import org.elm.lang.core.psi.ElmPsiElement
import org.elm.lang.core.psi.directChildren
import org.elm.lang.core.psi.elements.ElmValueDeclaration


class ElmPresentableTreeElement(val element: ElmPsiElement)
    : StructureViewTreeElement {

    private val navigatable: Navigatable? = element as? Navigatable


    override fun getChildren(): Array<TreeElement> =
        when (element) {
            is ElmValueDeclaration -> {
                element.directChildDecls
                    .map { ElmPresentableTreeElement(it) }
                    .toList().toTypedArray()
            }
            else -> emptyArray()
        }

    override fun getValue() =
        element

    override fun getPresentation() =
        getPresentationForStructure(element)

    override fun navigate(requestFocus: Boolean) {
        navigatable?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = navigatable?.canNavigate() == true

    override fun canNavigateToSource(): Boolean = navigatable?.canNavigateToSource() == true

}

/** Like [PsiElement.directChildren], but stops at any [ElmValueDeclaration]s */
private val PsiElement.directChildDecls: Sequence<ElmValueDeclaration>
    get() = directChildren.flatMap {
        when (it) {
            is ElmValueDeclaration -> sequenceOf(it)
            else -> sequenceOf(it) + it.directChildDecls
        }
    }.filterIsInstance<ElmValueDeclaration>()
