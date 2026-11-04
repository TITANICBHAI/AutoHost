package app.autohost.core.engine.orchestrator

/**
 * P15-06 (ADR-093): the adaptive worker-pool decision. The manifest declares four Python worker
 * processes (`:w0`–`:w3`); on a low-RAM device running all of them is wasteful. This pure
 * decision maps measured AVAILABLE RAM to the number of workers the orchestrator may start
 * concurrently. **No numbers are facts** — the thresholds below are placeholders until the
 * Phase 15 harness measures real devices (ADR-093: nothing is claimed before it is measured);
 * the wiring point is the orchestrator's start path, gated on `ActivityManager.memoryInfo`.
 *
 * The decision is deliberately conservative: the pool never grows past the manifest's four
 * workers and never drops below two (one UI-acting run plus one queued run must both fit).
 */
object WorkerPoolPlan {

    const val MAX_WORKERS = 4
    const val MIN_WORKERS = 2

    /** Placeholder thresholds (MB of available RAM) — replaced by P15-01's measured numbers. */
    const val HIGH_RAM_MB = 3_000
    const val MID_RAM_MB = 2_000

    fun workersFor(availableRamMb: Int): Int = when {
        availableRamMb < 0 -> MAX_WORKERS // unknown RAM: assume the full pool (fail open for capacity)
        availableRamMb >= HIGH_RAM_MB -> 4
        availableRamMb >= MID_RAM_MB -> 3
        else -> 2
    }.coerceIn(MIN_WORKERS, MAX_WORKERS)
}
