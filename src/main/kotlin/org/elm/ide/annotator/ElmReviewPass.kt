package org.elm.ide.annotator

import com.intellij.codeHighlighting.DirtyScopeTrackingHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.FileStatusMap
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import org.elm.ide.inspections.ElmReviewResult
import org.elm.ide.inspections.highlightsForFile
import org.elm.lang.core.psi.ElmFile
import org.elm.workspace.ElmReviewService
import org.elm.workspace.elmReviewService
import org.elm.workspace.elmSettings
import org.elm.workspace.elmreview.ElmReviewError
import java.nio.file.Path

private val log = logger<ElmReviewPass>()

class ElmReviewPass(
    private val factory: ElmReviewPassFactory,
    private val file: PsiFile,
    private val editor: Editor
) : TextEditorHighlightingPass(file.project, editor.document), DumbAware {

    @Volatile
    private var annotationInfo: ElmReviewResult? = null

    override fun doCollectInformation(progress: ProgressIndicator) {
        if (file !is ElmFile || !isPassEnabled()) return
        val pathToListenFor: Path = file.elmProject?.projectDirPath ?: return

        val service = editor.project?.elmReviewService ?: return
        service.start(pathToListenFor)
        updateHighlighting(service.messagesForCurrentProject(pathToListenFor), pathToListenFor)

        PsiManager.getInstance(editor.project!!).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childrenChanged(event: PsiTreeChangeEvent) {
                if (event.file?.virtualFile?.path == file.virtualFile?.path) {
                    updateHighlighting(emptyList(), pathToListenFor)
                }
            }
        }, editor.project!!)
    }

    private fun updateHighlighting(messages: List<ElmReviewError>, basePath: Path) {
        annotationInfo = ElmReviewResult(messages, basePath, 0)
        runReadAction {
            doFinish(collectCurrentFileHighlights())
        }
    }

    override fun doApplyInformationToEditor() {
        if (file !is ElmFile || !isPassEnabled()) {
            doFinish(emptyList())
            return
        }

        class WatchModeUpdate(messages: List<ElmReviewError>) : Update(messages) {
            override fun setRejected() {
                super.setRejected()
                runReadAction {
                    doFinish(collectCurrentFileHighlights())
                }
            }

            override fun run() {
                val result = annotationInfo ?: return
                runReadAction {
                    doApply(result, result.basePath)
                    doFinish(collectCurrentFileHighlights())
                }
            }
        }

        editor.project?.messageBus?.connect()?.subscribe(
            ElmReviewService.ELM_REVIEW_WATCH_TOPIC,
            object : ElmReviewService.ElmReviewWatchListener {
                override fun update(baseDirPath: Path, messages: List<ElmReviewError>) {
                    if (baseDirPath == (file as? ElmFile)?.elmProject?.projectDirPath) {
                        annotationInfo = ElmReviewResult(messages, baseDirPath, 0)
                        factory.scheduleExternalActivity(WatchModeUpdate(messages))
                    }
                }
            }
        )
    }

    private fun doApply(annotationResult: ElmReviewResult, basePath: Path) {
        if (file !is ElmFile || !file.isValid) return
        try {
            // Intentionally empty: we compute fresh HighlightInfo instances in `collectCurrentFileHighlights`.
        } catch (t: Throwable) {
            if (t is ProcessCanceledException) throw t
            log.warn("elm-review pass apply failed", t)
        }
    }

    private fun collectCurrentFileHighlights(): List<HighlightInfo> {
        val result = annotationInfo ?: return emptyList()
        val freshHighlights = highlightsForFile(file.project, result.basePath, result)
        return freshHighlights
            .groupBy { it.first.virtualFile.path }[file.virtualFile.path]
            ?.map { it.second }
            .orEmpty()
    }

    private fun doFinish(groupedHighlights: List<HighlightInfo>) {
        invokeLater(ModalityState.stateForComponent(editor.component)) {
            UpdateHighlightersUtil.setHighlightersToEditor(
                myProject,
                document,
                0,
                file.textLength,
                groupedHighlights,
                colorsScheme,
                id
            )
            DaemonCodeAnalyzerEx.getInstanceEx(myProject).fileStatusMap.markFileUpToDate(document, id)
        }
    }

    private fun isPassEnabled(): Boolean = file.project.elmSettings.toolchain.isElmReviewOnTheFlyEnabled
}

class ElmReviewPassFactory(
    project: Project,
    registrar: TextEditorHighlightingPassRegistrar
) : DirtyScopeTrackingHighlightingPassFactory {
    private val passId: Int = registrar.registerTextEditorHighlightingPass(this, null, null, false, -1)
    private val elmReviewQueue = MergingUpdateQueue(
        "ElmReviewQueue",
        300,
        true,
        MergingUpdateQueue.ANY_COMPONENT,
        project,
        null,
        false
    )

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        FileStatusMap.getDirtyTextRange(editor.document, file, passId) ?: return null
        return ElmReviewPass(this, file, editor)
    }

    override fun getPassId(): Int = passId

    fun scheduleExternalActivity(update: Update) = elmReviewQueue.queue(update)
}
