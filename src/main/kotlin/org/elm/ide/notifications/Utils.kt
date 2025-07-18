/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 *
 * Originally from intellij-rust
 */

package org.elm.ide.notifications

import com.intellij.notification.*
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.Project

private val pluginNotifications: NotificationGroup
    get() = NotificationGroupManager.getInstance().getNotificationGroup("Elm Plugin")

/**
 * Show a balloon notification along with action(s). The notification will be automatically dismissed
 * when an action is invoked.
 *
 * @param content The main content to be shown in the notification
 * @param type The notification type
 * @param actions Optional list of actions to be included in the notification
 */
fun Project.showBalloon(
        content: String,
        type: NotificationType,
        vararg actions: Pair<String, (() -> Unit)>
) {
    val notification = pluginNotifications.createNotification(content, type)
    actions.forEach { (title, fn) ->
        notification.addAction(
                NotificationAction.create(title) { _, notification ->
                    notification.hideBalloon()
                    fn()
                }
        )
    }
    Notifications.Bus.notify(notification, this)
}

fun executeAction(action: AnAction, place: String, dataContext: DataContext) {
    val event = AnActionEvent.createFromAnAction(action, null, place, dataContext)
    ActionUtil.performActionDumbAwareWithCallbacks(action, event)
}
