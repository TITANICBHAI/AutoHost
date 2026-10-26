package app.autohost.capabilities.shell

import java.math.BigInteger
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * P9-02: the Android 11+ wireless-debugging pairing transport (plan §3.1 T3, second link).
 *
 * **Draft status — UNVERIFIED-on-hardware by design (ADR-065).** The SPAKE2 core is RFC-pinned
 * (see [Spake2P256]); the TLS-PSK channel and the AOSP pairing-packet framing are implemented to
 * the published protocol shape but cannot be byte-confirmed against a real Android 11+ host from
 * this sandbox. The transport therefore reports [TransportStatus.Unsupported]
 * (`WIRELESS_PAIRING_DRAFT`) until Gate-9 device work verifies it; the Setup flow keeps steering
 * Android 10 users (the primary device) to the ADB-over-TCP link regardless.
 *
 * Pairing flow (`pair(code, host, port)`): connect TLS-PSK (PSK = the 6-digit code) → run SPAKE2
 * inside the channel → both sides exchange pairing packets carrying the ADB public key → the
 * device stores the key and the normal ADB client can connect over TLS afterwards.
 */
class WirelessPairingTransport(
    private val keys: AdbKeys,
    private val tls: PskTlsChannel.Factory = JssePskTlsChannel.factory(),
) : ShellTransport {

    override val transportId: String = "adb.wireless"

    @Volatile
    private var pairedEndpoint: Pair<String, Int>? = null

    override fun describe(): String {
        val endpoint = pairedEndpoint
        val key = "key ${keys.fingerprint()}"
        return if (endpoint == null) "wireless debugging (unpaired, $key)" else "wireless debugging ${endpoint.first}:${endpoint.second} ($key)"
    }

    override fun status(): TransportStatus {
        val pskAvailable = tls.pskSupported()
        return when {
            !pskAvailable -> TransportStatus.Unsupported("TLS_PSK_PROVIDER_MISSING")
            pairedEndpoint == null -> TransportStatus.NeedsSetup("WIRELESS_PAIRING_DRAFT")
            else -> TransportStatus.Unsupported("WIRELESS_PAIRING_DRAFT")
        }
    }

    override fun invalidateStatus() = Unit

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        throw ShellTransportException(
            ShellTransportException.Reason.UNSUPPORTED,
            "wireless debugging pairing is a draft until Gate-9 device verification (use adb-over-tcp, shizuku or root)",
        )
    }

    /**
     * Runs one pairing attempt against a device showing a 6-digit code. Returns the human-facing
     * result; failures are typed, never silent. The endpoint is remembered for the (draft) exec
     * path and the Setup screen.
     */
    fun pair(code: String, host: String, port: Int, timeoutMs: Long = 30_000): PairingOutcome {
        if (!tls.pskSupported()) return PairingOutcome.Failed("TLS_PSK_PROVIDER_MISSING")
        if (!code.all { it.isDigit() } || code.length !in 6..6) return PairingOutcome.Failed("PAIRING_CODE_INVALID")
        return try {
            tls.connect(host, port, code, timeoutMs).use { channel ->
                val client = Spake2P256.Party(
                    Spake2P256.Party.Side.A,
                    passwordScalar(code),
                    PAIRING_ID_CLIENT.encodeToByteArray(),
                    PAIRING_ID_DEVICE.encodeToByteArray(),
                )
                val x = BigInteger(160, SecureRandom())
                val pA = client.generateMessage(x)
                val pBFrame = channel.exchange(Frame(pA))
                val server = Spake2P256.Party.Side.B
                require(pBFrame.payload.size == 65 && pBFrame.payload[0] == 0x04.toByte()) {
                    "peer pairing message malformed"
                }
                // Mirror the exchange as the verifier to confirm the device used the same password.
                val verifier = Spake2P256.Party(
                    server, passwordScalar(code),
                    PAIRING_ID_CLIENT.encodeToByteArray(), PAIRING_ID_DEVICE.encodeToByteArray(),
                )
                val localPb = verifier.generateMessage(BigInteger(160, SecureRandom()))
                val clientKeys = client.finish(pBFrame.payload)
                val serverKeys = verifier.finish(pA)
                if (!clientKeys.ke.contentEquals(serverKeys.ke)) {
                    return PairingOutcome.Failed("PAIRING_CODE_MISMATCH")
                }
                // Send our public key inside the encrypted channel (framing is the draft element).
                channel.exchange(Frame(keys.publicKeyBlob()))
                pairedEndpoint = Pair(host, port)
                PairingOutcome.Paired(endpoint = "$host:$port", fingerprint = keys.fingerprint())
            }
        } catch (e: ShellTransportException) {
            PairingOutcome.Failed(e.reason.name)
        } catch (e: Exception) {
            PairingOutcome.Failed("PAIRING_FAILED:${e.javaClass.simpleName}")
        }
    }

    /** Derives the SPAKE2 password scalar from the pairing code (SHA-256 mod order, bias-bounded by mod). */
    private fun passwordScalar(code: String): BigInteger =
        BigInteger(1, Spake2P256.sha256(code.encodeToByteArray())).mod(Spake2P256.ORDER.subtract(BigInteger.ONE)).add(BigInteger.ONE)

    data class Frame(val payload: ByteArray)

    sealed class PairingOutcome {
        data class Paired(val endpoint: String, val fingerprint: String) : PairingOutcome()
        data class Failed(val reason: String) : PairingOutcome()
    }

    companion object {
        const val PAIRING_ID_CLIENT = "autohost.pairing.client"
        const val PAIRING_ID_DEVICE = "android.pairing.device"
    }
}

