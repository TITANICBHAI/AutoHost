package app.autohost.core.engine.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P5-09 (plan §13.1): the run export bundle (`.aarun`). A plain zip — redacted events, audit trail,
 * incidents, stage records, the checkpoint (secrets stripped by the caller's [redact]), the manifest,
 * optional evidence files and environment info. A bundle alone must explain a failure (Gate 5-c), so
 * a human-readable README.txt walks the reader through the files.
 *
 * Pure JVM: bytes in, zip bytes out; redaction and DB/file access stay with the caller.
 */
class RunExporter(private val formatVersion: Int = 1) {

    data class Bundle(
        val runId: String,
        val taskId: String,
        val manifestJson: String?,
        val checkpointJson: String?,
        val eventsJsonl: String,
        val auditJsonl: String,
        val incidentsJson: String,
        val environmentJson: String,
        val evidence: List<EvidenceFile> = emptyList(),
    ) {
        data class EvidenceFile(val name: String, val bytes: ByteArray)
    }

    fun build(bundle: Bundle): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            put(zip, "bundle.json", """{"format":"aarun","version":$formatVersion,"runId":"${bundle.runId}","taskId":"${bundle.taskId}"}""")
            put(zip, "README.txt", readme(bundle))
            bundle.manifestJson?.let { put(zip, "manifest.json", it) }
            put(zip, "checkpoint.json", bundle.checkpointJson ?: "null")
            put(zip, "events.jsonl", bundle.eventsJsonl)
            put(zip, "audit.jsonl", bundle.auditJsonl)
            put(zip, "incidents.json", bundle.incidentsJson)
            put(zip, "environment.json", bundle.environmentJson)
            for (file in bundle.evidence) {
                putBytes(zip, "evidence/${file.name}", file.bytes)
            }
        }
        return out.toByteArray()
    }

    private fun readme(bundle: Bundle): String = """
        |AutoHost run bundle (format $formatVersion)
        |
        |run: ${bundle.runId}  task: ${bundle.taskId}
        |
        |Contents:
        |- manifest.json     the task manifest that produced this run
        |- events.jsonl      the redacted run events (logs, stages, progress, evidence refs) in order
        |- audit.jsonl       every broker call with capability, method, outcome and duration
        |- incidents.json    incidents opened by failed actions; resolved ones carry resolvedAtMs
        |- checkpoint.json   the last saved run checkpoint (secrets stripped; null when none)
        |- environment.json  device model, API level, host version, providers
        |- evidence/         screenshots and dumps referenced by the events (when retained)
        |
        |Secrets are redacted at capture time in the worker and again on the host; if a secret value
        |appears here anyway, it was never registered as a secret.
        |
        |Reading a failure: open events.jsonl, find the last stage_end with ok=false, then look up the
        |matching incident and the surrounding audit lines for the failing call.
    """.trimMargin() + "\nrun: ${bundle.runId}\n"

    private fun put(zip: ZipOutputStream, name: String, content: String) =
        putBytes(zip, name, content.encodeToByteArray())

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        ByteArrayInputStream(bytes).copyTo(zip)
        zip.closeEntry()
    }
}
