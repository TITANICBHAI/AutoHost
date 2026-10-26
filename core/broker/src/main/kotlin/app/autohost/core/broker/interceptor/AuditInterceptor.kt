package app.autohost.core.broker.interceptor

import app.autohost.core.broker.BrokerInterceptor
import app.autohost.core.broker.PipelineCall
import app.autohost.core.model.Envelope
import kotlinx.serialization.Serializable

/**
 * Audit — the outermost link. It runs the chain first, then records the outcome (code, duration) so the audit
 * trail sees the final result whatever the inner links decided. Records are best-effort: audit failures never
 * change the call's outcome.
 */
class AuditInterceptor(
    private val sink: AuditSink,
    private val clock: app.autohost.core.common.Clock,
) : BrokerInterceptor {

    override suspend fun intercept(call: PipelineCall, next: suspend (PipelineCall) -> Envelope): Envelope {
        val envelope = next(call)
        val ctx = call.context
        runCatching {
            sink.record(
                AuditRecord(
                    tsMs = clock.nowMs(),
                    runId = ctx?.runId ?: "",
                    taskId = ctx?.taskId ?: "",
                    capability = call.capability,
                    method = call.method,
                    ok = envelope.ok,
                    errorCode = envelope.error?.code,
                    durationMs = clock.monotonicMs() - call.startedMonotonicMs,
                ),
            )
        }
        return envelope
    }
}

@Serializable
data class AuditRecord(
    val tsMs: Long,
    val runId: String,
    val taskId: String,
    val capability: String,
    val method: String,
    val ok: Boolean,
    val errorCode: String?,
    val durationMs: Long,
)

fun interface AuditSink {
    fun record(entry: AuditRecord)
}

/** Ring-buffer sink for wave 1; Room-backed audit arrives with Phase 2. */
class InMemoryAuditSink(private val capacity: Int = 1_000) : AuditSink {
    private val entries = ArrayDeque<AuditRecord>(capacity)

    @Synchronized
    override fun record(entry: AuditRecord) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(): List<AuditRecord> = entries.toList()

    @Synchronized
    fun forRun(runId: String): List<AuditRecord> = entries.filter { it.runId == runId }

    @Synchronized
    fun clear() = entries.clear()
}
