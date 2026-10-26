package app.autohost.capabilities.screen

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import kotlinx.serialization.json.put

/**
 * P4-10: the transparent recovery activity (plan §11.6). Started by the guard service for
 * `RECOVER`: `showWhenLocked` + `turnScreenOn` (manifest), then `requestDismissKeyguard` for
 * non-secure keyguards. Guarded by a generation token (stale launches no-op) and a timeout that
 * finishes the activity so it never lingers over the user's screen.
 */
class ScreenGuardRecoveryActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var generation = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        generation = intent?.getIntExtra(EXTRA_GENERATION, -1) ?: -1
        if (generation < 0) {
            finish()
            return
        }
        val keyguard = KeyguardHelper(this)
        val dismissed = keyguard.dismissFrom(this)
        AhLog.i(
            TAG, "recovery attempt",
            AhJson.meta {
                put("generation", generation)
                put("secure", keyguard.isSecure())
                put("dismissed", dismissed)
            },
        )
        handler.postDelayed({ finish() }, TIMEOUT_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "screenguard"
        const val EXTRA_GENERATION = "generation"
        const val TIMEOUT_MS = 5_000L
    }
}
