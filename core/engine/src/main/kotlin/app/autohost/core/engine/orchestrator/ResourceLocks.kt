package app.autohost.core.engine.orchestrator

import app.autohost.core.common.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P4-08: resource locks and the pending queue (plan §11.4).
 *
 * - **Global UI lock**: only one UI-acting run at a time (manifest `ui_exclusive` or any run that
 *   touches `device.ui.act`).
 * - Per-package lock (per target app) and per-profile lock.
 * - Queue ordered by priority then due time; bounded; `lock_timeout` exceeded → the caller gets
 *   `SKIPPED:LOCK_TIMEOUT` (plan §12.1) instead of waiting forever.
 *
 * Pure JVM: no Android imports, fake-clock friendly (`Clock` supplies wait deadlines).
 */
class ResourceLocks(private val clock: Clock) {

    data class Request(
        val runId: String,
        val taskId: String,
        val profileId: String?,
        val uiActing: Boolean,
        val packages: Set<String> = emptySet(),
        val priority: Int = 0,
        val dueAtMs: Long,
        val lockTimeoutMs: Long = DEFAULT_LOCK_TIMEOUT_MS,
    )

    sealed interface Acquisition {
        /** All requested locks held; run may start (and must [release] in finally). */
        data class Granted(val handle: Handle) : Acquisition

        /** Queued behind a UI run; caller waits and retries [poll] (or gives up on timeout). */
        data class Queued(val position: Int) : Acquisition

        /** Waited longer than lock_timeout → SKIPPED:LOCK_TIMEOUT (plan §12.1). */
        data object Timeout : Acquisition
    }

    class Handle internal constructor(
        private val locks: ResourceLocks,
        internal val request: Request,
    ) {
        fun release() = locks.release(request)
    }

    private val mutex = Mutex()
    private var uiHolder: String? = null
    private val packageHolders = HashMap<String, String>()
    private val profileHolders = HashMap<String, String>()
    private val queue = ArrayDeque<Request>()

    /**
     * Try to take the locks a run needs. Non-blocking: callers either get a grant, a queue
     * position (retry via [poll]), or a timeout verdict computed from [Request.dueAtMs].
     */
    suspend fun acquire(request: Request): Acquisition = mutex.withLock {
        if (canGrant(request)) {
            grant(request)
        } else {
            val position = enqueue(request)
            if (clock.nowMs() - request.dueAtMs > request.lockTimeoutMs) {
                // waited past the lock timeout while queued: drop from queue, skip honestly
                queue.removeIf { it.runId == request.runId }
                Acquisition.Timeout
            } else {
                Acquisition.Queued(position)
            }
        }
    }

    /** Retry path for queued runs: returns a grant when the way is clear. */
    suspend fun poll(runId: String): Acquisition? = mutex.withLock {
        val index = queue.indexOfFirst { it.runId == runId }
        if (index < 0) return null
        val request = queue[index]
        if (canGrant(request)) {
            queue.removeAt(index)
            grant(request)
        } else {
            if (clock.nowMs() - request.dueAtMs > request.lockTimeoutMs) {
                queue.removeAt(index)
                Acquisition.Timeout
            } else {
                null // still waiting; position unchanged
            }
        }
    }

    /** Cancel a queued entry (run cancelled/superseded while waiting). */
    suspend fun dequeue(runId: String) = mutex.withLock {
        queue.removeIf { it.runId == runId }
        Unit
    }

    /** Snapshot for tests and diagnostics. */
    suspend fun snapshot(): Snapshot = mutex.withLock {
        Snapshot(uiHolder, packageHolders.toMap(), profileHolders.toMap(), queue.toList())
    }

    data class Snapshot(
        val uiHolder: String?,
        val packageHolders: Map<String, String>,
        val profileHolders: Map<String, String>,
        val queued: List<Request>,
    )

    private fun canGrant(request: Request): Boolean {
        if (request.uiActing && uiHolder != null) return false
        if (request.packages.any { packageHolders.containsKey(it) }) return false
        val profile = request.profileId ?: return true
        return !profileHolders.containsKey(profile)
    }

    private fun grant(request: Request): Acquisition.Granted {
        if (request.uiActing) uiHolder = request.runId
        request.packages.forEach { packageHolders[it] = request.runId }
        request.profileId?.let { profileHolders[it] = request.runId }
        return Acquisition.Granted(Handle(this, request))
    }

    /** Queue ordered by priority (desc) then due time (asc), bounded (oldest dropped first, §11.9). */
    private fun enqueue(request: Request): Int {
        queue.removeIf { it.runId == request.runId }
        val index = queue.indexOfFirst { it.priority < request.priority ||
            (it.priority == request.priority && it.dueAtMs > request.dueAtMs) }
        if (index >= 0) queue.add(index, request) else queue.addLast(request)
        while (queue.size > MAX_QUEUE) queue.removeFirst()
        return queue.indexOfFirst { it.runId == request.runId }
    }

    private fun release(request: Request) {
        // synchronous best effort; the orchestrator releases from a terminal transition
        if (uiHolder == request.runId) uiHolder = null
        packageHolders.entries.removeAll { it.value == request.runId }
        profileHolders.entries.removeAll { it.value == request.runId }
    }

    companion object {
        const val DEFAULT_LOCK_TIMEOUT_MS = 120_000L
        const val MAX_QUEUE = 32
    }
}
