package app.autohost.capabilities.a11y

import app.autohost.core.common.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Every accessibility read runs through the gate (plan §5.7):
 *
 * - **single-flight per key** — concurrent reads for the same scope join one underlying read;
 * - **hard time budget** (default 2.5 s) — a read that does not finish in time yields the *last good
 *   snapshot* marked `degraded` (`degraded_reason`, `snapshot_age_ms`) instead of blocking the caller;
 * - **wedged reads are sacrificed, not cancelled** — the framework call is not cancellable, so the gate
 *   abandons the waiter at the budget; the read keeps running on the bounded read pool and its slot is
 *   reclaimed automatically when the read eventually completes.
 *
 * While a read is wedged, the map entry stays: further callers for the same key join it and degrade
 * immediately (their budget is already spent), and the pool-saturation check still sees the slot.
 */
class ReadGate(
    private val clock: Clock,
    val policy: ReadGatePolicy = ReadGatePolicy(),
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    class ReadFailed(val cause: Throwable) : Exception(cause)

    data class LastGood<S>(val snapshot: S, val atMonotonicMs: Long)

    sealed class ReadResult<S> {
        data class Fresh<S>(val snapshot: S) : ReadResult<S>()

        data class Degraded<S>(
            val snapshot: S?,
            val reason: ReadGatePolicy.Reason,
            val snapshotAgeMs: Long?,
            val detail: String,
        ) : ReadResult<S>()
    }

    private class InFlight(val deferred: Deferred<Any?>, val deadlineMonotonicMs: Long)

    private val inFlight = ConcurrentHashMap<String, InFlight>()
    private val lastGood = ConcurrentHashMap<String, LastGood<Any?>>()

    suspend fun <S : Any> read(key: String, read: () -> S): ReadResult<S> {
        val entry = inFlight[key] ?: start(key, read) ?: return degraded(
            key,
            ReadGatePolicy.Reason.POOL_SATURATED,
            "read pool saturated (${inFlight.size}/${policy.maxConcurrentReads})",
        )
        val remaining = entry.deadlineMonotonicMs - clock.monotonicMs()
        if (remaining <= 0) {
            return degraded(
                key,
                ReadGatePolicy.Reason.BUDGET_EXHAUSTED,
                "a read for this scope is wedged past its budget; its worker is sacrificed until it completes",
            )
        }
        val value = withTimeoutOrNull(remaining) {
            try {
                entry.deferred.await()
            } catch (e: ReadFailed) {
                throw e.cause
            }
        }
        return if (value != null) {
            lastGood[key] = LastGood(value, clock.monotonicMs())
            @Suppress("UNCHECKED_CAST")
            ReadResult.Fresh(value as S)
        } else {
            degraded(key, ReadGatePolicy.Reason.BUDGET_EXHAUSTED, "read did not finish within the budget")
        }
    }

    /** Starts a read on the read pool. Returns null when the pool is saturated. */
    private fun <S : Any> start(key: String, read: () -> S): InFlight? {
        synchronized(this) {
            inFlight[key]?.let { return it } // someone won the race; join their read
            if (inFlight.size >= policy.maxConcurrentReads) return null
            val deferred = CoroutineScope(SupervisorJob() + readDispatcher).async {
                try {
                    read()
                } catch (e: Exception) {
                    throw ReadFailed(e)
                }
            }
            val entry = InFlight(deferred, clock.monotonicMs() + policy.budgetMs)
            inFlight[key] = entry
            deferred.invokeOnCompletion { inFlight.remove(key, entry) } // slot reclaimed when the read settles
            return entry
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <S> degraded(key: String, reason: ReadGatePolicy.Reason, detail: String): ReadResult<S> {
        val lg = lastGood[key] as? LastGood<S>
        return ReadResult.Degraded(
            snapshot = lg?.snapshot,
            reason = reason,
            snapshotAgeMs = lg?.let { clock.monotonicMs() - it.atMonotonicMs },
            detail = detail,
        )
    }

    @Synchronized
    fun activeCount(): Int = inFlight.size

    @Synchronized
    fun clearHistory() = lastGood.clear()
}
