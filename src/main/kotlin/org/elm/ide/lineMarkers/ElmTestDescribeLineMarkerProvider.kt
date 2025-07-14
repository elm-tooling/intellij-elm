package org.elm.ide.lineMarkers

import com.intellij.codeInsight.daemon.GutterIconDescriptor
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiElement
import org.elm.ide.icons.ElmIcons
import org.elm.ide.test.core.ElmTestElementNavigator

/**
 * Handles adding a gutter icon for running tests under a describe
 */
class ElmTestDescribeLineMarkerProvider : ElmTestLineMarkerProvider() {
    companion object {
        val OPTION = GutterIconDescriptor.Option("elm.testDescribe", "Test describe", ElmIcons.RUN)
    }

    /**
     * Add gutter icons for the describe line
     */
    override fun shouldAddGutterIcon(element: PsiElement): Boolean {
        return element.text == "describe"
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!shouldAddGutterIcon(element)) return null
        val filter = ElmTestElementNavigator.findTestDescription(element) ?: return null

        return createLineMarkerInfo(
            element,
            ElmIcons.RUN,
            "Run describe",
            listOf(RunFilteredTestAction(element, filter), ModifyRunConfiguration(element, filter))
        )
    }
}