/**
 * The TLS-PSK seam. The JDK has no PSK cipher suites; Android's Conscrypt provider does. The
 * default factory probes for a PSK-capable SSLContext at runtime — on a device with Conscrypt the
 * pairing can actually run, on plain JVM tests it reports honestly unsupported.
 */
interface PskTlsChannel : AutoCloseable {
    fun exchange(frame: WirelessPairingTransport.Frame): WirelessPairingTransport.Frame

    interface Factory {
        fun pskSupported(): Boolean
        fun connect(host: String, port: Int, psk: String, timeoutMs: Long): PskTlsChannel
    }
}

/** JSSE-based attempt: looks for any provider offering a TLS_PSK context; null when none. */
class JssePskTlsChannel private constructor(private val socket: SSLSocket) : PskTlsChannel {

    override fun exchange(frame: WirelessPairingTransport.Frame): WirelessPairingTransport.Frame {
        val out = socket.getOutputStream()
        out.write(frame.payload)
        out.flush()
        val input = socket.getInputStream()
        val lengthBytes = ByteArray(4)
        readFully(input, lengthBytes)
        val length = (lengthBytes[0].toInt() and 0xFF) or
            ((lengthBytes[1].toInt() and 0xFF) shl 8) or
            ((lengthBytes[2].toInt() and 0xFF) shl 16) or
            ((lengthBytes[3].toInt() and 0xFF) shl 24)
        require(length in 1..MAX_FRAME) { "pairing frame length out of range: $length" }
        val payload = ByteArray(length)
        readFully(input, payload)
        return WirelessPairingTransport.Frame(payload)
    }

    override fun close() = runCatching { socket.close() }.getOrDefault(Unit)

    companion object {
        const val MAX_FRAME = 256 * 1024

        fun factory(): PskTlsChannel.Factory = object : PskTlsChannel.Factory {
            override fun pskSupported(): Boolean = pskContext() != null

            override fun connect(host: String, port: Int, psk: String, timeoutMs: Long): PskTlsChannel {
                val context = pskContext()
                    ?: throw ShellTransportException(ShellTransportException.Reason.UNSUPPORTED, "TLS_PSK_PROVIDER_MISSING")
                val raw = javax.net.SocketFactory.getDefault().createSocket()
                raw.connect(java.net.InetSocketAddress(host, port), timeoutMs.toInt())
                val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                socket.soTimeout = timeoutMs.toInt()
                socket.startHandshake()
                return JssePskTlsChannel(socket)
            }
        }

        /** Probes every installed provider for a TLS-PSK-capable context (Conscrypt on device). */
        private fun pskContext(): SSLContext? = java.security.Security.getProviders().asSequence()
            .mapNotNull { provider ->
                runCatching { SSLContext.getInstance("TLS", provider) }.getOrNull()
            }
            .firstOrNull { context ->
                runCatching {
                    context.defaultSSLParameters.cipherSuites.any { it.contains("PSK") }
                }.getOrDefault(false)
            }
    }
}

private fun readFully(input: java.io.InputStream, target: ByteArray) {
    var read = 0
    while (read < target.size) {
        val n = input.read(target, read, target.size - read)
        if (n < 0) throw java.io.EOFException("pairing channel closed")
        read += n
    }
}
