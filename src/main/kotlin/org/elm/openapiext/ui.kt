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
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.event.DocumentEvent

class UiDebouncer(
        parentDisposable: Disposable,
        private val delayMillis: Int = 200
) {
    private val disposed = AtomicBoolean(false)
    private val pendingTask = AtomicReference<ScheduledFuture<*>?>()

    init {
        Disposer.register(parentDisposable) {
            disposed.set(true)
            pendingTask.getAndSet(null)?.cancel(false)
        }
    }

    /**
     * @param onUiThread: callback to be executed in EDT with **any** modality state.
     * Use it only for UI updates
     */
    fun <T> run(onPooledThread: () -> T, onUiThread: (T) -> Unit) {
        if (disposed.get()) return
        pendingTask.getAndSet(null)?.cancel(false)
        val task = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (disposed.get()) return@schedule
            val r = onPooledThread()
            ApplicationManager.getApplication().invokeLater({
                if (!disposed.get()) {
                    onUiThread(r)
                }
            }, ModalityState.any())
        }, delayMillis.toLong(), TimeUnit.MILLISECONDS)
        pendingTask.set(task)
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
        ComponentWithBrowseButton.BrowseFolderActionListener(
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
