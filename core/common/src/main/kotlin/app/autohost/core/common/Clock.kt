package app.autohost.core.common

/**
 * Time source. The only sanctioned way to read time (AGENTS.md §4: never `System.currentTimeMillis()` directly).
 * [nowMs] is wall-clock epoch millis (UTC); [monotonicMs] never goes backwards and is immune to wall-clock changes,
 * so durations, heartbeats and TTLs use [monotonicMs].
 */
interface Clock {
    fun nowMs(): Long
    fun monotonicMs(): Long
}

/** Pure-JVM default: both readings come from the wall clock. The app installs an Android monotonic variant. */
open class SystemClockImpl : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun monotonicMs(): Long = System.currentTimeMillis()
}
