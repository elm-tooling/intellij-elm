package org.elm.workspace.compiler

import com.intellij.openapi.vfs.VirtualFile
import junit.framework.TestCase
import org.elm.workspace.ElmWorkspaceTestBase
import org.elm.workspace.commandLineTools.makeProject
import org.elm.workspace.Version
import org.elm.workspace.elmToolchain
import org.elm.workspace.elmWorkspace
import org.intellij.lang.annotations.Language
import org.junit.Test
import java.nio.file.Path


class ElmBuildActionTest : ElmWorkspaceTestBase() {

    @Test
    fun `test build Elm application project`() {
        val source = """
                    module Main exposing (..)
                    import Html
                    main = Html.text "hi"
                """.trimIndent()

        buildProject {
            project("elm.json", manifestElm19(installedElmCompilerVersion))
            dir("src") {
                elm("Main.elm", source)
            }
        }
        val file = myFixture.configureFromTempProjectFile("src/Main.elm").virtualFile
        configureBuildTargets(file, ElmCompilerKind.ELM)
        doTest(file, expectedNumErrors = 0, expectedOffset = 0)
    }

    @Test
    fun `test build Lamdera application project`() {
        if (project.elmToolchain.lamderaCLI == null) return
        val frontend = """
                    module Frontend exposing (..)
                    app = 42
                """.trimIndent()
        val backend = """
                    module Backend exposing (..)
                    app = 42
                """.trimIndent()

        buildProject {
            project("elm.json", manifestLamdera101)
            dir("src") {
                elm("Frontend.elm", frontend)
                elm("Backend.elm", backend)
            }
        }
        val fileFrontend = myFixture.configureFromTempProjectFile("src/Frontend.elm").virtualFile
        val fileBackend = myFixture.configureFromTempProjectFile("src/Backend.elm").virtualFile
        configureBuildTargets(listOf(fileFrontend, fileBackend), ElmCompilerKind.LAMDERA)
        doTest(listOf(fileFrontend, fileBackend), expectedNumErrors = 0, expectedOffset = listOf(0, 0))
    }

    @Test
    fun `test build Elm application project with an error`() {
        val source = """
                    module Main exposing (..)
                    import Html
                    foo = bogus
                    main = Html.text "hi"
                """.trimIndent()

        buildProject {
            project("elm.json", manifestElm19(installedElmCompilerVersion))
            dir("src") {
                elm("Main.elm", source)
            }
        }
        val file = myFixture.configureFromTempProjectFile("src/Main.elm").virtualFile
        configureBuildTargets(file, ElmCompilerKind.ELM)
        doTest(file, expectedNumErrors = 1, expectedOffset = 0)
    }


    private fun doTest(file: VirtualFile, expectedNumErrors: Int, expectedOffset: Int) {
        var succeeded = false
        with(project.messageBus.connect(testRootDisposable)) {
            subscribe(ERRORS_TOPIC, object : ElmErrorsListener {
                override fun update(baseDirPath: Path, messages: List<ElmError>, targetPath: String, offset: Int) {
                    TestCase.assertEquals(expectedNumErrors, messages.size)
                    TestCase.assertEquals(file.path, targetPath)
                    TestCase.assertEquals(expectedOffset, offset)
                    succeeded = true
                }
            })
        }

        buildConfiguredTargets(file)
        assertTrue(succeeded)
    }

    private fun doTest(files: List<VirtualFile>, expectedNumErrors: Int, expectedOffset: List<Int>) {
        var succeeded = false
        val inputPaths = files.map { it.path }
        with(project.messageBus.connect(testRootDisposable)) {
            subscribe(ERRORS_TOPIC, object : ElmErrorsListener {
                override fun update(baseDirPath: Path, messages: List<ElmError>, targetPath: String, offset: Int) {
                    TestCase.assertEquals(expectedNumErrors, messages.size)
                    assertTrue(inputPaths.contains(targetPath))
                    assertTrue(expectedOffset.contains(offset))
                    succeeded = true
                }
            })
        }

        buildConfiguredTargets(files.first())
        assertTrue(succeeded)
    }

    /** Build every configured target, exactly as the tool window's build actions do. */
    private fun buildConfiguredTargets(currentFileInEditor: VirtualFile?) {
        val entryPoints = project.elmWorkspace.resolveBuildTargetsDetailed().mapNotNull { it.resolved }
        makeProject(project, entryPoints, currentFileInEditor)
    }

    private fun configureBuildTargets(file: VirtualFile, compilerKind: ElmCompilerKind) =
        configureBuildTargets(listOf(file), compilerKind)

    private fun configureBuildTargets(files: List<VirtualFile>, compilerKind: ElmCompilerKind) {
        val compilerPath = project.elmToolchain.compilerPath?.toString()
            ?: error("Compiler path is not configured in test toolchain")
        val targets = files.mapIndexed { index, file ->
            ElmBuildTargetConfig(
                name = "Target ${index + 1}",
                inputPath = file.path,
                outputPath = "",
                mode = ElmBuildMode.NONE,
                compilerKind = compilerKind,
                compilerPath = compilerPath,
                compileOnSave = true
            )
        }
        project.elmWorkspace.setBuildTargets(targets)
    }
}

// The Elm compiler refuses to compile an application whose `elm.json` declares a different
// `elm-version` than the compiler itself, so this manifest is parameterized by the installed
// compiler version (0.19.1, 0.19.2, ...) rather than hardcoding one.
@Language("JSON")
private fun manifestElm19(elmVersion: Version) = """
        {
            "type": "application",
            "source-directories": [
                "src"
            ],
            "elm-version": "$elmVersion",
            "dependencies": {
                "direct": {
                    "elm/core": "1.0.0",
                    "elm/html": "1.0.0",
                    "elm/json": "1.0.0",
                    "elm/time": "1.0.0"
                },
                "indirect": {
                    "elm/virtual-dom": "1.0.2"
                }
            },
            "test-dependencies": {
                "direct": {},
                "indirect": {}
            }
        }
        """.trimIndent()

@Language("JSON")
private val manifestLamdera101 = """
        {
            "type": "application",
            "source-directories": [
                "src"
            ],
            "elm-version": "0.19.1",
            "dependencies": {
                "direct": {
                    "elm/browser": "1.0.2",
                    "elm/core": "1.0.5",
                    "elm/html": "1.0.0",
                    "elm/url": "1.0.0",
                    "lamdera/codecs": "1.0.0",
                    "lamdera/core": "1.0.0"
                },
                "indirect": {
                    "elm/bytes": "1.0.8",
                    "elm/file": "1.0.5",
                    "elm/http": "2.0.0",
                    "elm/json": "1.1.4",
                    "elm/time": "1.0.0",
                    "elm/virtual-dom": "1.0.2"
                }
            },
            "test-dependencies": {
                "direct": {},
                "indirect": {}
            }
        }
        """.trimIndent()
