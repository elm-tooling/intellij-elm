/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 *
 * Originally from intellij-rust
 */

package org.elm.openapiext

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.ComponentWithBrowseButton
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.util.Alarm
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

class UiDebouncer(
        private val parentDisposable: Disposable,
        private val delayMillis: Int = 200
) {
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parentDisposable)

    /**
     * @param onUiThread: callback to be executed in EDT with **any** modality state.
     * Use it only for UI updates
     */
    @Suppress("DEPRECATION")
    fun <T> run(onPooledThread: () -> T, onUiThread: (T) -> Unit) {
        if (Disposer.isDisposed(parentDisposable)) return
        alarm.cancelAllRequests()
        alarm.addRequest({
            val r = onPooledThread()
            ApplicationManager.getApplication().invokeLater({
                if (!Disposer.isDisposed(parentDisposable)) {
                    onUiThread(r)
                }
            }, ModalityState.any())
        }, delayMillis)
    }
}


fun fileSystemPathTextField(
        disposable: Disposable,
        title: String,
        fileDescriptor: FileChooserDescriptor,
        onTextChanged: () -> Unit = {}
): TextFieldWithBrowseButton {

    val component = TextFieldWithBrowseButton(null, disposable)
    val descriptor = fileDescriptor.withTitle(title)
    component.addActionListener(
        ComponentWithBrowseButton.BrowseFolderActionListener<JTextField>(
            component,
            null,
            descriptor,
            TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT
        )
    )
    FileChooserFactory.getInstance().installFileCompletion(component.textField, descriptor, true, disposable)
    component.childComponent.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
            onTextChanged()
        }
    })

    return component
}
