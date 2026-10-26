package app.autohost.capabilities.notifications

import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * P8-01: the NotificationListenerService — the device-facing half of `device.notifications`
 * (plan §6.1). On connect it publishes the active snapshot through [NotificationBridge]; posted/
 * removed events flow into the bridge and from there into the EventHub (P8-02). The service
 * performs no filtering itself — scoping to granted packages happens in the provider and the
 * trigger controller, per plan §11.8 ("event delivery is scoped to what the grant names").
 */
class AutoHostNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationBridge.init(app.autohost.core.common.SystemClockImpl())
        NotificationBridge.onConnected(activeNotifications.orEmpty())
        // The listener is the only component allowed to press/dismiss; it publishes the handlers.
        AutoHostNotificationListenerHandlers.press = { key, index -> pressByKey(key, index) }
        AutoHostNotificationListenerHandlers.dismiss = { key -> dismissByKey(key) }
    }

    private fun pressByKey(key: String, index: Int): Boolean = try {
        val sbn = activeNotifications.orEmpty().firstOrNull { it.key == key } ?: return false
        val action = sbn.notification?.actions?.getOrNull(index) ?: return false
        action.actionIntent?.send() == true
    } catch (e: RuntimeException) {
        false
    }

    private fun dismissByKey(key: String): Boolean = try {
        cancelNotification(key)
        true
    } catch (e: RuntimeException) {
        false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationBridge.posted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationBridge.removed(sbn)
    }

    override fun onDestroy() {
        NotificationBridge.onDisconnected()
        super.onDestroy()
    }

    companion object {
        /** The component string of the listener for [NotificationAccessPolicy] checks. */
        fun componentClass(context: Context): String = "${context.packageName}.capabilities.notifications.AutoHostNotificationListener"

        /** Opens the system notification-access settings (the Fix action). */
        fun openAccessSettings(context: Context) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Process-local handler slots the provider calls through (plain volatiles, same pattern as A11yBridge). */
object AutoHostNotificationListenerHandlers {
    @Volatile
    var press: ((key: String, index: Int) -> Boolean)? = null

    @Volatile
    var dismiss: ((key: String) -> Boolean)? = null
}
