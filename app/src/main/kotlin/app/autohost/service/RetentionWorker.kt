package app.autohost.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.autohost.AutoHostApp
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.engine.orchestrator.RetentionPolicy
import app.autohost.core.engine.orchestrator.RetentionRules
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/**
 * P5-08: evidence + log retention (plan §13.1/§21). Daily worker: prunes run-events/audit/finished
 * rows older than the window through `data.retention.sweep`, then deletes run *directories*
 * (evidence, checkpoints) via the pure [RetentionPolicy] — age first, then count, then bytes.
 */
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as? AutoHostApp)?.graph ?: return Result.failure()
        val days = inputData.getInt(KEY_DAYS, DEFAULT_DAYS)
        sweep(graph, days)
        return Result.success()
    }

    companion object {
        private const val TAG = "retention"
        const val KEY_DAYS = "days"
        const val DEFAULT_DAYS = 14
        private const val PERIODIC_NAME = "autohost-retention"

        /** Idempotent; call at app start so the daily pass always exists. */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
                .setInputData(workDataOf(KEY_DAYS to DEFAULT_DAYS))
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }

        /** One synchronous sweep — also used by Settings and tests. Returns deleted run-dir count. */
        fun sweepNow(context: Context, days: Int = DEFAULT_DAYS): Int {
            val graph = (context as? AutoHostApp)?.graph ?: return 0
            return runBlocking { sweep(graph, days) }
        }

        private suspend fun sweep(graph: app.autohost.AppGraph, days: Int): Int {
            runCatching { graph.data.retention.sweep(days) }
                .onFailure { AhLog.w(TAG, "db sweep failed: ${it.message}") }
            var deleted = 0
            runCatching {
                val root = graph.runsRoot()
                val stats = RetentionPolicy.stats(root)
                val doomed = RetentionPolicy.selectForDeletion(
                    stats,
                    RetentionRules(maxAgeDays = days),
                    graph.clock.nowMs(),
                )
                deleted = RetentionPolicy.apply(stats, doomed)
                if (deleted > 0) {
                    AhLog.i(TAG, "retention removed run dirs", buildJsonObject { put("deleted", deleted) })
                }
            }.onFailure { AhLog.w(TAG, "dir sweep failed: ${it.message}") }
            return deleted
        }
    }
}
