package app.autohost.capabilities.shell

/**
 * P9-02: the Shizuku transport. Shizuku exposes adb/shell-privileged process execution through its
 * binder; the transport itself is pure (an [Exec] seam), while the Shizuku-API-backed
 * implementation (`ShizukuProcessExec`, the `dev.rikka.shizuku:api` dependency) lives in `:app`
 * wiring — this keeps the capability module free of the external dependency and the (compile-time)
 * API surface isolated to one file (ADR-064, first-build note in HANDOFF §4).
 *
 * Status is probed through the injected [Exec] (`pingBinder` + permission check), memoized with a
 * cooldown so availability polling stays cheap.
 */
class ShizukuTransport(private val exec: Exec) : ShellTransport {

    interface Exec {
        /** True when the Shizuku server binder is alive AND the app holds the permission. */
        fun isReady(): Boolean

        fun run(command: String, timeoutMs: Long): ShellResult
    }

    override val transportId: String = "shizuku"

    private val lock = Any()
    private var cached: Pair<Long, TransportStatus>? = null

    override fun describe(): String = "Shizuku (shell-privileged binder)"

    override fun status(): TransportStatus {
        val (at, value) = synchronized(lock) { cached } ?: Pair(Long.MIN_VALUE, TransportStatus.NeedsSetup("SHIZUKU_NOT_RUNNING"))
        if (System.nanoTime() / 1_000_000 - at < PROBE_COOLDOWN_MS) return value
        val probed = probe()
        synchronized(lock) { cached = Pair(System.nanoTime() / 1_000_000, probed) }
        return probed
    }

    override fun invalidateStatus() = synchronized(lock) { cached = null }

    private fun probe(): TransportStatus = try {
        if (exec.isReady()) TransportStatus.Live else TransportStatus.NeedsSetup("SHIZUKU_NOT_RUNNING")
    } catch (_: Exception) {
        TransportStatus.NeedsSetup("SHIZUKU_NOT_RUNNING")
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        if (!exec.isReady()) {
            throw ShellTransportException(ShellTransportException.Reason.NOT_CONNECTED, "Shizuku server is not running or not authorized")
        }
        return try {
            exec.run(command, timeoutMs)
        } catch (e: ShellTransportException) {
            throw e
        } catch (e: Exception) {
            throw ShellTransportException(ShellTransportException.Reason.DEVICE_CLOSED, "shizuku exec failed: ${e.message}")
        }
    }

    companion object {
        const val PROBE_COOLDOWN_MS: Long = 30_000
    }
}
