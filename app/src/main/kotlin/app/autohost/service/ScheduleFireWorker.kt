package app.autohost.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.autohost.AutoHostApp

/**
 * P4-05: the WorkManager fire target (fallback backend). Resolves the graph and hands the fire
 * to the schedule controller; the reconcile worker still owns honesty about late delivery.
 */
class ScheduleFireWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val fireId = inputData.getString(KEY_FIRE_ID) ?: return Result.failure()
        val graph = (applicationContext as? AutoHostApp)?.graph ?: return Result.failure()
        graph.scheduleController.onFire(fireId)
        return Result.success()
    }

    companion object {
        const val KEY_FIRE_ID = "fire_id"
    }
}
