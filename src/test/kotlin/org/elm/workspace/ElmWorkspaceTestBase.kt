/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 *
 * Originally from intellij-rust
 */

package org.elm.workspace

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import org.elm.FileTreeBuilder
import org.elm.TestProject
import org.elm.fileTree
import java.util.concurrent.TimeUnit

/**
 * Base class for "heavy" integration tests such as those that depend on the Elm toolchain
 * or read Elm project files in order to create a "workspace".
 *
 * For normal tests, see [org.elm.lang.ElmTestBase]
 */
abstract class ElmWorkspaceTestBase : CodeInsightFixtureTestCase<ModuleFixtureBuilder<*>>() {

    protected var toolchain = ElmToolchain.BLANK
    private var originalToolchain = ElmToolchain.BLANK

    protected val elmWorkspaceDirectory: VirtualFile
        get() = myFixture.findFileInTempDir(".")

    protected fun awaitWorkspaceLoaded(retries: Int = 3, timeoutSeconds: Long = 30): Boolean {
        repeat(retries) {
            project.elmWorkspace.asyncDiscoverAndRefresh().get(timeoutSeconds, TimeUnit.SECONDS)
            if (project.elmWorkspace.allProjects.isNotEmpty()) return true
        }
        return false
    }

    fun buildProject(builder: FileTreeBuilder.() -> Unit): TestProject {
        val result = fileTree(builder).create(project, elmWorkspaceDirectory)
        if (awaitWorkspaceLoaded()) return result
        require(project.elmWorkspace.allProjects.isNotEmpty()) { "no Elm project was loaded" }
        return result
    }

    override fun setUp() {
        super.setUp()
        originalToolchain = project.elmToolchain
        toolchain = ElmToolchain.suggest(project)
        project.elmWorkspace.useToolchain(toolchain)
    }


/*
    override fun runTest() {
        if (!toolchain.looksLikeValidToolchain()) {
            System.err.println("SKIP $name: no Elm toolchain found")
            return
        }
        super.runTest()
    }
*/


    override fun tearDown() {
        project.elmWorkspace.useToolchain(originalToolchain)
        super.tearDown()
    }


    fun checkEquals(expected: Any, actual: Any) {
        if (expected != actual)
            failure(expected.toString(), actual.toString())
    }

    private fun failure(expected: String, actual: String): AssertionError {
        // IntelliJ will handle this output specially by showing a diff.
        throw AssertionError("\nExpected: $expected\n     but: was $actual")
    }
}
