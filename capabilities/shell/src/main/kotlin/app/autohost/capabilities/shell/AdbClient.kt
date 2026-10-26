package app.autohost.capabilities.shell

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/**
 * P9-01: the client half of one ADB session — CNXN/AUTH handshake, then `shell` services over
 * OPEN/OKAY/WRTE/CLSE. One [AdbClient] instance drives one connection; transports create it per
 * exec (stateless, honest about latency — the shell tier is the power path, not the hot one).
 *
 * Exec strategy: request the `shell,v2:` service first (separated stdout/stderr plus a real exit
 * code — Android 9+ adbd implements it). When the device closes without data (an adbd that lacks
 * shell_v2, or a service that was rejected), the caller may retry through [execLegacy] where the
 * exit code is honestly `null`.
 */
class AdbClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val keys: AdbKeys,
    private val identity: String = DEFAULT_IDENTITY,
    private val maxData: Int = AdbProtocol.MAX_PAYLOAD,
) {
    private lateinit var reader: DataInputStream
    private lateinit var writer: DataOutputStream
    private var version: Int = 0
    private var deviceMaxData: Int = 0
    private var pubkeySent = false

    /** Runs the CNXN/AUTH dance until the device answers CNXN (authorized) or times out. */
    fun connect(deadlineMs: Long) {
        reader = DataInputStream(input.buffered())
        writer = DataOutputStream(output.buffered())
        AdbProtocol.write(
            writer,
            AdbProtocol.Message(
                AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, maxData, identity.encodeToByteArray(),
            ),
        )
        var pubkeyOffers = 0
        while (true) {
            if (System.nanoTime() / 1_000_000 > deadlineMs) throw AdbTimeout("handshake exceeded the deadline")
            val message = try {
                AdbProtocol.read(reader)
            } catch (e: EOFException) {
                throw AdbProtocol.AdbProtocolException("adbd closed during handshake: ${e.message}")
            }
            when (message.cmd) {
                AdbProtocol.A_CNXN -> {
                    version = message.arg0
                    deviceMaxData = message.arg1
                    return
                }
                AdbProtocol.A_AUTH -> when (message.arg0) {
                    AdbProtocol.AUTH_TOKEN -> {
                        // Sign the challenge; if adbd does not know us it re-challenges, and on the
                        // second loop we offer the public key for the user to approve (the same
                        // "allow USB debugging?" dialog a PC's first connect raises).
                        val signature = AdbProtocol.AUTH_SIGNATURE
                        AdbProtocol.write(
                            writer,
                            AdbProtocol.Message(
                                AdbProtocol.A_AUTH, signature, 0, keys.signToken(message.payload),
                            ),
                        )
                        if (!pubkeySent && sawSecondChallenge()) {
                            pubkeyOffers++
                            AdbProtocol.write(
                                writer,
                                AdbProtocol.Message(
                                    AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, keys.publicKeyBlob(),
                                ),
                            )
                            pubkeySent = true
                        }
                    }
                    else -> throw AdbProtocol.AdbProtocolException(
                        "unexpected AUTH type ${message.arg0} during handshake",
                    )
                }
                else -> throw AdbProtocol.AdbProtocolException("unexpected ${AdbProtocol.name(message.cmd)} during handshake")
            }
        }
    }

    private var challengeCount = 0

    private fun sawSecondChallenge(): Boolean {
        challengeCount++
        return challengeCount >= 2
    }

    /** True when the last handshake ended with us offering the key (the device still needs a tap). */
    fun awaitingUserAuthorization(): Boolean = pubkeySent

    /**
     * Runs one `shell,v2:<command>` service and collects the separated streams. The v2 framing
     * prefixes every chunk with stream id (0 stdin, 1 stdout, 2 stderr, 3 exit) + LE length; the
     * exit chunk carries a 4-byte LE process exit code.
     */
    fun execV2(command: String, timeoutMs: Long): ShellResult {
        val deadline = System.nanoTime() / 1_000_000 + timeoutMs
        val localId = LOCAL_STREAM_ID
        AdbProtocol.write(writer, AdbProtocol.Message(AdbProtocol.A_OPEN, localId, 0, "shell,v2:$command".encodeToByteArray()))
        val openAck = AdbProtocol.read(reader)
        if (openAck.cmd != AdbProtocol.A_OKAY || openAck.arg1 != localId) {
            throw AdbProtocol.AdbProtocolException("OPEN was not acknowledged with OKAY (${AdbProtocol.name(openAck.cmd)})")
        }
        val remoteId = openAck.arg0
        val stdout = java.io.ByteArrayOutputStream()
        val stdoutText = StringBuilder()
        val stderr = java.io.ByteArrayOutputStream()
        val stderrText = StringBuilder()
        var exitCode: Int? = null
        var sawData = false
        while (true) {
            if (System.nanoTime() / 1_000_000 > deadline) throw AdbTimeout("shell,v2 exceeded the deadline")
            val message = try {
                AdbProtocol.read(reader)
            } catch (e: EOFException) {
                break // adbd closes after CLSE on some versions; treat as clean end
            } catch (e: SocketTimeoutException) {
                throw AdbTimeout("shell,v2 read timed out")
            }
            when (message.cmd) {
                AdbProtocol.A_WRTE -> {
                    sawData = true
                    AdbProtocol.write(writer, AdbProtocol.Message(AdbProtocol.A_OKAY, localId, remoteId, ByteArray(0)))
                    parseV2Chunks(message.payload, stdout, stdoutText, stderr, stderrText)?.let { exitCode = it }
                }
                AdbProtocol.A_OKAY -> Unit // flow-control ack for our WRTEs (we never write)
                AdbProtocol.A_CLSE -> break
                else -> throw AdbProtocol.AdbProtocolException("unexpected ${AdbProtocol.name(message.cmd)} in shell,v2")
            }
        }
        if (!sawData && exitCode == null) {
            throw ShellV2Unavailable("no shell,v2 stream data before close")
        }
        return ShellResult(
            stdout = stdoutText.toString(),
            stderr = stderrText.toString(),
            exitCode = exitCode,
            mode = "shell_v2",
            stdoutBytes = stdout.toByteArray(),
        )
    }

    /** Legacy `shell:<command>` — merged output, honest null exit code. */
    fun execLegacy(command: String, timeoutMs: Long): ShellResult {
        val deadline = System.nanoTime() / 1_000_000 + timeoutMs
        val localId = LOCAL_STREAM_ID
        AdbProtocol.write(writer, AdbProtocol.Message(AdbProtocol.A_OPEN, localId, 0, "shell:$command".encodeToByteArray()))
        val openAck = AdbProtocol.read(reader)
        if (openAck.cmd != AdbProtocol.A_OKAY || openAck.arg1 != localId) {
            throw AdbProtocol.AdbProtocolException("OPEN was not acknowledged with OKAY (${AdbProtocol.name(openAck.cmd)})")
        }
        val remoteId = openAck.arg0
        val merged = java.io.ByteArrayOutputStream()
        val mergedText = StringBuilder()
        while (true) {
            if (System.nanoTime() / 1_000_000 > deadline) throw AdbTimeout("shell exceeded the deadline")
            val message = try {
                AdbProtocol.read(reader)
            } catch (e: EOFException) {
                break
            } catch (e: SocketTimeoutException) {
                throw AdbTimeout("shell read timed out")
            }
            when (message.cmd) {
                AdbProtocol.A_WRTE -> {
                    merged.write(message.payload)
                    mergedText.append(String(message.payload, Charsets.UTF_8))
                    AdbProtocol.write(writer, AdbProtocol.Message(AdbProtocol.A_OKAY, localId, remoteId, ByteArray(0)))
                }
                AdbProtocol.A_OKAY -> Unit
                AdbProtocol.A_CLSE -> break
                else -> throw AdbProtocol.AdbProtocolException("unexpected ${AdbProtocol.name(message.cmd)} in shell")
            }
        }
        // Legacy mode translates LF→CRLF on some adbds, so binary payloads are corrupted here;
        // callers needing bytes must get a shell,v2 connection (the screencap provider reports an
        // honest SCREENSHOT_BLOCKED on the non-PNG result this produces).
        return ShellResult(stdout = mergedText.toString(), stderr = "", exitCode = null, mode = "shell", stdoutBytes = merged.toByteArray())
    }

    fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun parseV2Chunks(
        payload: ByteArray,
        stdoutBytes: java.io.ByteArrayOutputStream,
        stdoutText: StringBuilder,
        stderrBytes: java.io.ByteArrayOutputStream,
        stderrText: StringBuilder,
    ): Int? {
        var offset = 0
        var exit: Int? = null
        while (offset + 5 <= payload.size) {
            val streamId = payload[offset].toInt() and 0xFF
            val length = (payload[offset + 1].toInt() and 0xFF) or
                ((payload[offset + 2].toInt() and 0xFF) shl 8) or
                ((payload[offset + 3].toInt() and 0xFF) shl 16) or
                ((payload[offset + 4].toInt() and 0xFF) shl 24)
            offset += 5
            if (offset + length > payload.size) throw AdbProtocol.AdbProtocolException("shell,v2 chunk overruns the frame")
            val chunk = payload.copyOfRange(offset, offset + length)
            when (streamId) {
                STREAM_STDOUT -> {
                    stdoutBytes.write(chunk)
                    stdoutText.append(String(chunk, Charsets.UTF_8))
                }
                STREAM_STDERR -> {
                    stderrBytes.write(chunk)
                    stderrText.append(String(chunk, Charsets.UTF_8))
                }
                STREAM_EXIT -> if (length >= 4) {
                    exit = (payload[offset].toInt() and 0xFF) or
                        ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                        ((payload[offset + 2].toInt() and 0xFF) shl 16) or
                        ((payload[offset + 3].toInt() and 0xFF) shl 24)
                }
                else -> Unit // stdin echoes nothing back; ignore unknown ids honestly
            }
            offset += length
        }
        return exit
    }

    class AdbTimeout(detail: String) : Exception(detail)

    class ShellV2Unavailable(detail: String) : Exception(detail)

    companion object {
        const val DEFAULT_IDENTITY = "host::autohost"
        const val LOCAL_STREAM_ID = 1
        const val STREAM_STDIN = 0
        const val STREAM_STDOUT = 1
        const val STREAM_STDERR = 2
        const val STREAM_EXIT = 3
    }
}
