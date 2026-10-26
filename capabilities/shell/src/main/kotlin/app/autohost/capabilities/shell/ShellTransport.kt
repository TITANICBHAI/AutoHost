package app.autohost.capabilities.shell

/**
 * P9-01/P9-02: one execution path for shell commands. Plan §3.1 T3 — transports are tried in the
 * plan's order (ADB-over-TCP, Wireless debugging, Shizuku, root); the first one that reports live
 * availability wins. Implementations keep their own cheap status (memoized probe with a cooldown)
 * so `availability()` never performs I/O on the hot path.
 */
interface ShellTransport {
    val transportId: String

    /** Human-facing detail for Setup & Health (e.g. "127.0.0.1:5555", "shizuku binder", fingerprint). */
    fun describe(): String

    /** Cheap, memoized liveness/availability state (never throws). */
    fun status(): TransportStatus

    /** Forces a re-probe on the next [status] call (Setup screen refresh, post-reboot reminder). */
    fun invalidateStatus()

    /**
     * Runs one command. Expected failures throw [ShellTransportException] with a typed reason —
     * the provider maps them to broker errors; unexpected crashes are caught upstream.
     */
    fun exec(command: String, timeoutMs: Long): ShellResult
}

data class ShellResult(
    val stdout: String,
    val stderr: String,
    /** Real exit code (shell,v2), or null when the transport cannot know one. */
    val exitCode: Int?,
    val mode: String,
    /**
     * Raw stdout bytes (same content as [stdout] pre-decode). Binary producers like `screencap -p`
     * MUST be consumed through this — the String field is a UTF-8 decode and lossy for binary.
     */
    val stdoutBytes: ByteArray? = null,
)

sealed class TransportStatus {
    data object Live : TransportStatus()

    /** Reachable-but-not-authorized: the device is waiting for the user's "allow" tap. */
    data object AwaitingAuthorization : TransportStatus()

    data class NeedsSetup(val reason: String) : TransportStatus()

    data class Unsupported(val reason: String) : TransportStatus()
}

class ShellTransportException(val reason: Reason, detail: String) : Exception(detail) {
    enum class Reason { NOT_CONNECTED, TIMEOUT, DEVICE_CLOSED, UNSUPPORTED }
}

/** Ordered transport set — the fallback chain (plan §5.4: first available wins). */
class ShellTransports(private val ordered: List<ShellTransport>) {

    fun all(): List<ShellTransport> = ordered.toList()

    fun firstLive(): ShellTransport? = ordered.firstOrNull { it.status() is TransportStatus.Live }

    fun statuses(): List<Pair<ShellTransport, TransportStatus>> = ordered.map { it to it.status() }

    fun invalidateAll() = ordered.forEach { it.invalidateStatus() }
}
