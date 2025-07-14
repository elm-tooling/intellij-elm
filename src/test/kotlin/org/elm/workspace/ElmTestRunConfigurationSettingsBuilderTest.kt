package org.elm.workspace

import org.elm.FileTree
import org.elm.ide.test.run.ElmTestRunConfigurationSettingsBuilder
import org.elm.fileTree
import org.elm.ide.test.run.ElmTestRunConfiguration

class ElmTestRunConfigurationSettingsBuilderTest : ElmWorkspaceTestBase() {
    fun `test createAndRegisterFromElement with test file`() {
        val projectStructure = createProjectStructure()

        val testProject = projectStructure.create(project, elmWorkspaceDirectory)
        val psiElement = testProject.psiFile("tests/ExampleTest.elm")

        val configSettings = ElmTestRunConfigurationSettingsBuilder.createAndRegisterFromElement(psiElement)

        assertNotNull(configSettings)
        val config = configSettings.configuration as ElmTestRunConfiguration

        assertEquals(true, config.options.filteredTestConfig?.filePath?.contains("tests/ExampleTest.elm"))
        assertEquals(false, config.options.filteredTestConfig?.testIsDirectory)
        assertEquals(null, config.options.filteredTestConfig?.filter)
        assertEquals(true, config.options.filteredTestConfig?.runnableFilePath()?.contains("tests/ExampleTest.elm"))
    }

    fun `test createAndRegisterFromElement with filter`() {
        val projectStructure = createProjectStructure()

        val testProject = projectStructure.create(project, elmWorkspaceDirectory)
        val psiElement = testProject.psiFile("tests/ExampleTest.elm")

        val configSettings = ElmTestRunConfigurationSettingsBuilder.createAndRegisterFromElement(psiElement, "my test")

        assertNotNull(configSettings)
        val config = configSettings.configuration as ElmTestRunConfiguration

        assertEquals(true, config.options.filteredTestConfig?.filePath?.contains("tests/ExampleTest.elm"))
        assertEquals(false, config.options.filteredTestConfig?.testIsDirectory)
        assertEquals("my test", config.options.filteredTestConfig?.filter)
        assertEquals(true, config.options.filteredTestConfig?.runnableFilePath()?.contains("tests/ExampleTest.elm"))
    }

    private fun createProjectStructure(): FileTree {
        return fileTree {
            dir("tests") {
                elm(
                    "ExampleTest.elm", """
                module ExampleTest exposing (..)
                import Test exposing (test)
                foo = test "my test" <| \_ -> Expect.equal 1 1
                bar = test "my second test" <| \_ -> Expect.equal 1 1
                --^
            """.trimIndent()
                )
            }
        }
    }
}