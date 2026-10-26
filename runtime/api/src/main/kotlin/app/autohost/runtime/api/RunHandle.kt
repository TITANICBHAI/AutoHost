package app.autohost.runtime.api

/**
 * Control surface for one started run. All methods are safe to call from any thread and safe to call after the
 * run finished (they become no-ops). `kill` is the last resort after cancel did not stop the worker in time.
 */
interface RunHandle {
    val runId: String

    /** Cooperative: takes effect at the next SDK call / sleep inside the task. */
    fun requestCancel()

    fun requestPause()

    fun requestResume()

    /** Forceful: stop the worker regardless of what it is doing. */
    fun kill()

    /** Release host-side resources (unbind services, clear caches). The worker still self-recycles. */
    fun release()
}
