package nl.koenhendriks.claudecommit

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import nl.koenhendriks.claudecommit.settings.ClaudeCommitConfigurable

internal fun notify(project: Project, content: String, type: NotificationType, title: String = "") {
    val notification = NotificationGroupManager.getInstance()
        .getNotificationGroup("Commit Message for Claude Code")
        .createNotification(title, content, type)
    if (type == NotificationType.ERROR) {
        notification.addAction(NotificationAction.createSimple(ClaudeCommitBundle.message("notification.openSettings")) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeCommitConfigurable::class.java)
        })
    }
    notification.notify(project)
}
