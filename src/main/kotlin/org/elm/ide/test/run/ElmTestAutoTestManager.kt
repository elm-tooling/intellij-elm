package org.elm.ide.test.run

import com.intellij.execution.testframework.autotest.AbstractAutoTestManager
import com.intellij.execution.testframework.autotest.DelayedDocumentWatcher
import com.intellij.openapi.components.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elm.lang.core.ElmFileType
import java.util.function.Predicate

@State(
    name = "ElmTestAutoTestManager",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
@Service(Service.Level.PROJECT)
class ElmTestAutoTestManager internal constructor(
    project: Project
) : AbstractAutoTestManager(project) {

    override fun createWatcher(project: Project) =
        DelayedDocumentWatcher(project,
            myDelayMillis,
            this::restartAllAutoTests,
            Predicate { it: VirtualFile -> it.fileType == ElmFileType && FileEditorManager.getInstance(project).isFileOpen(it) }
        )
}

val Project.elmAutoTestManager
    get() = service<ElmTestAutoTestManager>()
