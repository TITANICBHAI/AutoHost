package app.autohost.shell

import android.content.Context
import android.provider.Settings
import app.autohost.capabilities.shell.ShellCommands
import app.autohost.capabilities.shell.ShellTransports
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog

/**
 * P9-11: the one settings-write door (ADR-067, plan §3.4 — "one-time WRITE_SECURE_SETTINGS grant"; plan
 * §6.1 W4 write path). Two paths, tried in order:
 *
 *  1. **direct** — the app holds WRITE_SECURE_SETTINGS (the one-time `adb shell pm grant …` from
 *     the PC helper `autohost grant-secure-settings`): a plain `Settings.X.put…`;
 *  2. **shell** — no grant, but the shell tier is paired: `settings put <table> <key> <value>`
 *     through the first live transport.
 *
 * Everything that writes settings goes through here (the `device.settings` write seam, self-heal,
 * the keep-awake helper), so the audit trail is one place and G9-b evidence is reproducible.
 */
class SecureSettingsHelper(private val context: Context, private val transports: ShellTransports?) {

    sealed class Outcome {
        data class Applied(val path: String) : Outcome()
        data class Refused(val detail: String) : Outcome()
    }

    fun hasWriteSecureSettings(): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun write(table: String, key: String, value: String): Outcome {
        require(table in TABLES) { "table must be system|secure|global" }
        if (hasWriteSecureSettings()) {
            val ok = putDirect(table, key, value)
            if (ok) {
                log("written", key, "direct")
                return Outcome.Applied(PATH_DIRECT)
            }
            // fall through to the shell path before refusing
        }
        val live = transports?.firstLive()
        if (live != null) {
            return try {
                val result = live.exec(ShellCommands.settingsPut(table, key, value), SHELL_TIMEOUT_MS)
                val ok = result.exitCode == null || result.exitCode == 0
                if (ok) log("written", key, "shell") else log("refused", key, "shell exit=${result.exitCode}")
                if (ok) Outcome.Applied(PATH_SHELL) else Outcome.Refused("settings put exited ${result.exitCode}")
            } catch (e: Exception) {
                Outcome.Refused("shell write failed: ${e.message}")
            }
        }
        return Outcome.Refused(
            if (hasWriteSecureSettings()) "direct write failed and no shell transport is connected"
            else "WRITE_SECURE_SETTINGS not granted and no shell transport is connected",
        )
    }

    fun read(table: String, key: String): String? = try {
        when (table) {
            "secure" -> Settings.Secure.getString(context.contentResolver, key)
            "global" -> Settings.Global.getString(context.contentResolver, key)
            else -> Settings.System.getString(context.contentResolver, key)
        }
    } catch (_: Exception) {
        null
    }

    private fun putDirect(table: String, key: String, value: String): Boolean = runCatching {
        when (table) {
            "secure" -> Settings.Secure.putString(context.contentResolver, key, value)
            "global" -> Settings.Global.putString(context.contentResolver, key, value)
            else -> Settings.System.putString(context.contentResolver, key, value)
        }
        true
    }.getOrDefault(false)

    private fun log(event: String, key: String, path: String) {
        AhLog.i(TAG, "settings write $event", AhJson.meta {
            put("key", key)
            put("path", path)
        })
    }

    companion object {
        private const val TAG = "secure-settings"
        const val PATH_DIRECT = "write_secure"
        const val PATH_SHELL = "shell"
        val TABLES = setOf("system", "secure", "global")
        const val SHELL_TIMEOUT_MS = 10_000L
    }
}
