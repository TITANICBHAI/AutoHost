package app.autohost.install

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.autohost.capabilities.a11y.A11yBridge
import app.autohost.capabilities.a11y.AutoAcceptPolicy
import app.autohost.capabilities.a11y.SimpleA11yReader
import app.autohost.capabilities.app.AppManageProvider
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import java.io.File
import kotlinx.coroutines.delay

/**
 * P9-09: the installer-UI path of `device.app.manage.install` (mode "installer") — the a11y-driven
 * fallback for when the shell tier cannot reach `pm install` but the accessibility service is up.
 * The APK rides ACTION_VIEW through [FileProvider]; the pending
 * [AutoAcceptPolicy.Flow.INSTALL] lets the existing auto-accept gate tap the known installer
 * buttons (view-id + localized-label matching, cooldown, give-up — plan risk table "Auto-accept of
 * system dialogs"); the outcome is decided by watching the installer window disappear.
 *
 * Honest outcomes (ADR-069): `CONFIRMED` = the installer UI chain was observed completing (the
 * dialog appeared and the window left) — NOT a package-manager readback; `WAITING_USER` = no
 * dialog appeared in time (Play Protect, OEM variant, user present); `FAILED` = the APK file is
 * not readable by the app (shell-only paths like /data/local/tmp must use mode "pm").
 */
class InstallController(private val context: Context, private val clock: Clock) :
    AppManageProvider.InstallUi {

    override suspend fun installViaUi(apkDevicePath: String): String {
        val service = A11yBridge.service.value ?: return FAILED
        val file = File(apkDevicePath)
        if (!file.canRead() || !file.isFile) {
            AhLog.w(TAG, "installer path not readable by the app", AhJson.meta { put("path", apkDevicePath.take(120)) })
            return FAILED
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            return FAILED
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        service.autoAcceptGate.markPending(AutoAcceptPolicy.Flow.INSTALL)
        return try {
            service.startActivity(intent)
            awaitInstallerChain()
        } catch (e: Exception) {
            AhLog.w(TAG, "installer intent failed", AhJson.meta { put("detail", e.message ?: "?") })
            FAILED
        } finally {
            service.autoAcceptGate.clearPending()
        }
    }

    /** Polls for the installer window appearing and then leaving (the dialog chain completing). */
    private suspend fun awaitInstallerChain(): String {
        val reader = SimpleA11yReader({ A11yBridge.service.value }, clock)
        val started = clock.monotonicMs()
        val appearDeadline = started + APPEAR_TIMEOUT_MS
        val hardDeadline = started + HARD_TIMEOUT_MS
        var sawDialog = false
        while (clock.monotonicMs() < hardDeadline) {
            val pkg = try {
                reader.read(null).pkg
            } catch (_: Exception) {
                null
            }
            val isInstaller = pkg != null && INSTALLER_PACKAGES.any { pkg.startsWith(it) }
            if (isInstaller) {
                sawDialog = true
            } else if (sawDialog) {
                return CONFIRMED // the installer window appeared and has now left
            }
            if (!sawDialog && clock.monotonicMs() > appearDeadline) {
                return WAITING_USER // nothing we recognize appeared (Play Protect variant, user absent)
            }
            delay(POLL_MS)
        }
        return if (sawDialog) CONFIRMED else WAITING_USER
    }

    companion object {
        private const val TAG = "install"
        const val CONFIRMED = "CONFIRMED"
        const val WAITING_USER = "WAITING_USER"
        const val FAILED = "FAILED"
        const val APPEAR_TIMEOUT_MS = 6_000L
        const val HARD_TIMEOUT_MS = 90_000L
        const val POLL_MS = 400L

        /** Installer window packages across AOSP/OEM variants (ids live in PopupGuardPolicy). */
        val INSTALLER_PACKAGES = listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
        )
    }
}
