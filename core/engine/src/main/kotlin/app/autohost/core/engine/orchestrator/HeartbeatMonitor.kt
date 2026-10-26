package app.autohost.core.engine.orchestrator

/**
 * Pure heartbeat staleness decision (plan §5.2: heartbeats ≤ 2 s; > 10 s without one ⇒ INTERRUPTED).
 * Unit-tested on the JVM; the orchestrator only wires the clock and the store.
 */
object HeartbeatMonitor {
    const val EXPECTED_INTERVAL_MS = 2_000L
    const val STALE_THRESHOLD_MS = 10_000L

    fun isStale(lastHeartbeatMs: Long, nowMs: Long, thresholdMs: Long = STALE_THRESHOLD_MS): Boolean =
        nowMs - lastHeartbeatMs > thresholdMs
}
