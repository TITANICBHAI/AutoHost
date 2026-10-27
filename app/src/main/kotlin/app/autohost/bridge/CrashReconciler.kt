package app.autohost.bridge

import app.autohost.AppGraph
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.model.RunStatus
import app.autohost.core.model.TaskManifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P5-05 (plan §12.4): crash and boot reconciliation. After process start every persisted row still in
 * STARTING/RUNNING/PAUSED/WAITING_USER is an orphan (no live worker can exist) → INTERRUPTED with
 * reason REBOOT_OR_CRASH; each orphan then goes through [ResumePolicy] — auto-resumed as a new
 * attempt (checkpoint restored from the row) when the manifest allows, otherwise left for the user.
 *
 * Runs after the scheduler reconcile in AppGraph's startup block; synchronous over runBlocking is
 * fine here (startup coroutine, DB-backed).
 */
class CrashReconciler(private val graph: AppGraph) {

    fun reconcile(why: String) {
        val orphans = runBlocking {
            val rows = mutableListOf<app.autohost.core.data.db.RunEntity>()
            for (status in ORPHAN_STATUSES) {
                rows += graph.data.runDao.byStatus(status)
            }
            rows
        }
        if (orphans.isEmpty()) return
        AhLog.i(TAG, "reconciling orphaned runs", buildJsonObject { put("why", why); put("count", orphans.size) })
        for (row in orphans) {
            runBlocking { handleOrphan(row, why) }
        }
    }

    private suspend fun handleOrphan(row: app.autohost.core.data.db.RunEntity, why: String) {
        // 1. mark INTERRUPTED — honest history, never an assumed success (plan §5.2)
        graph.data.runs.finish(row.runId, RunStatus.INTERRUPTED.name, "REBOOT_OR_CRASH")

        // 2. auto-resume decision (plan §12.3): bounded by the manifest's auto_resume/max_attempts
        val manifest = runCatching {
            AhJson.json.decodeFromString(
                TaskManifest.serializer(),
                graph.data.tasks.latestVersion(row.taskId)?.manifestJson ?: return,
            )
        }.getOrNull() ?: return
        val decision = app.autohost.core.engine.orchestrator.ResumePolicy.decide(
            manifest.execution,
            row.attempt,
        )
        val checkpoint: JsonObject? = row.checkpointJson?.let { text ->
            runCatching { AhJson.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        }
        when (decision) {
            is app.autohost.core.engine.orchestrator.ResumePolicy.Decision.Resume -> {
                AhLog.i(
                    TAG, "auto-resuming interrupted run",
                    buildJsonObject { put("run_id", row.runId); put("attempt", decision.attempt); put("why", why) },
                )
                // resumeThroughDb re-opens the run through the orchestrator with a fresh runtime start
                graph.resumeRunFromDb(row, manifest, decision.attempt, checkpoint)
            }
            is app.autohost.core.engine.orchestrator.ResumePolicy.Decision.Stop -> {
                AhLog.i(
                    TAG, "interrupted run left for the user",
                    buildJsonObject { put("run_id", row.runId); put("reason", decision.reason) },
                )
            }
        }
    }

    companion object {
        private const val TAG = "crash-reconcile"

        /** Everything that implies a live worker — impossible right after process start (plan §12.4). */
        val ORPHAN_STATUSES = listOf("STARTING", "RUNNING", "PAUSED", "WAITING_USER")
    }
}
