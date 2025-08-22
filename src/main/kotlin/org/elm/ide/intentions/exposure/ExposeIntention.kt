package org.elm.ide.intentions.exposure

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.ElmExposableTag
import org.elm.lang.core.psi.elements.*
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils

/**
 * An intention action that adds a function to a module's `exposing` list.
 */
open class ExposeIntention : ExposureIntentionBase<ExposeIntention.Context>() {

    data class Context(val nameToExpose: String, val exposingList: ElmExposingList)

    override fun getText() = "Expose"

    override fun findApplicableContext(project: Project, editor: Editor, element: PsiElement): Context? {
        val exposingList = getExposingList(element) ?: return null

        // check if the caret is on the identifier that names the exposable declaration
        val decl = element.parent as? ElmExposableTag ?: return null
        if (decl.nameIdentifier != element) return null

        return when {
            // might be nice to support this in the future (making a union type fully exposed)
            decl is ElmUnionVariant -> null
            decl is ElmFunctionDeclarationLeft && !decl.isTopLevel -> null
            !exposingList.exposes(decl) -> createContext(decl, exposingList)
            else -> null
        }
    }

    /**
     * Creates a [Context] based on the passed in parameters. Overriding subclasses can return null if they find that
     * the passed in [decl] isn't valid for their particular intention.
     */
    protected open fun createContext(decl: ElmExposableTag, exposingList: ElmExposingList): Context? =
        Context(decl.name, exposingList)

    override fun invoke(project: Project, editor: Editor, context: Context) {
        // The actual mutation (no write wrappers inside this lambda)
        fun applyEdits() {
            context.exposingList.addItem(context.nameToExpose)
        }

        if (IntentionPreviewUtils.isIntentionPreviewActive()) {
            // Preview: we're on a non-physical PSI copy under a read action.
            // Do NOT start write/command actions here.
            applyEdits()
        } else {
            // Real run: perform under a write command so it’s undoable.
            WriteCommandAction.writeCommandAction(project)
                .withName(text)
                .run<RuntimeException> { applyEdits() }
        }
    }

    // (Optional) Keep this true so real runs happen in write context;
    // the platform still won't give you a write action during preview.
    override fun startInWriteAction(): Boolean = true
}
