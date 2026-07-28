package org.elm.ide.inspections

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import junit.framework.TestCase
import org.elm.workspace.ElmWorkspaceTestBase
import org.elm.workspace.Version
import org.elm.workspace.elmWorkspace
import org.elm.workspace.elmreview.ElmReviewError
import org.elm.workspace.elmreview.Location
import org.elm.workspace.elmreview.Region
import org.intellij.lang.annotations.Language
import org.junit.Test

class ElmReviewUtilsTest : ElmWorkspaceTestBase() {

    @Test
    fun `test builds highlight info for matching review error`() {
        val source = """
            module Main exposing (main)

            main = 1
        """.trimIndent()
        buildProject {
            project("elm.json", manifestElm19(installedElmCompilerVersion))
            dir("src") { elm("Main.elm", source) }
        }
        val file = myFixture.configureFromTempProjectFile("src/Main.elm").virtualFile
        val basePath = project.elmWorkspace.allProjects.single().projectDirPath
        val message = ElmReviewError(
            path = "src/Main.elm",
            rule = "NoUnused.Variables",
            message = "Unused variable",
            region = Region(Location(3, 1), Location(3, 5))
        )
        val result = ElmReviewResult(listOf(message), basePath, 0)

        val highlights = ReadAction.compute<List<Pair<PsiFile, HighlightInfo>>, Throwable> { highlightsForFile(project, basePath, result) }

        TestCase.assertEquals(1, highlights.size)
        TestCase.assertEquals(file.path, highlights[0].first.virtualFile.path)
        TestCase.assertEquals("Unused variable", highlights[0].second.description)
    }

    @Test
    fun `test drops review error when region is out of document bounds`() {
        val source = """
            module Main exposing (main)

            main = 1
        """.trimIndent()
        buildProject {
            project("elm.json", manifestElm19(installedElmCompilerVersion))
            dir("src") { elm("Main.elm", source) }
        }
        val basePath = project.elmWorkspace.allProjects.single().projectDirPath
        val message = ElmReviewError(
            path = "src/Main.elm",
            rule = "NoUnused.Variables",
            message = "Unused variable",
            region = Region(Location(999, 1), Location(999, 2))
        )
        val result = ElmReviewResult(listOf(message), basePath, 0)

        val highlights = ReadAction.compute<List<Pair<PsiFile, HighlightInfo>>, Throwable> { highlightsForFile(project, basePath, result) }

        assertTrue(highlights.isEmpty())
    }
}

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
