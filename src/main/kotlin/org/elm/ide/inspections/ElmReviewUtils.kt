package org.elm.ide.inspections

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.elm.workspace.elmreview.ElmReviewError
import java.nio.file.Path

fun highlightsForFile(
    project: Project,
    basePath: Path,
    annotationResult: ElmReviewResult,
    targetVirtualFilePath: String? = null
): List<Pair<PsiFile, HighlightInfo>> {
    return annotationResult.messages.mapNotNull { message ->
        val rule = message.rule ?: return@mapNotNull null
        val summary = message.message ?: return@mapNotNull null
        val messagePath = message.path ?: return@mapNotNull null
        val resolvedPath = Path.of(basePath.toString(), messagePath).toString()
        if (targetVirtualFilePath != null && targetVirtualFilePath != resolvedPath) return@mapNotNull null
        val fileWithError: VirtualFile = LocalFileSystem.getInstance()
            .findFileByPath(resolvedPath)
            ?: return@mapNotNull null
        val psiFile = PsiManager.getInstance(project).findFile(fileWithError) ?: return@mapNotNull null
        val doc = fileWithError.findDocument() ?: return@mapNotNull null

        val severity = when {
            rule.startsWith("NoUnused.") -> HighlightInfoType.UNUSED_SYMBOL
            rule == "NoDeprecated" -> HighlightInfoType.DEPRECATED
            else -> HighlightInfoType.WARNING
        }
        val tooltipHtml = message.html ?: buildString {
            append("<h2>elm-review ")
            append(rule)
            append("</h2><p>")
            append(summary)
            append("</p>")
            if (!message.details.isNullOrEmpty()) {
                append("<br /><p>")
                append(message.details!!.joinToString("\n"))
                append("</p>")
            }
        }

        val highlightBuilder = HighlightInfo.newHighlightInfo(severity)
            .severity(HighlightSeverity.WARNING)
            .description(summary)
            .escapedToolTip(tooltipHtml)

        val textRange = message.region?.toTextRange(doc) ?: return@mapNotNull null
        if (textRange.startOffset < 0 || textRange.startOffset > textRange.endOffset) return@mapNotNull null
        highlightBuilder.range(textRange)

        val fixes = message.fix.orEmpty()
        if (fixes.isNotEmpty()) {
            val fixPatches = fixes.map { fix -> fix.string to fix.range }
            @Suppress("DialogTitleCapitalization")
            val key = HighlightDisplayKey.findOrRegister(ELM_EXTERNAL_LINTER_ID, "elm-review")
            val action = ApplySuggestionFix(
                "Apply elm-review $rule fix",
                fixPatches,
                doc
            )
            highlightBuilder.registerFix(action, null, "elm-review", textRange, key)
        }

        psiFile to (highlightBuilder.create() ?: return@mapNotNull null)
    }
}

private const val ELM_EXTERNAL_LINTER_ID: String = "ElmReviewOptions"

class ElmReviewResult(
    val messages: List<ElmReviewError>,
    val basePath: Path,
    @Suppress("unused")
    val executionTime: Long
)
