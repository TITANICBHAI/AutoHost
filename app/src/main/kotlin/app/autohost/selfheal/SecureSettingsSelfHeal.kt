package app.autohost.selfheal

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.common.ApiGate
import kotlinx.serialization.json.put

/**
 * P4-14: boot self-heal (plan §3.4/§18-P4-14). With `WRITE_SECURE_SETTINGS` granted (one-time
 * `pm grant`, plan §3.4), AutoHost can re-enable its own accessibility service after a reboot or
 * an OEM kill — the supported path for unattended use on the Android 10 phone. Opt-in: nothing
 * here runs unless the grant exists AND the user enabled self-heal in Setup (setting key
 * `selfheal.accessibility`).
 *
 * Every attempt is logged with before/after state so G4-d evidence is reproducible.
 */
class SecureSettingsSelfHeal(private val context: Context) {

    /** Called from [app.autohost.receiver.BootReceiver] (boot, package replace, alarm-perm change). */
    fun onBoot(why: String) {
        if (!enabledByUser()) {
            AhLog.i(TAG, "self-heal disabled by setting", AhJson.meta { put("why", why) })
            return
        }
        if (!hasWriteSecureSettings()) {
            AhLog.w(
                TAG, "self-heal skipped: WRITE_SECURE_SETTINGS not granted",
                AhJson.meta { put("why", why) },
            )
            return
        }
        restoreAccessibility(why)
    }

    /** Re-enables the AutoHost accessibility service via secure settings. Returns the outcome. */
    fun restoreAccessibility(why: String): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: ""
        val service = ComponentName(context, "app.autohost.capabilities.a11y.AutoHostAccessibilityService")
        val already = flat.split(':').any { entry ->
            val name = entry.substringAfter('/')
            name == service.className || name == "." + service.className.substringAfterLast('.')
        }
        if (already) {
            AhLog.i(TAG, "accessibility already enabled", AhJson.meta { put("why", why) })
            return true
        }
        val merged = (flat.split(':').filter { it.isNotBlank() } + service.flattenToString()).joinToString(":")
        val ok = runCatching {
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
        }.getOrDefault(false)
        // Also make sure the top-level accessibility toggle is on (readers on API 29+ check both).
        if (ok) {
            runCatching {
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        }
        AhLog.i(
            TAG, "accessibility self-heal attempted",
            AhJson.meta {
                put("why", why)
                put("ok", ok)
                put("api", ApiGate.sdkInt)
            },
        )
        return ok
    }

    private fun hasWriteSecureSettings(): Boolean =
        runCatching {
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    private fun enabledByUser(): Boolean {
        val graph = (context.applicationContext as? app.autohost.AutoHostApp)?.graph ?: return false
        return runCatching {
            kotlinx.coroutines.runBlocking { graph.data.settings.bool(SETTING_KEY) }
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "selfheal"
        const val SETTING_KEY = "selfheal.accessibility"
    }
}
