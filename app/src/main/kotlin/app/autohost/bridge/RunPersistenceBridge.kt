package app.autohost.bridge

import app.autohost.core.common.AhLog
import app.autohost.core.data.db.EvidenceEntity
import app.autohost.core.data.db.RunEntity
import app.autohost.core.data.db.RunEventEntity
import app.autohost.core.data.db.StageRecordEntity
import app.autohost.core.data.repo.DataGraph
import app.autohost.core.engine.orchestrator.InMemoryRunStore
import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.model.BrokerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * P5-01/P5-18: mirrors the live run store into Room so History, the Dev API, run export and crash
 * reconciliation survive process death. Writes are append-mostly: one `runs` row per run (upserted on
 * every status change), `run_events` rows per event, `stage_records` per stage begin/end and
 * `evidence` rows per evidence event. Failures never break the run: persistence is best-effort.
 */
class RunPersistenceBridge(
    private val runStore: InMemoryRunStore,
    private val data: DataGraph,
    private val scope: CoroutineScope,
    private val checkpointSource: (runId: String) -> JsonObject?,
) {
    private val watched = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        scope.launch {
            runStore.runs.collect { runs ->
                for (run in runs) {
                    if (watched.add(run.runId)) watchEvents(run.runId)
                    mirrorRun(run)
                }
            }
        }
    }

    private fun watchEvents(runId: String) {
        scope.launch {
            var seq = runCatching { data.runs.maxSeq(runId) }.getOrDefault(0L)
            runStore.events(runId).collect { event ->
                seq += 1
                persistEvent(runId, seq, event)
            }
        }
    }

    private suspend fun persistEvent(runId: String, seq: Long, event: BrokerEvent) {
        runCatching {
            data.runs.appendEvent(
                RunEventEntity(
                    runId = runId,
                    seq = seq,
                    ts = event.ts,
                    level = event.level ?: "info",
                    stage = event.stage,
                    message = event.message ?: event.type,
                    dataJson = event.data?.toString(),
                ),
            )
            when (event.type) {
                "stage_begin" -> {
                    val name = event.stage ?: return
                    data.runs.recordStage(
                        StageRecordEntity(
                            runId = runId, name = name, status = "running",
                            attempts = event.data?.get("attempt")?.jsonPrimitive?.longOrNull?.toInt() ?: 1,
                            startedAt = event.ts,
                        ),
                    )
                }
                "stage_end" -> {
                    val name = event.stage ?: return
                    val ok = event.data?.get("ok")?.jsonPrimitive?.content != "false"
                    val previous = data.runs.stagesOf(runId).lastOrNull { it.name == name }
                    data.runs.recordStage(
                        StageRecordEntity(
                            rowId = previous?.rowId ?: 0,
                            runId = runId, name = name,
                            status = if (ok) "succeeded" else "failed",
                            attempts = previous?.attempts ?: 1,
                            startedAt = previous?.startedAt,
                            endedAt = event.ts,
                            errorJson = event.data?.get("error")?.jsonPrimitive?.content,
                            onResume = previous?.onResume,
                        ),
                    )
                }
                "evidence" -> {
                    val path = event.data?.get("path")?.jsonPrimitive?.content ?: return
                    data.runs.recordEvidence(
                        EvidenceEntity(
                            evidenceId = "$runId-${event.ts}-${path.hashCode()}",
                            runId = runId,
                            stage = event.stage,
                            type = event.data?.get("kind")?.jsonPrimitive?.content ?: "export",
                            path = path,
                            createdAt = event.ts,
                        ),
                    )
                }
            }
        }.onFailure { AhLog.w(TAG, "event persist failed: ${it.message}") }
    }

    private suspend fun mirrorRun(run: RunRecord) {
        runCatching {
            val existing = data.runs.byRunId(run.runId)
            data.runs.record(
                RunEntity(
                    rowId = existing?.rowId ?: 0,
                    runId = run.runId,
                    taskId = run.taskId,
                    profileId = run.profileId,
                    trigger = run.triggerType,
                    status = run.status.name,
                    reasonCode = run.reason,
                    attempt = run.attempt,
                    startedAt = run.startedAtMs,
                    endedAt = run.endedAtMs,
                    currentStage = run.stage,
                    progress = run.progress,
                    checkpointJson = checkpointSource(run.runId)?.toString(),
                    resultJson = run.result?.toString(),
                ),
            )
        }.onFailure { AhLog.w(TAG, "run persist failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "run-bridge"
    }
}
