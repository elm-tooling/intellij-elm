package org.elm.ide.annotator

import com.intellij.codeHighlighting.DirtyScopeTrackingHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.FileStatusMap
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import org.elm.ide.inspections.ElmReviewResult
import org.elm.ide.inspections.highlightsForFile
import org.elm.lang.core.psi.ElmFile
import org.elm.workspace.ElmReviewService
import org.elm.workspace.elmReviewService
import org.elm.workspace.elmSettings
import org.elm.workspace.elmWorkspace
import org.elm.workspace.elmreview.ElmReviewError
import java.nio.file.Path

private val log = logger<ElmReviewPass>()

class ElmReviewPass(
    private val file: PsiFile,
    private val editor: Editor
) : TextEditorHighlightingPass(file.project, editor.document), DumbAware {

    @Volatile
    private var annotationInfo: ElmReviewResult? = null

    override fun doCollectInformation(progress: ProgressIndicator) {
        if (file !is ElmFile || !isPassEnabled()) return
        val elmProject = file.elmProject ?: return
        val pathToListenFor: Path = elmProject.projectDirPath

        val service = editor.project?.elmReviewService ?: return
        val sourceFilePath = runReadAction { Path.of(file.virtualFile.path) }
        service.runReviewOnDocumentChange(
            projectBasePath = pathToListenFor,
            sourceFilePath = sourceFilePath,
            documentModificationStamp = document.modificationStamp
        )
        val messages = if (service.hasFreshResults(pathToListenFor)) {
            service.messagesForCurrentProject(pathToListenFor)
        } else {
            emptyList()
        }
        annotationInfo = ElmReviewResult(messages, pathToListenFor, 0)
    }

    override fun doApplyInformationToEditor() {
        if (file !is ElmFile || !isPassEnabled()) {
            doFinish(emptyList())
            return
        }
        runReadAction {
            doFinish(collectCurrentFileHighlights())
        }
    }

    private fun collectCurrentFileHighlights(): List<HighlightInfo> {
        val result = annotationInfo ?: return emptyList()
        return highlightsForFile(
            file.project,
            result.basePath,
            result,
            targetVirtualFilePath = file.virtualFile.path
        ).map { it.second }
    }

    private fun doFinish(groupedHighlights: List<HighlightInfo>) {
        invokeLater(ModalityState.stateForComponent(editor.component)) {
            applyHighlighters(groupedHighlights)
            DaemonCodeAnalyzerEx.getInstanceEx(myProject).fileStatusMap.markFileUpToDate(document, id)
        }
    }

    private fun applyHighlighters(groupedHighlights: List<HighlightInfo>) {
        val appliedByBackgroundApi = runCatching {
            backgroundUpdateHighlightersMethod?.invoke(
                null,
                myProject,
                file,
                document,
                0,
                file.textLength,
                groupedHighlights,
                id
            )
        }.isSuccess
        if (appliedByBackgroundApi) return

        runCatching {
            legacyUpdateHighlightersMethod?.invoke(
                null,
                myProject,
                document,
                0,
                file.textLength,
                groupedHighlights,
                colorsScheme,
                id
            )
        }.onFailure {
            log.warn("elm-review pass failed to apply highlights", it)
        }
    }

    private fun isPassEnabled(): Boolean = file.project.elmSettings.toolchain.isElmReviewOnTheFlyEnabled

    companion object {
        private val backgroundUpdateHighlightersMethod = runCatching {
            val documentClass = Class.forName("com.intellij.openapi.editor.Document")
            Class.forName("com.intellij.codeInsight.daemon.impl.BackgroundUpdateHighlightersUtil").getMethod(
                "setHighlightersToEditor",
                Project::class.java,
                PsiFile::class.java,
                documentClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Collection::class.java,
                Int::class.javaPrimitiveType
            )
        }.getOrNull()

        private val legacyUpdateHighlightersMethod = runCatching {
            Class.forName("com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil").getMethod(
                "setHighlightersToEditor",
                Project::class.java,
                Class.forName("com.intellij.openapi.editor.Document"),
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Collection::class.java,
                Class.forName("com.intellij.openapi.editor.colors.EditorColorsScheme"),
                Int::class.javaPrimitiveType
            )
        }.getOrNull()
    }
}

class ElmReviewPassFactory(
    project: Project,
    registrar: TextEditorHighlightingPassRegistrar
) : DirtyScopeTrackingHighlightingPassFactory {
    private val parentDisposable = project.elmWorkspace
    private val passId: Int = registrar.registerTextEditorHighlightingPass(this, null, null, false, -1)
    private val elmReviewQueue = MergingUpdateQueue(
        "ElmReviewQueue",
        300,
        true,
        MergingUpdateQueue.ANY_COMPONENT,
        parentDisposable,
        null,
        false
    )

    init {
        project.messageBus.connect(parentDisposable).subscribe(
            ElmReviewService.ELM_REVIEW_WATCH_TOPIC,
            object : ElmReviewService.ElmReviewWatchListener {
                override fun update(baseDirPath: Path, messages: List<ElmReviewError>) {
                    scheduleExternalActivity(object : Update(baseDirPath) {
                        override fun run() {
                            val daemon = DaemonCodeAnalyzerEx.getInstanceEx(project)
                            val docsToMark = runReadAction {
                                val psiManager = PsiManager.getInstance(project)
                                FileEditorManager.getInstance(project).openFiles
                                    .mapNotNull { psiManager.findFile(it) as? ElmFile }
                                    .filter { it.elmProject?.projectDirPath == baseDirPath }
                                    .mapNotNull { FileDocumentManager.getInstance().getDocument(it.virtualFile) }
                            }
                            docsToMark.forEach { doc -> daemon.markDocumentDirty(doc, this) }
                            daemon.settingsChanged()
                        }
                    })
                }
            }
        )
    }

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        FileStatusMap.getDirtyTextRange(editor.document, file, passId) ?: return null
        return ElmReviewPass(file, editor)
    }

    override fun getPassId(): Int = passId

    fun scheduleExternalActivity(update: Update) = elmReviewQueue.queue(update)
}
