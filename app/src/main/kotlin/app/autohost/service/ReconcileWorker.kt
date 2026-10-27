package app.autohost.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.autohost.AutoHostApp
import java.util.concurrent.TimeUnit

/**
 * P4-06: the periodic reconcile worker (plan §11.3, ~20 min): verifies the next alarm exists,
 * detects missed fires beyond the grace window, applies the misfire policy, re-arms, and records
 * RECONCILED events. The same reconcile runs at app start, boot, package replace and
 * time/time-zone change (BootReceiver), so a deliberately deleted alarm repairs itself (G4-f).
 */
class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as? AutoHostApp)?.graph ?: return Result.failure()
        graph.scheduleController.reconcile(why = inputData.getString(KEY_WHY) ?: "periodic")
        // P8-03: the same periodic pass re-arms time-source trigger rules and refreshes the
        // event-hub FGS need (§11.8/§11.3).
        graph.triggerController.reconcile(why = inputData.getString(KEY_WHY) ?: "periodic")
        return Result.success()
    }

    companion object {
        const val KEY_WHY = "why"
        private const val PERIODIC_NAME = "autohost-schedule-reconcile"

        /** Idempotent; call at app start so the periodic pass always exists. */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReconcileWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_WHY to "periodic"))
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }

        const val PERIODIC_MINUTES = 20L
    }
}
