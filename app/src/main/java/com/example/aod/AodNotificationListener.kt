package com.example.aod

import android.content.Context
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Gives access to active notifications and media sessions. */
class AodNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: AodNotificationListener? = null
        var onChange: (() -> Unit)? = null

        fun activeIcons(context: Context): List<Drawable> {
            val svc = instance ?: return emptyList()
            return runCatching { svc.activeNotifications }.getOrNull().orEmpty()
                .filter { it.isClearable && !it.isOngoing && it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .mapNotNull { it.notification.smallIcon?.loadDrawable(context)?.mutate() }
        }
    }

    override fun onListenerConnected() { instance = this; onChange?.invoke() }
    override fun onListenerDisconnected() { instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification) { onChange?.invoke() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { onChange?.invoke() }
}
