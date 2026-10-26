package app.autohost.capabilities.intents

import kotlinx.serialization.json.JsonObject

/**
 * P8-06: the intent allowlist policy (plan §6.1 `device.intent` — "Allowlist of actions/packages
 * in the grant"). Fail-closed by design:
 *
 *  - `open_url` accepts only http/https data (the OS app-chooser makes it visible, not stealth).
 *  - `open_settings` accepts only the known Settings page names mapped below.
 *  - `start_activity` needs EITHER an explicit action in the grant's `scope.actions` OR a
 *    component package in `scope.packages`; a VIEW with http(s) data is additionally allowed.
 *  - `broadcast` needs the action in `scope.actions` — no grant, no broadcast.
 *
 * The policy is pure; the provider applies it before any framework call.
 */
object IntentPolicy {

    data class Scope(val actions: Set<String> = emptySet(), val packages: Set<String> = emptySet())

    fun parseScope(scopeJson: JsonObject?): Scope {
        if (scopeJson == null) return Scope()
        val actions = (scopeJson["actions"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.toSet()
            ?: emptySet()
        val packages = (scopeJson["packages"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.toSet()
            ?: emptySet()
        return Scope(actions, packages)
    }

    sealed class Verdict {
        data object Allow : Verdict()
        data class Deny(val reason: String) : Verdict()
    }

    /** Known Settings pages (name → action); anything else needs an explicit action grant. */
    val SETTINGS_PAGES: Map<String, String> = mapOf(
        "wifi" to android.provider.Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to android.provider.Settings.ACTION_SOUND_SETTINGS,
        "applications" to android.provider.Settings.ACTION_APPLICATION_SETTINGS,
        "battery" to android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "date" to android.provider.Settings.ACTION_DATE_SETTINGS,
        "locale" to android.provider.Settings.ACTION_LOCALE_SETTINGS,
        "input" to android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS,
        "accessibility" to android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "notification_listener" to android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
        "exact_alarms" to "android.settings.SCHEDULE_EXACT_ALARM",
        "app_details" to android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "settings" to android.provider.Settings.ACTION_SETTINGS,
    )

    fun checkUrl(url: String): Verdict {
        val scheme = url.substringBefore("://", "").lowercase()
        return if (scheme == "http" || scheme == "https") {
            Verdict.Allow
        } else {
            Verdict.Deny("only http/https URLs are allowed (got '$scheme')")
        }
    }

    fun checkSettingsPage(page: String): Verdict =
        if (page in SETTINGS_PAGES) Verdict.Allow else Verdict.Deny("unknown settings page '$page'")

    fun checkActivity(action: String?, componentPkg: String?, dataUri: String?, scope: Scope): Verdict {
        // Explicit component: the package must be in the grant.
        if (componentPkg != null) {
            return if (componentPkg in scope.packages) {
                Verdict.Allow
            } else {
                Verdict.Deny("package '$componentPkg' is not in the grant's packages scope")
            }
        }
        // Implicit action: must be granted; http/https VIEW is the one built-in convenience.
        if (action != null) {
            if (action in scope.actions) return Verdict.Allow
            val data = dataUri?.substringBefore(":", "")?.lowercase()
            if (action == android.content.Intent.ACTION_VIEW && (data == "http" || data == "https")) {
                return Verdict.Allow
            }
            return Verdict.Deny("action '$action' is not in the grant's actions scope")
        }
        return Verdict.Deny("start_activity needs an action or a component in the grant")
    }

    fun checkBroadcast(action: String?, scope: Scope): Verdict {
        if (action == null) return Verdict.Deny("broadcast needs an action")
        return if (action in scope.actions) {
            Verdict.Allow
        } else {
            Verdict.Deny("action '$action' is not in the grant's actions scope")
        }
    }
}
