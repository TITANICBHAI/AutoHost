package app.autohost.capabilities.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.autohost.core.common.ApiGate
import app.autohost.core.common.AhLog
import app.autohost.core.common.AhJson
import app.autohost.core.common.Clock
import app.autohost.core.common.SystemClockImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/**
 * P4-10: the ScreenGuard foreground service (plan §11.6). While enabled it:
 * - holds a steady screen wake lock (the always-on recipe's fallback), and
 * - re-evaluates the [ScreenGuardPolicy] every [RECHECK_MS] and on screen on/off / user-present
 *   broadcasts, and for `RECOVER` starts the transparent [ScreenGuardRecoveryActivity] guarded by
 *   a generation token + timeout; a secure lock degrades with `DEGRADED(SECURE_LOCK)` → the run
 *   surface shows WAITING_USER(unlock). Every state is surfaced, never silent (§11.6).
 *
 * Enabled state is one bit the app toggles (`AppGraph.screenGuardEnabled`); the service follows it.
 */
class ScreenGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var wake: WakeHelper
    private lateinit var keyguard: KeyguardHelper

    @Volatile
    private var lastAttemptAtMs: Long? = null

    @Volatile
    private var lastVerdict: ScreenGuardPolicy.Verdict? = null

    @Volatile
    private var generation: Int = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reevaluate("broadcast:${intent?.action ?: "unknown"}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        wake = WakeHelper(this)
        keyguard = KeyguardHelper(this)
        startForegroundCompat()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(screenReceiver, filter) }
        scope.launch {
            while (true) {
                delay(RECHECK_MS)
                reevaluate("periodic")
            }
        }
        AhLog.i(TAG, "screen guard service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        reevaluate("start")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        wake.release()
        scope.cancel()
        AhLog.i(TAG, "screen guard service stopped")
        super.onDestroy()
    }

    /** One policy pass; the verdict is applied and logged (visible and stoppable, §11.6). */
    private fun reevaluate(why: String) {
        val now = clock.nowMs()
        val input = ScreenGuardPolicy.Input(
            enabled = true, // the service only runs while enabled
            interactive = AndroidSystemState.isOn(),
            deviceLocked = keyguard.isLocked(),
            secureLock = keyguard.isSecure(),
            lastAttemptAtMs = lastAttemptAtMs,
            nowMs = now,
            lastVerdict = lastVerdict,
        )
        val verdict = ScreenGuardPolicy.decide(input)
        when (verdict) {
            ScreenGuardPolicy.Verdict.Noop -> {
                if (lastVerdict !is ScreenGuardPolicy.Verdict.Noop) {
                    AhLog.i(TAG, "screen clear", AhJson.meta { put("why", why) })
                }
            }
            ScreenGuardPolicy.Verdict.WakeOnly -> {
                wake.acquire("guard")
                lastAttemptAtMs = clock.nowMs()
            }
            ScreenGuardPolicy.Verdict.Recover -> {
                wake.acquire("guard")
                lastAttemptAtMs = clock.nowMs()
                generation += 1
                startRecovery(generation)
            }
            is ScreenGuardPolicy.Verdict.Degraded -> {
                // secure lock: WAITING_USER(unlock) is raised by the run surface; never silent
                AhLog.w(TAG, "screen guard degraded", AhJson.meta {
                    put("reason", verdict.reason); put("why", why)
                })
            }
        }
        lastVerdict = verdict
    }

    private fun startRecovery(token: Int) {
        val intent = Intent(this, ScreenGuardRecoveryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ScreenGuardRecoveryActivity.EXTRA_GENERATION, token)
        runCatching { startActivity(intent) }
            .onFailure { AhLog.w(TAG, "recovery activity refused", AhJson.meta { put("error", it.message ?: "?") }) }
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen guard", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("AutoHost screen guard")
                .setContentText("Keeping the screen available for scheduled tasks")
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("AutoHost screen guard")
                .setContentText("Keeping the screen available for scheduled tasks")
                .setOngoing(true)
                .build()
        }
        if (ApiGate.sdkInt >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "screenguard"
        private const val CHANNEL_ID = "screen_guard"
        private const val NOTIFICATION_ID = 41
        const val RECHECK_MS = 30_000L
        const val ACTION_DISABLE = "app.autohost.action.SCREEN_GUARD_DISABLE"

        /** The clock is installed once from AppGraph (AGENTS.md §4 — never System.currentTimeMillis). */
        @Volatile
        var clock: Clock = SystemClockImpl()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ScreenGuardService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenGuardService::class.java))
        }
    }
}
