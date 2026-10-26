package app.autohost.core.engine.orchestrator

import app.autohost.core.broker.AuditRecord
import app.autohost.core.common.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * P5-07 (plan §12.5): incidents. A failed broker call opens an incident (capability, method, error
 * code, stage, evidence-free by design) that stays attached to the run view until a later *success in
 * the same stage* resolves it, or the run ends. History shows unresolved incidents first.
 *
 * Fed from the audit sink (which sees every broker call with its outcome); the stage comes from the
 * run record at the moment of the call. Pure JVM.
 */
data class Incident(
    val runId: String,
    val stage: String?,
    val capability: String,
    val method: String,
    val errorCode: String?,
    val openedAtMs: Long,
    val lastSeenAtMs: Long,
    val resolvedAtMs: Long? = null,
) {
    val open: Boolean get() = resolvedAtMs == null
    val key: String get() = "$capability.$method:$errorCode"
}

class IncidentTracker(private val clock: Clock) {

    private val byRun = ConcurrentHashMap<String, LinkedHashMap<String, Incident>>()

    /** Called for every audited broker call. Stage = the run's current stage (may be null). */
    fun onAudit(record: AuditRecord, stage: String?) {
        if (record.runId.isEmpty()) return
        val incidents = byRun.getOrPut(record.runId) { LinkedHashMap() }
        synchronized(incidents) {
            val existing = incidents[record.key(stage)]
            if (!record.ok) {
                if (existing != null && existing.open) {
                    incidents[record.key(stage)] = existing.copy(
                        lastSeenAtMs = record.tsMs,
                        stage = stage ?: existing.stage,
                    )
                } else {
                    incidents[record.key(stage)] = Incident(
                        runId = record.runId,
                        stage = stage,
                        capability = record.capability,
                        method = record.method,
                        errorCode = record.errorCode,
                        openedAtMs = record.tsMs,
                        lastSeenAtMs = record.tsMs,
                    )
                }
            } else if (existing != null && existing.open) {
                // a later success in the same stage resolves the incident (plan §12.5)
                incidents[record.key(stage)] = existing.copy(resolvedAtMs = clock.nowMs())
            }
        }
    }

    /** Unresolved incidents for the run view (History shows these first). */
    fun openOf(runId: String): List<Incident> = synchronized(byRun.getOrPut(runId) { LinkedHashMap() }) {
        byRun[runId]?.values?.filter { it.open }?.sortedBy { it.openedAtMs } ?: emptyList()
    }

    fun allOf(runId: String): List<Incident> = synchronized(byRun.getOrPut(runId) { LinkedHashMap() }) {
        byRun[runId]?.values?.toList() ?: emptyList()
    }

    /** Run ended: everything still open is closed as "run ended" (visible in the export). */
    fun closeRun(runId: String) {
        val incidents = byRun[runId] ?: return
        synchronized(incidents) {
            for ((key, incident) in incidents) {
                if (incident.open) {
                    incidents[key] = incident.copy(resolvedAtMs = clock.nowMs())
                }
            }
        }
    }

    fun clear() = byRun.clear()

    private fun AuditRecord.key(stage: String?): String = "${capability}.${method}:@${stage ?: "-"}:${errorCode ?: "ok"}"
}
