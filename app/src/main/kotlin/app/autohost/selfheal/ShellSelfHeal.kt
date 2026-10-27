package app.autohost.selfheal

import android.content.ComponentName
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.shell.SecureSettingsHelper
import kotlinx.serialization.json.put

/**
 * P9-11: the shell-fallback half of self-heal (the P4-14 [SecureSettingsSelfHeal] keeps owning the
 * WRITE_SECURE-direct boot path). Where P4-14 heals only with the one-time grant, this class adds
 * the paths the shell tier unlocks (plan: "autohost grant-secure-settings helper → accessibility
 * self-heal, IME auto-select, keep-awake"):
 *
 *  - **accessibility self-heal without the grant** — the merged service string goes through
 *    `settings put secure` on the shell tier;
 *  - **IME auto-select** — `default_input_method` is written (and the previous IME returned so the
 *    caller can restore it), the supported path behind plan §6.5 "we select it once
 *    WRITE_SECURE_SETTINGS is granted" (or the shell equivalent);
 *  - **keep-awake** — `stay_on_while_plugged_in` is set while a task needs the screen awake and
 *    restored afterwards (the value is read before it is changed; the caller owns the restore).
 *
 * The protected keys ([app.autohost.core.engine.host.SettingsWritePolicy.DENIED_KEYS]) are written
 * HERE, deliberately: tasks can never touch them through `device.settings`, but self-heal may —
 * that asymmetry is the whole point of the denylist.
 */
class ShellSelfHeal(private val helper: SecureSettingsHelper) {

    /** Re-enables the AutoHost a11y service through the best available write path. */
    fun ensureAccessibility(why: String): Boolean {
        val service = ComponentName(
            "app.autohost",
            "app.autohost.capabilities.a11y.AutoHostAccessibilityService",
        )
        val flat = helper.read("secure", "enabled_accessibility_services") ?: ""
        val already = flat.split(':').any { entry ->
            val name = entry.substringAfter('/')
            name == service.className || name == ".${service.className.substringAfterLast('.')}"
        }
        if (already) return true
        val merged = (flat.split(':').filter { it.isNotBlank() } + service.flattenToString()).joinToString(":")
        val outcome = helper.write("secure", "enabled_accessibility_services", merged)
        if (outcome is SecureSettingsHelper.Outcome.Applied) {
            helper.write("secure", "accessibility_enabled", "1")
        }
        AhLog.i(TAG, "accessibility self-heal (shell tier)", AhJson.meta {
            put("why", why)
            put("ok", outcome is SecureSettingsHelper.Outcome.Applied)
        })
        return outcome is SecureSettingsHelper.Outcome.Applied
    }

    /**
     * Selects AutoHost's IME as the system input method. Returns the previous IME id (restore it
     * with [restoreIme]) or null when nothing changed / the write path is unavailable.
     */
    fun selectIme(ourImeId: String): String? {
        val previous = helper.read("secure", DEFAULT_INPUT_METHOD)
        if (previous == ourImeId) return null
        val outcome = helper.write("secure", DEFAULT_INPUT_METHOD, ourImeId)
        if (outcome !is SecureSettingsHelper.Outcome.Applied) {
            AhLog.w(TAG, "ime auto-select refused", AhJson.meta { put("detail", outcome.toString()) })
            return null
        }
        AhLog.i(TAG, "ime auto-selected", AhJson.meta { put("previous", previous) })
        return previous
    }

    /** Restores a previously-selected IME (best effort; used when a text-input stage ends). */
    fun restoreIme(previousImeId: String): Boolean =
        helper.write("secure", DEFAULT_INPUT_METHOD, previousImeId) is SecureSettingsHelper.Outcome.Applied

    /**
     * Keep-awake via `stay_on_while_plugged_in` (AC|USB|wireless = 7). Returns the previous value
     * so the caller can restore it; null when nothing was changed.
     */
    fun keepAwake(on: Boolean): String? {
        if (!on) return null // restore is explicit: keepAwakeRestore(previous)
        val previous = helper.read("global", STAY_ON_WHILE_PLUGGED_IN) ?: "0"
        val outcome = helper.write("global", STAY_ON_WHILE_PLUGGED_IN, "7")
        return if (outcome is SecureSettingsHelper.Outcome.Applied) previous else null
    }

    fun keepAwakeRestore(previous: String): Boolean =
        helper.write("global", STAY_ON_WHILE_PLUGGED_IN, previous) is SecureSettingsHelper.Outcome.Applied

    companion object {
        private const val TAG = "selfheal"
        const val DEFAULT_INPUT_METHOD = "default_input_method"
        const val STAY_ON_WHILE_PLUGGED_IN = "stay_on_while_plugged_in"
    }
}
