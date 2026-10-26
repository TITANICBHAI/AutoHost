package app.autohost.core.devapi

import java.security.MessageDigest
import java.util.Base64

/**
 * P11-07 (ADR-081): the RFC 6455 frame codec for the reverse Dev API transport — shared by the
 * phone-side link (client role: sends MASKED frames, receives UNMASKED server frames) and, via
 * the mirror in `autohost_cli/bridge.py`, by the PC listener. Text frames only; ping/pong/close
 * are skipped by callers. 7-bit, 16-bit and 64-bit payload lengths are all supported — Dev API
 * responses (runs.tail, exports) can exceed the 64 KiB the in-app server's legacy codec covers.
 */
object WsFrames {

    const val OP_TEXT = 0x1
    const val OP_PING = 0x9
    const val OP_PONG = 0xA
    const val OP_CLOSE = 0x8

    /** The RFC 6455 magic GUID for `Sec-WebSocket-Accept`. */
    const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    data class Frame(val opcode: Int, val payload: ByteArray) {
        val text: String get() = String(payload)
    }

    sealed class Decode {
        data class Frame(val frame: WsFrames.Frame) : Decode()
        data object NeedMore : Decode()
        data object Closed : Decode()
        data class Invalid(val reason: String) : Decode()
    }

    /** The accept value a server must answer a handshake with (RFC 6455 §1.3). */
    fun accept(secWebSocketKey: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((secWebSocketKey + WS_GUID).encodeToByteArray()),
        )

    fun randomKey(): String =
        Base64.getEncoder().encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })

    /**
     * Incremental decode: feed whatever bytes arrived; the [offset]/[length] window is one TCP
     * read. Control frames (ping/pong/close) are returned to the caller, never auto-answered —
     * the transport loops here decide (the reverse link answers pings with pongs and stops on
     * close; the Dev API's in-app server skips them).
     */
    fun decode(buffer: ByteArray, offset: Int, length: Int, expectMasked: Boolean): Decode {
        if (length < 2) return Decode.NeedMore
        val b0 = buffer[offset].toInt() and 0xFF
        val b1 = buffer[offset + 1].toInt() and 0xFF
        val opcode = b0 and 0x0F
        if (b0 and 0x70 != 0) return Decode.Invalid("RSV bits set (no extension negotiated)")
        val masked = b1 and 0x80 != 0
        if (masked != expectMasked) {
            return Decode.Invalid(if (expectMasked) "frame must be masked" else "server frames are never masked")
        }
        var payloadLength = (b1 and 0x7F).toLong()
        var header = 2
        when {
            payloadLength == 126L -> {
                if (length < 4) return Decode.NeedMore
                payloadLength = ((buffer[offset + 2].toInt() and 0xFF).toLong() shl 8) or (buffer[offset + 3].toInt() and 0xFF).toLong()
                header = 4
            }
            payloadLength == 127L -> {
                if (length < 10) return Decode.NeedMore
                payloadLength = 0
                for (i in 2..9) payloadLength = (payloadLength shl 8) or (buffer[offset + i].toLong() and 0xFF)
                header = 10
            }
        }
        val maskLength = if (masked) 4 else 0
        val total = header.toLong() + maskLength + payloadLength
        if (total > 8 * 1024 * 1024) return Decode.Invalid("frame exceeds the 8 MiB transport cap")
        if (length.toLong() < total) return Decode.NeedMore
        val totalBytes = total.toInt()
        var payload = buffer.copyOfRange(offset + header + maskLength, offset + totalBytes)
        if (masked) {
            val maskStart = offset + header
            val out = payload.copyOf()
            for (i in out.indices) out[i] = (out[i].toInt() xor buffer[maskStart + (i % 4)].toInt()).toByte()
            payload = out
        }
        return Decode.Frame(WsFrames.Frame(opcode, payload))
    }

    /** One-shot convenience over a full read window (tests, simple callers). */
    fun decode(buffer: ByteArray, expectMasked: Boolean): Decode = decode(buffer, 0, buffer.size, expectMasked)

    /** Encodes one text frame. Client role sets [mask] = true (random key per frame). */
    fun encodeText(payload: String, mask: Boolean): ByteArray = encode(OP_TEXT, payload.encodeToByteArray(), mask)

    fun encode(opcode: Int, payload: ByteArray, mask: Boolean): ByteArray {
        val maskLength = if (mask) 4 else 0
        val lengthBits = when {
            payload.size < 126 -> 1
            payload.size < 65536 -> 3
            else -> 9
        }
        val header = ByteArray(lengthBits + maskLength)
        header[0] = (0x80 or opcode).toByte() // FIN + opcode (single frame, no fragmentation)
        when (lengthBits) {
            1 -> header[1] = payload.size.toByte()
            3 -> {
                header[1] = (0x80 or 126).toByte()
                header[2] = ((payload.size shr 8) and 0xFF).toByte()
                header[3] = (payload.size and 0xFF).toByte()
            }
            else -> {
                header[1] = (0x80 or 127).toByte()
                var size = payload.size.toLong()
                for (i in 7 downTo 0) {
                    header[2 + (7 - i)] = ((size shr (i * 8)) and 0xFF).toByte()
                }
            }
        }
        if (mask) {
            val key = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
            key.copyInto(header, lengthBits)
            val out = ByteArray(header.size + payload.size)
            header.copyInto(out)
            for (i in payload.indices) out[header.size + i] = (payload[i].toInt() xor key[i % 4].toInt()).toByte()
            return out
        }
        val out = ByteArray(header.size + payload.size)
        header.copyInto(out)
        payload.copyInto(out, header.size)
        return out
    }
}
