package org.elm.ide.inspections

import com.intellij.codeInsight.intention.FileModifier
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInspection.IntentionAndQuickFixAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.elm.workspace.elmreview.Region

class ApplySuggestionFix(
    private val message: String,
    @FileModifier.SafeFieldForPreview
    private val patches: List<Pair<String, Region>>,
    @FileModifier.SafeFieldForPreview
    private val doc: Document
) : IntentionAndQuickFixAction(), HighPriorityAction {

    override fun getFamilyName(): String = "Apply external linter suggestion"

    override fun availableInBatchMode(): Boolean = false

    override fun getName(): String = message

    override fun applyFix(project: Project, file: PsiFile?, editor: Editor?) {
        val document = editor?.document ?: file?.viewProvider?.document ?: doc
        patches
            .mapNotNull { (replacement, region) ->
                PatchLocation.fromRegion(document, region)?.let { replacement to it }
            }
            .sortedByDescending { (_, location) -> location.startOffset() }
            .forEach { (replacement, location) ->
                when (location) {
                    is PatchLocation.Range -> document.replaceString(location.value.startOffset, location.value.endOffset, replacement)
                    is PatchLocation.Point -> document.insertString(location.value, replacement)
                }
            }
        if (ApplicationManager.getApplication().isWriteAccessAllowed && editor != null) {
            FileDocumentManager.getInstance().saveDocument(editor.document)
        }
    }
}

private sealed class PatchLocation : Comparable<PatchLocation> {
    abstract fun startOffset(): Int

    override fun compareTo(other: PatchLocation): Int = startOffset().compareTo(other.startOffset())

    class Range(val value: TextRange) : PatchLocation() {
        override fun startOffset(): Int = value.startOffset
    }

    class Point(val value: Int) : PatchLocation() {
        override fun startOffset(): Int = value
    }

    companion object {
        fun fromRegion(document: Document, region: Region): PatchLocation? {
            return if (region.start?.line == region.end?.line && region.start?.column == region.end?.column) {
                Region.toOffset(document, region.start?.line ?: return null, region.start?.column ?: return null)?.let { Point(it) }
            } else {
                region.toTextRange(document)?.let { Range(it) }
            }
        }
    }
}
