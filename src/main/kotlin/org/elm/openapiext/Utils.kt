/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 *
 * From intellij-rust
 */

package org.elm.openapiext

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.jdom.Element
import org.jdom.input.SAXBuilder
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.relativeTo


fun <T> Project.runWriteCommandAction(command: () -> T): T {
    return WriteCommandAction.runWriteCommandAction(this, Computable<T> { command() })
}

val Project.modules: Collection<Module>
    get() = ModuleManager.getInstance(this).modules.toList()


fun checkWriteAccessAllowed() {
    check(ApplicationManager.getApplication().isWriteAccessAllowed) {
        "Needs write action"
    }
}

fun checkReadAccessAllowed() {
    check(ApplicationManager.getApplication().isReadAccessAllowed) {
        "Needs read action"
    }
}

fun checkIsEventDispatchThread() {
    check(ApplicationManager.getApplication().isDispatchThread) {
        "Needs to be on the Event Dispatch Thread (EDT)"
    }
}

fun fullyRefreshDirectory(directory: VirtualFile) {
    VfsUtil.markDirtyAndRefresh(/* async = */ false, /* recursive = */ true, /* reloadChildren = */ true, directory)
}

fun VirtualFile.findFileBreadthFirst(maxDepth: Int, predicate: (VirtualFile) -> Boolean): VirtualFile? {
    val queue = LinkedList<Pair<VirtualFile, Int>>()
        .also { it.push(this to 0) }

    loop@ while (queue.isNotEmpty()) {
        val (candidate, itemDepth) = queue.pop()
        when {
            predicate(candidate) -> return candidate
            itemDepth >= maxDepth -> continue@loop
            else -> queue.addAll(candidate.children.map { it to itemDepth + 1 })
        }
    }
    return null
}

val VirtualFile.pathAsPath: Path get() = Paths.get(path)
fun VirtualFile.pathRelative(project: Project): Path {
    val absPath = Paths.get(path)
    return absPath.relativeTo(Paths.get(project.basePath))
}

fun VirtualFile.toPsiFile(project: Project): PsiFile? =
    PsiManager.getInstance(project).findFile(this)


fun Element.toXmlString() =
    JDOMUtil.writeElement(this)

fun elementFromXmlString(xml: String): Element =
    // TODO(cies) Use JDOMUtil or JDK API (StAX) or XmlDomReader.readXmlAsModel instead (first decide which)
    SAXBuilder().build(xml.byteInputStream()).rootElement


/**
 * Unless you are absolutely certain that the file will only ever exist
 * on disk (and not in-memory when running tests), you should use [findFileByPathTestAware]
 * instead.
 */
fun LocalFileSystem.findFileByPath(path: Path): VirtualFile? {
    return findFileByPath(path.toString())
}

/**
 * Attempt to find a [VirtualFile] for [path].
 *
 * If running in unit test mode, try the in-memory VFS first.
 *
 * Background: most of our unit tests run in the "light" mode which uses in-memory VFS
 * (http://www.jetbrains.org/intellij/sdk/docs/basics/testing_plugins/light_and_heavy_tests.html).
 * But some things like `elm/core` and other package dependencies exist in a real filesystem
 * on disk, [LocalFileSystem].
 *
 * Whenever you find yourself calling [LocalFileSystem.findFileByPath], consider using this
 * function instead.
 */
fun findFileByPathTestAware(path: Path): VirtualFile? {
    val filePath = path.toString()

    // First, try whatever file system has the file in memory (typically used in unit tests)
    val inMemoryFile = VirtualFileManager.getInstance().findFileByUrl("temp://$filePath")
    if (inMemoryFile != null) {
        return inMemoryFile
    }

    // Fallback to local file system
    return LocalFileSystem.getInstance().findFileByPath(filePath)
}

// TODO [kl] Rethink these "testAware" functions.
//
// These functions are a hack to workaround a mixed VFS environment. The crux of the problem
// is that our ElmTestBase (non-workspace, non-"heavy" integration tests) use the in-mem,
// light VFS. But the package dependencies exist in the real, LocalFileSystem VFS at `~/.elm`.
// Maybe there's a better way?

fun refreshAndFindFileByPathTestAware(path: Path): VirtualFile? {
    val filePath = path.toString()
    val tempUrl = "temp://$filePath"

    if (isUnitTestMode) {
        val tempVFile = VirtualFileManager.getInstance().refreshAndFindFileByUrl(tempUrl)
        if (tempVFile != null) {
            return tempVFile
        }
    }

    return LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
}

val isUnitTestMode: Boolean get() = ApplicationManager.getApplication().isUnitTestMode

fun saveAllDocuments() = FileDocumentManager.getInstance().saveAllDocuments()
