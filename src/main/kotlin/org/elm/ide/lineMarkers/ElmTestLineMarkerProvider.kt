package org.elm.ide.lineMarkers

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.FunctionUtil
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Abstract class to handle common operations for all test marker classes
 */
abstract class ElmTestLineMarkerProvider : LineMarkerProvider {
    /**
     * Create a popup with a list of given actions
     */
    private class PopupHandler(val actions: List<AnAction>) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, elt: PsiElement) {
            val group = DefaultActionGroup().apply {
                addAll(actions)
            }

            val popup = JBPopupFactory.getInstance()
                .createActionGroupPopup(
                    null,
                    group,
                    DataManager.getInstance().getDataContext(e.component),
                    JBPopupFactory.ActionSelectionAid.MNEMONICS,
                    true
                )

            popup.show(RelativePoint(e))
        }
    }

    /**
     * Returns true if the line should contain a gutter icon for the type of marker
     */
    abstract fun shouldAddGutterIcon(element: PsiElement): Boolean

    /**
     * Returns true if the file contains tests
     */
    protected fun isTestFile(element: PsiElement): Boolean {
        return element.containingFile.text.contains("import Test exposing")
    }

    /**
     * Create a gutter icon on a given line with an icon, tooltip, and list of popup actions
     */
    protected fun createLineMarkerInfo(element: PsiElement, icon: Icon, tooltip: String, actions: List<AnAction>, ): LineMarkerInfo<*>? {
        if (!isTestFile(element) || !shouldAddGutterIcon(element)) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            FunctionUtil.constant(tooltip),
            PopupHandler(actions),
            GutterIconRenderer.Alignment.LEFT
        ) { tooltip }
    }
}