package app.autohost.capabilities.screen

import android.content.Context
import android.os.PowerManager

/**
 * P4-10: wake-lock helper (plan §11.6 ScreenGuard). The guard holds a steady SCREEN_BRIGHT_WAKE_LOCK
 * with ACQUIRE_CAUSES_WAKEUP while enabled (the always-on recipe's "keep awake" fallback for devices
 * where Stay-awake is unavailable). Reference-counted and honest: [acquire] reports failure instead
 * of throwing.
 */
class WakeHelper(private val context: Context) {

    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("WakelockTimeout") // steady guard lock, released on disable/service destroy
    fun acquire(tag: String): Boolean {
        if (wakeLock?.isHeld == true) return true
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "autohost:screenguard_$tag",
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
            true
        }.getOrDefault(false)
    }

    fun release() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    fun isHeld(): Boolean = wakeLock?.isHeld == true
}
