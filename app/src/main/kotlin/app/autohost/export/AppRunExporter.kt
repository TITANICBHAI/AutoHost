package app.autohost.export

import app.autohost.core.common.AhJson
import app.autohost.core.engine.export.RunExporter
import app.autohost.core.engine.orchestrator.InMemoryRunStore
import app.autohost.core.engine.orchestrator.IncidentTracker
import app.autohost.core.engine.host.StateProvider
import app.autohost.core.broker.InMemoryAuditSink
import app.autohost.core.data.secrets.Redactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * P5-09: assembles the `.aarun` bundle for one run (plan §13.1). The pure zip builder lives in
 * `:core:engine` ([RunExporter]); this class gathers the inputs from the live store, the audit sink,
 * the incident tracker, the checkpoint store and the task repository — applying the [Redactor] to
 * every free-text field on the way out (Gate 5-d: the canary secret never appears in any output).
 *
 * Evidence files are included up to a hard cap ([MAX_EVIDENCE_FILES] × [MAX_EVIDENCE_BYTES]) so a
 * photo-heavy run cannot produce a gigabyte bundle.
 */
class AppRunExporter(
    private val runStore: InMemoryRunStore,
    private val auditSink: InMemoryAuditSink,
    private val incidents: IncidentTracker,
    private val stateProvider: StateProvider,
    private val redactor: Redactor,
    private val runRoot: File,
    private val manifestOf: (taskId: String) -> String?,
    private val environment: JsonObject,
    private val exporter: RunExporter = RunExporter(),
) {

    /** Builds the bundle, or null when the run is unknown. */
    fun export(runId: String): ByteArray? {
        val record = runStore.get(runId) ?: return null
        val events = runStore.snapshot(runId, limit = MAX_EVENTS)
        val eventsJsonl = events.joinToString("\n") { event ->
            redactor.redact(AhJson.json.encodeToString(app.autohost.core.model.BrokerEvent.serializer(), event))
        }
        val auditJsonl = auditSink.forRun(runId).takeLast(MAX_AUDIT).joinToString("\n") { entry ->
            redactor.redact(
                AhJson.json.encodeToString(
                    app.autohost.core.broker.AuditRecord.serializer(),
                    entry,
                ),
            )
        }
        val incidentsJson = AhJson.json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "incidents",
                    JsonArray(incidents.allOf(runId).map { incident ->
                        buildJsonObject {
                            put("capability", incident.capability)
                            put("method", incident.method)
                            put("stage", incident.stage)
                            incident.errorCode?.let { put("errorCode", it) }
                            put("openedAtMs", incident.openedAtMs)
                            put("lastSeenAtMs", incident.lastSeenAtMs)
                            incident.resolvedAtMs?.let { put("resolvedAtMs", it) }
                            put("open", incident.open)
                        }
                    }),
                )
            },
        )
        val checkpoint = stateProvider.checkpointOf(runId)
        val checkpointJson = checkpoint?.let {
            redactor.redact(AhJson.json.encodeToString(JsonObject.serializer(), redactor.redactJsonKeys(it)))
        }
        return exporter.build(
            RunExporter.Bundle(
                runId = runId,
                taskId = record.taskId,
                manifestJson = manifestOf(record.taskId)?.let { redactor.redact(it) },
                checkpointJson = checkpointJson,
                eventsJsonl = eventsJsonl,
                auditJsonl = auditJsonl,
                incidentsJson = incidentsJson,
                environmentJson = AhJson.json.encodeToString(JsonObject.serializer(), environment),
                evidence = collectEvidence(runId),
            ),
        )
    }

    private fun collectEvidence(runId: String): List<RunExporter.Bundle.EvidenceFile> {
        val dir = File(File(runRoot, runId), "evidence")
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return emptyList()
        val out = ArrayList<RunExporter.Bundle.EvidenceFile>()
        var budget = MAX_EVIDENCE_BYTES
        for (file in files) {
            if (out.size >= MAX_EVIDENCE_FILES || budget <= 0) break
            if (file.length() > budget) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            budget -= bytes.size
            out += RunExporter.Bundle.EvidenceFile(file.name, bytes)
        }
        return out
    }

    companion object {
        const val MAX_EVENTS = 2_000
        const val MAX_AUDIT = 500
        const val MAX_EVIDENCE_FILES = 20
        const val MAX_EVIDENCE_BYTES = 4L * 1024 * 1024
    }
}
