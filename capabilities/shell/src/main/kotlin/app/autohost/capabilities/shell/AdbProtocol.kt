package app.autohost.capabilities.shell

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.zip.CRC32

/**
 * P9-01: the ADB wire protocol (AOSP `protocol.txt`), framed over any byte stream — the shell
 * tier's client half. Pure Kotlin with no Android types so the JVM test suite drives it against a
 * fake `adbd` over real sockets.
 *
 * Every message: 24-byte little-endian header —
 *   cmd (4) | arg0 (4) | arg1 (4) | dataLength (4) | dataCrc32 (4) | magic (4 = cmd xor -1)
 * followed by the payload. Commands are the well-known four-character ids; CNXN payload is
 * version + max data + system-identity string; AUTH tokens/signatures/pubkeys ride the same
 * framing (AOSP `adb_auth`).
 */
object AdbProtocol {

    const val A_CNXN: Int = 0x4E58_4E43 // 'CNXN'
    const val A_AUTH: Int = 0x4854_5541 // 'AUTH'
    const val A_OPEN: Int = 0x4E45_504F // 'OPEN'
    const val A_OKAY: Int = 0x5941_4B4F // 'OKAY'
    const val A_CLSE: Int = 0x4553_4C43 // 'CLSE'
    const val A_WRTE: Int = 0x4554_5257 // 'WRTE'

    const val A_VERSION: Int = 0x0100_0001

    /** AUTH token types (AOSP adb.h). */
    const val AUTH_TOKEN: Int = 1
    const val AUTH_SIGNATURE: Int = 2
    const val AUTH_RSAPUBLICKEY: Int = 3

    /** Our advertised max payload. adbd allows up to 256 KiB; 4 KiB is the conservative host side. */
    const val MAX_PAYLOAD: Int = 4096

    const val HEADER_BYTES: Int = 24

    fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    /** One framed ADB message. */
    data class Message(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray) {
        override fun toString(): String =
            "AdbMessage(${name(cmd)}, arg0=$arg0, arg1=$arg1, len=${payload.size})"
    }

    fun name(cmd: Int): String = when (cmd) {
        A_CNXN -> "CNXN"
        A_AUTH -> "AUTH"
        A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"
        A_CLSE -> "CLSE"
        A_WRTE -> "WRTE"
        else -> "0x%08x".format(cmd)
    }

    /** Encode a message (header + payload) into a fresh byte array. */
    fun encode(message: Message): ByteArray {
        require(message.payload.size <= MAX_PAYLOAD) { "payload exceeds MAX_PAYLOAD" }
        val out = ByteArray(HEADER_BYTES + message.payload.size)
        writeInt(out, 0, message.cmd)
        writeInt(out, 4, message.arg0)
        writeInt(out, 8, message.arg1)
        writeInt(out, 12, message.payload.size)
        writeInt(out, 16, crc32(message.payload).toInt())
        writeInt(out, 20, message.cmd.inv())
        message.payload.copyInto(out, HEADER_BYTES)
        return out
    }

    /** Blocking read of one message; throws [EOFException] when the stream is closed mid-header. */
    fun read(input: DataInputStream): Message {
        val header = ByteArray(HEADER_BYTES)
        input.readFully(header)
        val cmd = readInt(header, 0)
        val arg0 = readInt(header, 4)
        val arg1 = readInt(header, 8)
        val length = readInt(header, 12)
        val crc = readInt(header, 16)
        val magic = readInt(header, 20)
        if (magic != cmd.inv()) throw AdbProtocolException("bad message magic (${name(cmd)})")
        if (length < 0 || length > MAX_PAYLOAD) throw AdbProtocolException("bad payload length $length")
        val payload = if (length > 0) ByteArray(length).also { input.readFully(it) } else ByteArray(0)
        if (crc32(payload).toInt() != crc) throw AdbProtocolException("payload CRC mismatch (${name(cmd)})")
        return Message(cmd, arg0, arg1, payload)
    }

    fun write(output: DataOutputStream, message: Message) {
        output.write(encode(message))
        output.flush()
    }

    class AdbProtocolException(detail: String) : Exception(detail)

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)
}
