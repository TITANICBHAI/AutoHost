package app.autohost.capabilities.screen

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context

/**
 * P4-10: keyguard helpers (plan §11.6). `requestDismissKeyguard` (API 26+) clears non-secure
 * keyguards (swipe/None) and requires an Activity — the [ScreenGuardRecoveryActivity] calls it
 * from its own `onCreate`; secure locks report failure and the policy degrades. Kept apart from
 * the service so the reads stay testable and the dismiss stays honest.
 */
class KeyguardHelper(private val context: Context) {

    private val km get() = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    fun isLocked(): Boolean = runCatching { km?.isKeyguardLocked ?: false }.getOrDefault(false)

    fun isSecure(): Boolean = runCatching { km?.isDeviceSecure ?: false }.getOrDefault(false)

    fun unlocked(): Boolean = !isLocked()

    /**
     * Best-effort dismiss of a non-secure keyguard from the recovery activity; false when the
     * lock is secure (the caller degrades to WAITING_USER(unlock), §11.6).
     */
    fun dismissFrom(activity: Activity): Boolean = runCatching {
        val manager = km ?: return false
        if (manager.isDeviceSecure) return false
        if (!manager.isKeyguardLocked) return true
        manager.requestDismissKeyguard(activity, null)
        true
    }.getOrDefault(false)
}
