package app.autohost.security

import android.content.Context
import app.autohost.core.broker.security.InsecureFeatures
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-13: the volatile snapshot + persistence for the insecure-feature switches (plan §14.5).
 * Reads and writes the three app_settings keys through the SettingsRepository; the broker's
 * interceptor reads [state] — a plain volatile, so the call hot path never touches the DB.
 *
 * Every change is audited: an explicit WARN entry under the `security` tag with the feature and
 * the new value (switches are only reachable from the Settings screen; plan: every enable is
 * audited and shown on the task detail screen).
 */
class InsecureSwitchStore(private val context: Context) {

    @Volatile
    var state: InsecureFeatures.State = InsecureFeatures.State()
        private set

    private val data get() = app.autohost.AutoHostApp.of(context).graph.data

    /** Loads the persisted state; called at app start before the broker serves calls. */
    suspend fun refresh() {
        state = InsecureFeatures.parseState(
            relaxed = data.settings.bool(InsecureFeatures.SETTING_RELAXED),
            enabledJson = data.settings.string(InsecureFeatures.SETTING_ENABLED),
            deniedJson = data.settings.string(InsecureFeatures.SETTING_DENIED),
        )
        // P8: dev_api_remote is enforced at the Dev API layer (bind address) — keep it in sync.
        runCatching { app.autohost.AutoHostApp.of(context).graph.refreshDevApiPolicy() }
    }

    suspend fun setRelaxed(relaxed: Boolean) {
        data.settings.setBool(InsecureFeatures.SETTING_RELAXED, relaxed)
        refresh()
        audit("relaxed_mode", enabled = relaxed)
    }

    /** Turns one feature ON or OFF (off = back to the default-off state). */
    suspend fun setFeature(feature: String, enabled: Boolean) {
        require(feature in InsecureFeatures.KNOWN) { "unknown insecure feature '$feature'" }
        val enabledSet = state.enabled.toMutableSet()
        val deniedSet = state.denied.toMutableSet()
        if (enabled) {
            enabledSet += feature
            deniedSet -= feature
        } else {
            enabledSet -= feature
        }
        persist(enabledSet, deniedSet)
        refresh()
        audit(feature, enabled)
    }

    /** Explicit deny — wins over relaxed mode and over the enabled set (plan §14.5). */
    suspend fun setDenied(feature: String, denied: Boolean) {
        require(feature in InsecureFeatures.KNOWN) { "unknown insecure feature '$feature'" }
        val enabledSet = state.enabled.toMutableSet()
        val deniedSet = state.denied.toMutableSet()
        if (denied) {
            deniedSet += feature
            enabledSet -= feature
        } else {
            deniedSet -= feature
        }
        persist(enabledSet, deniedSet)
        refresh()
        audit(feature, enabled = !denied)
    }

    private suspend fun persist(enabledSet: Set<String>, deniedSet: Set<String>) {
        data.settings.setString(InsecureFeatures.SETTING_ENABLED, encode(enabledSet))
        data.settings.setString(InsecureFeatures.SETTING_DENIED, encode(deniedSet))
    }

    private fun encode(set: Set<String>): String =
        JsonArray(set.sorted().map { JsonPrimitive(it) }).toString()

    private suspend fun audit(feature: String, enabled: Boolean) {
        AhLog.w(
            "security",
            "insecure switch ${if (enabled) "enabled" else "disabled"}: $feature",
            AhJson.meta {
                put("feature", feature)
                put("by", "user")
                put("relaxed", state.relaxed)
                put("enabled", JsonArray(state.enabled.sorted().map { JsonPrimitive(it) }).toString())
                put("denied", JsonArray(state.denied.sorted().map { JsonPrimitive(it) }).toString())
            },
        )
    }
}
