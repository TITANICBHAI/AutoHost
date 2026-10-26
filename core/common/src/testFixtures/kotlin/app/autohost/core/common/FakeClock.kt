package app.autohost.core.common

/**
 * Controllable clock for JVM tests. Wall and monotonic readings advance together via [advance] (or [advanceMs]),
 * and stay deterministic — never sleep, never drift. With [autoAdvancePerReadMs] every reading nudges the clock
 * forward (useful for poll loops whose deadlines must eventually be reached).
 */
class FakeClock(
    startMs: Long = 1_000_000L,
    private val autoAdvancePerReadMs: Long = 0,
) : Clock {
    private val lock = Any()
    private var current: Long = startMs

    val hooks = mutableListOf<(FakeClock) -> Unit>()

    override fun nowMs(): Long {
        synchronized(lock) {
            runHooks()
            if (autoAdvancePerReadMs > 0) current += autoAdvancePerReadMs
        }
        return synchronized(lock) { current }
    }

    override fun monotonicMs(): Long = nowMs()

    fun advance(deltaMs: Long) {
        synchronized(lock) {
            require(deltaMs >= 0) { "clocks never go backwards" }
            current += deltaMs
        }
    }

    /** Advance in steps so on_tick style hooks fire repeatedly. */
    fun advanceMs(deltaMs: Long, stepMs: Long = 50) {
        var remaining = deltaMs
        while (remaining > 0) {
            val step = minOf(stepMs, remaining)
            advance(step)
            remaining -= step
        }
    }

    /** Registers a hook called on every reading (used to model "sleep wakes the world"). */
    fun onRead(hook: (FakeClock) -> Unit) {
        hooks.add(hook)
    }

    private fun runHooks() {
        for (h in hooks.toList()) h(this)
    }
}
