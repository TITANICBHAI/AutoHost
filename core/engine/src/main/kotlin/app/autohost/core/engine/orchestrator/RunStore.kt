package app.autohost.core.engine.orchestrator

import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.RunStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** One run as the host sees it. Persisted in Room from Phase 2; until then the store is authoritative. */
data class RunRecord(
    val runId: String,
    val taskId: String,
    val taskName: String,
    val profileId: String?,
    val status: RunStatus,
    val attempt: Int = 1,
    val triggerType: String = "manual",
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val stage: String? = null,
    val progress: Float? = null,
    val result: JsonObject? = null,
    val reason: String? = null,
    val message: String? = null,
) {
    val active: Boolean get() = status.active
}

/**
 * Run bookkeeping for UI + orchestrator. `events` is the live per-run log feed (replayed to new subscribers so
 * a Run screen joining late still sees the log).
 */
interface RunStore {
    val runs: StateFlow<List<RunRecord>>

    fun create(record: RunRecord)

    /** Applies [transform] through the state machine guard is the caller's duty; the store just persists. */
    fun update(runId: String, transform: (RunRecord) -> RunRecord): RunRecord?

    fun get(runId: String): RunRecord?

    fun appendEvent(runId: String, event: BrokerEvent)

    /** Live event stream for one run. */
    fun events(runId: String): SharedFlow<BrokerEvent>

    /** Bounded snapshot of the events so far (for export and initial render). */
    fun snapshot(runId: String, limit: Int = 500): List<BrokerEvent>

    fun clear()
}

class InMemoryRunStore : RunStore {
    private val _runs = MutableStateFlow<List<RunRecord>>(emptyList())
    override val runs: StateFlow<List<RunRecord>> = _runs.asStateFlow()

    private val events = ConcurrentHashMap<String, MutableSharedFlow<BrokerEvent>>()
    private val history = ConcurrentHashMap<String, ArrayDeque<BrokerEvent>>()
    private val lock = Any()

    override fun create(record: RunRecord) {
        synchronized(lock) { _runs.value = _runs.value + record }
    }

    override fun update(runId: String, transform: (RunRecord) -> RunRecord): RunRecord? {
        synchronized(lock) {
            val index = _runs.value.indexOfFirst { it.runId == runId }
            if (index < 0) return null
            val updated = transform(_runs.value[index])
            val copy = _runs.value.toMutableList()
            copy[index] = updated
            _runs.value = copy
            return updated
        }
    }

    override fun get(runId: String): RunRecord? = _runs.value.firstOrNull { it.runId == runId }

    override fun appendEvent(runId: String, event: BrokerEvent) {
        synchronized(lock) {
            val deque = history.getOrPut(runId) { ArrayDeque() }
            if (deque.size >= MAX_HISTORY) deque.removeFirst()
            deque.addLast(event)
        }
        shared(runId).tryEmit(event)
    }

    override fun events(runId: String): SharedFlow<BrokerEvent> = shared(runId)

    override fun snapshot(runId: String, limit: Int): List<BrokerEvent> = synchronized(lock) {
        history[runId]?.toList()?.takeLast(limit) ?: emptyList()
    }

    override fun clear() {
        synchronized(lock) {
            _runs.value = emptyList()
            history.clear()
        }
        events.clear()
    }

    private fun shared(runId: String): MutableSharedFlow<BrokerEvent> =
        events.getOrPut(runId) { MutableSharedFlow(replay = 100, extraBufferCapacity = 256) }

    companion object {
        const val MAX_HISTORY = 2_000
    }
}
