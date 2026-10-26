package app.autohost.capabilities.notifications

/**
 * P8-01: pure parse of the notification-access setting (`Settings.Secure
 * .ENABLED_NOTIFICATION_LISTENERS`, shape `pkg/cls:pkg2/cls2`). The provider's availability and
 * the health check both go through this, so the JVM tests pin the exact string the system writes.
 */
object NotificationAccessPolicy {

    data class Component(val pkg: String, val cls: String)

    fun enabledComponents(setting: String?): List<Component> {
        if (setting.isNullOrBlank()) return emptyList()
        return setting.split(":")
            .mapNotNull { entry ->
                val parts = entry.trim().split("/")
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) null
                else Component(parts[0], parts[1])
            }
    }

    fun isEnabled(setting: String?, pkg: String, cls: String): Boolean =
        enabledComponents(setting).any { it.pkg == pkg && it.cls == cls }

    /** Convenience for the flattened class name (our listener is declared as `.AutoHostNotificationListener`). */
    fun isEnabledFor(setting: String?, pkg: String, simpleClassName: String): Boolean =
        enabledComponents(setting).any { it.pkg == pkg && it.cls.endsWith(simpleClassName) }
}
