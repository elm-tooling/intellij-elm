package org.elm.ide.lineMarkers

import com.intellij.codeInsight.daemon.GutterIconDescriptor
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiElement
import org.elm.ide.icons.ElmIcons
import org.elm.ide.test.core.ElmTestElementNavigator
import org.elm.workspace.elmToolchain

/** * Handles adding a gutter icon for running a specific test */
class ElmTestSingleLineMarkerProvider : ElmTestLineMarkerProvider() {
    companion object {
        val OPTION = GutterIconDescriptor.Option("elm.testSingle", "Test single", ElmIcons.RUN)
    }

    /** * Add gutter icons for the test line */
    override fun shouldAddGutterIcon(element: PsiElement): Boolean {
        return element.project.elmToolchain.isElmTestRsEnabled && element.text == "test"
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!shouldAddGutterIcon(element)) return null
        val filter = ElmTestElementNavigator.findTestDescription(element) ?: return null

        return createLineMarkerInfo(
            element,
            ElmIcons.RUN,
            "Run test",
            listOf(RunFilteredTestAction(element, filter), ModifyRunConfiguration(element, filter)),
        )
    }
}
