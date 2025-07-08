package org.elm.ide.lineMarkers

import com.intellij.codeInsight.daemon.GutterIconDescriptor
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiElement
import org.elm.ide.icons.ElmIcons
import org.elm.lang.core.psi.ElmTypes

/**
 * Handles adding a gutter icon for running all tests in a module
 */
class ElmTestModuleLineMarkerProvider : ElmTestLineMarkerProvider() {
    companion object {
        val OPTION = GutterIconDescriptor.Option("elm.test", "Test module", ElmIcons.RUN_ALL)
    }

    /**
     * Add gutter icons for the module line
     */
    override fun shouldAddGutterIcon(element: PsiElement): Boolean {
        return element.node.elementType == ElmTypes.MODULE
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return createLineMarkerInfo(
            element,
            ElmIcons.RUN_ALL,
            "Run all tests in this module",
            listOf(RunAllTestsAction(element), ModifyRunConfiguration(element))
        )
    }
}
