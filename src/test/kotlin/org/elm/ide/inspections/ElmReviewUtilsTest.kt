package org.elm.ide.inspections

import com.intellij.openapi.application.runReadAction
import junit.framework.TestCase
import org.elm.workspace.ElmWorkspaceTestBase
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
            project("elm.json", manifestElm19)
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

        val highlights = runReadAction { highlightsForFile(project, basePath, result) }

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
            project("elm.json", manifestElm19)
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

        val highlights = runReadAction { highlightsForFile(project, basePath, result) }

        TestCase.assertTrue(highlights.isEmpty())
    }
}

@Language("JSON")
private val manifestElm19 = """
        {
            "type": "application",
            "source-directories": [
                "src"
            ],
            "elm-version": "0.19.1",
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
