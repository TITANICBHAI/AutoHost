package app.autohost.runtime.js

/**
 * P11-01: pure engine-cap decisions (the JS runtime's `*Policy`). QuickJS runs **in the main
 * process** — there is no worker process to crash — so the engine gets hard caps up front and a
 * deadline that mirrors the run's `limits.timeoutMinutes`. All inputs are plain values so the
 * decisions stay JVM-testable (CONVENTIONS: pure policies hold decisions).
 */
object JsRunPolicy {

    /** Heap cap for the QuickJS runtime. A full heap aborts the script with an engine error. */
    const val DEFAULT_MEMORY_BYTES: Long = 32L * 1024 * 1024

    /** Low-RAM devices (≤3 GB class, plan §21.5) get the tighter cap. */
    const val LOW_RAM_MEMORY_BYTES: Long = 16L * 1024 * 1024

    /** JS stack cap — deep recursion aborts instead of taking the host process down. */
    const val STACK_BYTES: Long = 1L * 1024 * 1024

    fun caps(lowRam: Boolean, timeoutMinutes: Int): JsEngineCaps {
        val minutes = timeoutMinutes.coerceIn(1, 24 * 60)
        return JsEngineCaps(
            maxMemoryBytes = if (lowRam) LOW_RAM_MEMORY_BYTES else DEFAULT_MEMORY_BYTES,
            maxStackBytes = STACK_BYTES,
            deadlineMs = minutes * 60_000L,
        )
    }

    /**
     * The interrupt decision the engine polls between operations: cancellation always wins,
     * then the wall deadline (monotonic clock, immune to wall-clock changes).
     */
    fun shouldInterrupt(cancelled: Boolean, startedMonotonicMs: Long, nowMonotonicMs: Long, deadlineMs: Long): Boolean {
        if (cancelled) return true
        return nowMonotonicMs - startedMonotonicMs >= deadlineMs
    }

    /** Reason strings for interrupt outcomes — pinned so run outcomes stay stable. */
    const val REASON_CANCELLED = "USER_CANCELLED"
    const val REASON_DEADLINE = "RUN_TIMEOUT"
}

data class JsEngineCaps(
    val maxMemoryBytes: Long,
    val maxStackBytes: Long,
    val deadlineMs: Long,
)
