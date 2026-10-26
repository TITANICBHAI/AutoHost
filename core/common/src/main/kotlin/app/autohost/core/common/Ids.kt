package app.autohost.core.common

import java.security.SecureRandom

/**
 * Identifier generation (AGENTS.md §4: never `Random` directly). [ulid] produces sortable, unique-at-millisecond
 * identifiers used for run ids, audit ids and similar; [hexToken] produces cryptographically random tokens
 * (run tokens, Dev API tokens). Never use either for anything else, and never use the pair reversed.
 */
object Ids {
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32 (no I, L, O, U)
    private val random = SecureRandom()

    // 48-bit timestamp + 80-bit randomness = 128 bits = 26 chars.
    private const val TIME_CHARS = 10
    private const val TOTAL_CHARS = 26

    // Lock protects monotonic ordering: two ULIDs generated in the same millisecond must still sort.
    private val lock = Any()
    private var lastTimeMs = -1L
    private var lastRandom = ByteArray(10)

    fun ulid(nowMs: Long = System.currentTimeMillis()): String {
        val timeBytes = ByteArray(6)
        for (i in 0 until 6) timeBytes[i] = ((nowMs ushr ((5 - i) * 8)) and 0xFF).toByte()
        val randomBytes: ByteArray
        synchronized(lock) {
            randomBytes = if (nowMs == lastTimeMs) {
                increment(lastRandom)
            } else {
                ByteArray(10).also { random.nextBytes(it) }
            }
            lastTimeMs = nowMs
            lastRandom = randomBytes
        }
        val bytes = timeBytes + randomBytes
        return encode(bytes)
    }

    /** Strictly increasing randomness when several ids are minted in the same millisecond. */
    private fun increment(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        for (i in copy.indices.reversed()) {
            val v = (copy[i].toInt() and 0xFF) + 1
            copy[i] = v.toByte()
            if (v <= 0xFF) return copy
        }
        // overflowed (astronomically unlikely): fall back to fresh randomness
        val fresh = ByteArray(copy.size)
        random.nextBytes(fresh)
        return fresh
    }

    /** [nBytes] random bytes as lowercase hex. Default 32 bytes = 64 chars (run-token strength). */
    fun hexToken(nBytes: Int = 32): String {
        require(nBytes > 0) { "token size must be positive" }
        val bytes = ByteArray(nBytes)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun encode(bytes: ByteArray): String {
        // 16 bytes -> 128 bits -> 26 base32 chars (5 bits per char, 130 bits used, last 2 bits are padding).
        var bits = 0L
        var bitsSize = 0
        val sb = StringBuilder(TOTAL_CHARS)
        var chars = 0
        for (b in bytes) {
            bits = (bits shl 8) or (b.toLong() and 0xFF)
            bitsSize += 8
            while (bitsSize >= 5 && chars < TOTAL_CHARS) {
                val index = ((bits ushr (bitsSize - 5)) and 0x1F).toInt()
                sb.append(ENCODING[index])
                chars++
                bitsSize -= 5
            }
        }
        while (chars < TOTAL_CHARS) {
            sb.append(ENCODING[0])
            chars++
        }
        return sb.toString()
    }
}
