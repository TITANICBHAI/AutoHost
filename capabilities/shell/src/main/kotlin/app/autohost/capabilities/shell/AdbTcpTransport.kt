package app.autohost.capabilities.shell

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * P9-02 (plan §3.1 T3, the Android 10 phone's path): ADB-over-TCP to `127.0.0.1:5555` (or any
 * host:port a PC set up with `adb tcpip 5555`, or an Android 11+ wireless-debugging IP). One
 * fresh connection per exec — stateless and simple; the memoized status probe reconnects at most
 * once per [PROBE_COOLDOWN_MS].
 *
 * VERIFIED-path status: this transport is the one Gate-9 exercises on the Android 10 phone.
 */
class AdbTcpTransport(
    private val host: String,
    private val port: Int,
    private val keys: AdbKeys,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMs: Long = 3_000,
) : ShellTransport {

    override val transportId: String = "adb.tcp"

    private val lock = Any()
    private var cached: Pair<Long, TransportStatus>? = null

    override fun describe(): String = "$host:$port (ADB-over-TCP, key ${keys.fingerprint()})"

    override fun status(): TransportStatus {
        val (at, value) = synchronized(lock) { cached } ?: Pair(Long.MIN_VALUE, TransportStatus.NeedsSetup("SHELL_TIER_NOT_PAIRED"))
        if (System.nanoTime() / 1_000_000 - at < PROBE_COOLDOWN_MS) return value
        val probed = probe()
        synchronized(lock) { cached = Pair(System.nanoTime() / 1_000_000, probed) }
        return probed
    }

    override fun invalidateStatus() = synchronized(lock) { cached = null }

    private fun probe(): TransportStatus {
        return try {
            openSocket().use { socket ->
                val client = AdbClient(socket.getInputStream(), socket.getOutputStream(), keys)
                client.connect(System.nanoTime() / 1_000_000 + connectTimeoutMs)
                if (client.awaitingUserAuthorization()) TransportStatus.AwaitingAuthorization
                else TransportStatus.Live
            }
        } catch (e: AdbClient.AdbTimeout) {
            TransportStatus.NeedsSetup("SHELL_CONNECT_TIMEOUT")
        } catch (_: Exception) {
            TransportStatus.NeedsSetup("SHELL_TIER_NOT_PAIRED")
        }
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        invalidateStatus()
        val socket = try {
            openSocket()
        } catch (e: Exception) {
            throw ShellTransportException(ShellTransportException.Reason.NOT_CONNECTED, "adb tcp connect failed: ${e.message}")
        }
        try {
            socket.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
            val client = AdbClient(socket.getInputStream(), socket.getOutputStream(), keys)
            client.connect(System.nanoTime() / 1_000_000 + connectTimeoutMs)
            return try {
                client.execV2(command, timeoutMs)
            } catch (e: AdbClient.ShellV2Unavailable) {
                client.execLegacy(command, timeoutMs)
            }
        } catch (e: AdbClient.AdbTimeout) {
            throw ShellTransportException(ShellTransportException.Reason.TIMEOUT, e.message ?: "adb timeout")
        } catch (e: AdbProtocol.AdbProtocolException) {
            throw ShellTransportException(ShellTransportException.Reason.DEVICE_CLOSED, e.message ?: "adb protocol error")
        } catch (e: AdbClient.ShellV2Unavailable) {
            throw ShellTransportException(ShellTransportException.Reason.DEVICE_CLOSED, e.message ?: "shell service refused")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun openSocket(): Socket =
        socketFactory.createSocket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), connectTimeoutMs.toInt())
        }

    companion object {
        const val PROBE_COOLDOWN_MS: Long = 30_000
    }
}
