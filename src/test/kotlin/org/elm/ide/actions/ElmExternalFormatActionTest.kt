package org.elm.ide.actions

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.TestActionEvent
import junit.framework.TestCase
import org.elm.workspace.ElmWorkspaceTestBase
import org.intellij.lang.annotations.Language
import org.junit.Test

class ElmExternalFormatActionTest : ElmWorkspaceTestBase() {

/*
    override fun runTest() {
        if (toolchain.elmFormatCLI == null) {
            // TODO in the future maybe we should install elm-format in the CI build environment
            System.err.println("SKIP $name: elm-format not found")
            return
        }
        super.runTest()
    }
*/


    @Test
    fun `test elm-format action with elm 19`() {
        buildProject {
            project("elm.json", manifestElm19)
            dir("src") {
                elm("Main.elm", """
                    module Main exposing (f)


                    f x = x

                """.trimIndent())
            }
        }

        val file = myFixture.configureFromTempProjectFile("src/Main.elm").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        reformat(file)
        val expected = """
                    module Main exposing (f)


                    f x =
                        x

                """.trimIndent()
        TestCase.assertEquals(expected, document.text)
    }

    @Test
    fun `test elm-format action on a file with syntax errors`() {
        val originalCode = """
                    module Main exposing (f)


                    f x =
                """.trimIndent()


        buildProject {
            project("elm.json", manifestElm19)
            dir("src") {
                elm("Main.elm", originalCode)
            }
        }

        val file = myFixture.configureFromTempProjectFile("src/Main.elm").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!

        val ref = connectToBusAndGetNotificationRef()
        reformat(file)
        
        TestCase.assertEquals(originalCode, document.text)

        TestCase.assertEquals("elm-format encountered syntax errors that it could not fix", ref.get().content)
        TestCase.assertEquals(1, ref.get().actions.size)
        TestCase.assertEquals("Show Errors", ref.get().actions.first().templatePresentation.text)
    }

    @Test
    fun `test elm-format action shouldn't be active on non-elm files`() {
        buildProject {
            project("elm.json", manifestElm19.trimIndent())
            dir("src") {
                elm("Main.elm")
                file("foo.txt", "")
            }
        }

        val file = myFixture.configureFromTempProjectFile("src/foo.txt").virtualFile
        val (_, e) = makeTestAction(file)
        check(!e.presentation.isEnabledAndVisible) {
            "The elm-format action shouldn't be enabled in this context"
        }
    }

    @Test
    fun `test elm-format action should add to the undo stack`() {
        buildProject {
            project("elm.json", manifestElm19)
            dir("src") {
                elm("Main.elm", """
                    module Main exposing (f)


                    f x = x

                """.trimIndent())
            }
        }

        val file = myFixture.configureFromTempProjectFile("src/Main.elm").virtualFile
        reformat(file)

        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(file)

        val undoManager = UndoManager.getInstance(project)
        TestCase.assertTrue(undoManager.isUndoAvailable(fileEditor))
    }

    private fun reformat(file: VirtualFile) {
        val (action, event) = makeTestAction(file)
        check(event.presentation.isEnabledAndVisible) {
            "The elm-format action should be enabled in this context"
        }
        action.actionPerformed(event)
    }

    private fun makeTestAction(file: VirtualFile): Pair<ElmExternalFormatAction, TestActionEvent> {
        val dataContext = MapDataContext(mapOf(
                CommonDataKeys.PROJECT to project,
                CommonDataKeys.VIRTUAL_FILE to file,
                CommonDataKeys.EDITOR to editor
        ))
        val action = ElmExternalFormatAction()
        val event = TestActionEvent(dataContext, action)
        action.beforeActionPerformedUpdate(event)
        return Pair(action, event)
    }

    private fun connectToBusAndGetNotificationRef(): Ref<Notification> {
        val notificationRef = Ref<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) =
                    notificationRef.set(notification)
            })
        return notificationRef
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
                    "elm/json": "1.0.0"                
                },
                "indirect": {}
            },
            "test-dependencies": {
                "direct": {},
                "indirect": {}
            }
        }
        """.trimIndent()
