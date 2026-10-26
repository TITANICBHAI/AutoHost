package app.autohost.core.engine.schedule

import android.content.Context
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import app.autohost.core.common.Clock
import java.util.concurrent.TimeUnit

/**
 * P4-05: WorkManager backend — the fallback when exact alarms are unavailable/denied (plan §11.3).
 * Flex windows make timing approximate; the UI shows the persistent "timing may be late" badge
 * (G4-e). The worker class is supplied by `:app` (the engine stays app-agnostic); it enqueues
 * through the schedule controller.
 */
class WorkBackend(
    private val context: Context,
    private val workerClass: Class<out ListenableWorker>,
    private val clock: Clock,
) : SchedulerBackend {

    override val kind: String = "work"

    override fun arm(fireId: String, atMs: Long, allowWhileIdle: Boolean): ArmResult {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull()
            ?: return ArmResult.Refused("workmanager_unavailable")
        val delay = (atMs - clock.nowMs()).coerceAtLeast(0)
        val typed = OneTimeWorkRequest.Builder(workerClass)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_FIRE_ID to fireId))
            .addTag(TAG_PREFIX + fireId)
            .build()
        wm.cancelAllWorkByTag(TAG_PREFIX + fireId)
        wm.enqueue(typed)
        return ArmResult.Armed
    }

    override fun cancel(fireId: String) {
        runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(TAG_PREFIX + fireId) }
    }

    /** WorkManager is always "allowed" — it just may not be precise (badge covers honesty). */
    override fun canDeliverExact(): Boolean = false

    companion object {
        const val TAG_PREFIX = "autohost-fire-"
        const val KEY_FIRE_ID = "fire_id"
    }
}
