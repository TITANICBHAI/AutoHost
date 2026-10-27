package app.autohost.capabilities.shell

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * P9-02: SPAKE2 (RFC 9382) over P-256 with SHA-256/HKDF/HMAC — the password-authenticated core of
 * the Android 11+ wireless-debugging *pairing* step ("pair device with pairing code"). Pure
 * BigInteger math, no crypto library dependency; the JVM test pins the port against the RFC 9382
 * Appendix-B vector (pA prefix `04a56fa8…`) plus a full two-party transcript cross-checked by an
 * independent Python reference (scripts/spake2_ref.py).
 *
 * The [Spake2Party] computes pA/pB and the derived Ke/KcA/KcB exactly per RFC §3.3/§4 (transcript
 * TT with 8-byte little-endian lengths; Ke‖Ka = Hash(TT); KcA‖KcB = HKDF(Ka, "ConfirmationKeys")).
 * Role A (prover, uses M) maps to the pairing client; role B (verifier, uses N) to the device.
 */
object Spake2P256 {

    val P: BigInteger = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val A_FIELD: BigInteger = BigInteger.valueOf(-3).mod(P)
    private val B_FIELD: BigInteger = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    val GX: BigInteger = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
    val GY: BigInteger = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)
    val ORDER: BigInteger = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    val COFACTOR: BigInteger = BigInteger.ONE

    /** RFC 9382 Table 1, P-256 M and N (compressed SEC1). */
    val M_HEX = "02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"
    val N_HEX = "03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"

    data class Point(val x: BigInteger, val y: BigInteger) {
        companion object {
            val INFINITY: Point = Point(BigInteger.ZERO, BigInteger.ZERO)
        }
    }

    fun decodePoint(hex: String): Point {
        val data = hex.hexToBytes()
        return when (data[0].toInt() and 0xFE) {
            0x02 -> decompress(data)
            0x04 -> Point(
                BigInteger(1, data.copyOfRange(1, 33)),
                BigInteger(1, data.copyOfRange(33, 65)),
            ).also { require(onCurve(it)) { "point not on curve" } }
            else -> throw IllegalArgumentException("unsupported SEC1 prefix 0x%02x".format(data[0]))
        }
    }

    private fun decompress(data: ByteArray): Point {
        val x = BigInteger(1, data.copyOfRange(1, data.size))
        val rhs = (x.multiply(x).multiply(x).add(A_FIELD.multiply(x)).add(B_FIELD)).mod(P)
        val y = rhs.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
        require(y.multiply(y).mod(P) == rhs) { "x is not a quadratic residue" }
        val wantOdd = data[0].toInt() == 0x03
        return if (y.testBit(0) == wantOdd) Point(x, y) else Point(x, P.subtract(y))
    }

    fun onCurve(point: Point): Boolean {
        val rhs = point.x.multiply(point.x).multiply(point.x)
            .add(A_FIELD.multiply(point.x)).add(B_FIELD).mod(P)
        return point.y.multiply(point.y).mod(P) == rhs
    }

    fun add(p1: Point?, p2: Point?): Point? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        if (p1.x == p2.x && p1.y.add(p2.y).mod(P) == BigInteger.ZERO) return null
        val lambda = if (p1 == p2) {
            p1.x.multiply(p1.x).multiply(BigInteger.valueOf(3)).add(A_FIELD)
                .multiply(p1.y.multiply(BigInteger.TWO).modInverse(P)).mod(P)
        } else {
            p2.y.subtract(p1.y).multiply(p2.x.subtract(p1.x).modInverse(P)).mod(P)
        }
        val x3 = lambda.multiply(lambda).subtract(p1.x).subtract(p2.x).mod(P)
        val y3 = lambda.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P)
        return Point(x3, y3)
    }

    fun mul(k: BigInteger, point: Point): Point? {
        var scalar = k.mod(ORDER)
        var result: Point? = null
        var addend: Point? = point
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    fun encodeUncompressed(point: Point): ByteArray =
        byteArrayOf(0x04) + leftPad(point.x) + leftPad(point.y)

    private fun leftPad(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        val from = if (raw.size > 32 && raw[0] == 0.toByte()) 1 else 0
        require(raw.size - from <= 32) { "coordinate too large" }
        raw.copyInto(out, 32 - (raw.size - from), from)
        return out
    }

    /** RFC 9382 §3.3/§4 — one side of the exchange; side "A" (prover, uses M) or "B" (uses N). */
    class Party(
        private val side: Side,
        private val w: BigInteger,
        private val idA: ByteArray,
        private val idB: ByteArray,
    ) {
        enum class Side { A, B }

        private lateinit var scalar: BigInteger
        lateinit var myMessage: ByteArray
            private set

        fun generateMessage(scalar: BigInteger): ByteArray {
            require(scalar.signum() > 0 && scalar < ORDER) { "scalar out of range" }
            this.scalar = scalar
            val base = if (side == Side.A) decodePoint(M_HEX) else decodePoint(N_HEX)
            val point = add(mul(w, base), mul(scalar, decodeGenerator()))
                ?: throw IllegalStateException("message point is at infinity")
            myMessage = encodeUncompressed(point)
            return myMessage
        }

        /** Consumes the peer's message and derives Ke/KcA/KcB. Aborts on a non-member point. */
        fun finish(peerMessage: ByteArray): Result {
            require(peerMessage.isNotEmpty() && peerMessage[0] == 0x04.toByte()) { "peer point must be uncompressed" }
            val peer = Point(
                BigInteger(1, peerMessage.copyOfRange(1, 33)),
                BigInteger(1, peerMessage.copyOfRange(33, 65)),
            )
            require(onCurve(peer)) { "peer point is not a member of the group (RFC: MUST abort)" }
            val base = if (side == Side.A) decodePoint(N_HEX) else decodePoint(M_HEX)
            val maskingPoint = mul(w.negate().mod(ORDER), base)
                ?: throw IllegalStateException("masking point is at infinity")
            val adjustedPeer = add(peer, maskingPoint)
                ?: throw IllegalStateException("adjusted peer point is at infinity")
            val shared = mul(scalar, adjustedPeer)
                ?: throw IllegalStateException("shared point is at infinity")
            val k = encodeUncompressed(shared)
            val pa = if (side == Side.A) myMessage else peerMessage
            val pb = if (side == Side.A) peerMessage else myMessage
            val tt = transcript(pa, pb, k)
            val digest = sha256(tt)
            val ke = digest.copyOfRange(0, 16)
            val ka = digest.copyOfRange(16, 32)
            val confirmation = hkdf(ka, ByteArray(0), "ConfirmationKeys".encodeToByteArray(), 32)
            return Result(
                ke = ke,
                kcA = confirmation.copyOfRange(0, 16),
                kcB = confirmation.copyOfRange(16, 32),
                tt = tt,
            )
        }

        private fun transcript(pa: ByteArray, pb: ByteArray, k: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(le64(idA.size)); out.write(idA)
            out.write(le64(idB.size)); out.write(idB)
            out.write(le64(pa.size)); out.write(pa)
            out.write(le64(pb.size)); out.write(pb)
            out.write(le64(k.size)); out.write(k)
            out.write(le64(32)); out.write(leftPad(w))
            return out.toByteArray()
        }

        data class Result(val ke: ByteArray, val kcA: ByteArray, val kcB: ByteArray, val tt: ByteArray)
    }

    fun decodeGenerator(): Point = Point(GX, GY)

    fun confirm(kc: ByteArray, tt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(kc, "HmacSHA256"))
        return mac.doFinal(tt)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val okm = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (okm.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            okm.write(t)
            counter++
        }
        return okm.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun le64(value: Int): ByteArray {
        val v = value.toLong()
        return byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
            0, 0, 0, 0,
        )
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { i ->
            ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
        }
    }
}
