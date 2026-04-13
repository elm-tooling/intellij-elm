package org.elm.ide.statusbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class ElmTaskStatusService(private val project: Project) {

    data class State(
        val compilerRunning: Int = 0,
        val reviewRunning: Int = 0
    ) {
        val isCompilerRunning: Boolean get() = compilerRunning > 0
        val isReviewRunning: Boolean get() = reviewRunning > 0
        val isRunning: Boolean get() = isCompilerRunning || isReviewRunning
    }

    interface Listener {
        fun statusChanged(state: State)
    }

    companion object {
        val TOPIC = Topic("Elm task status updates", Listener::class.java)
    }

    private val compilerCounter = AtomicInteger(0)
    private val reviewCounter = AtomicInteger(0)

    fun currentState(): State = State(compilerCounter.get(), reviewCounter.get())

    fun compilerStarted() {
        compilerCounter.incrementAndGet()
        publish()
    }

    fun compilerFinished() {
        decrementAtFloorZero(compilerCounter)
        publish()
    }

    fun reviewStarted() {
        reviewCounter.incrementAndGet()
        publish()
    }

    fun reviewFinished() {
        decrementAtFloorZero(reviewCounter)
        publish()
    }

    private fun publish() {
        if (project.isDisposed) return
        val state = currentState()
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            project.messageBus.syncPublisher(TOPIC).statusChanged(state)
        } else {
            app.invokeLater {
                if (!project.isDisposed) {
                    project.messageBus.syncPublisher(TOPIC).statusChanged(state)
                }
            }
        }
    }

    private fun decrementAtFloorZero(counter: AtomicInteger) {
        while (true) {
            val current = counter.get()
            if (current <= 0) return
            if (counter.compareAndSet(current, current - 1)) return
        }
    }
}

val Project.elmTaskStatus: ElmTaskStatusService
    get() = service()
