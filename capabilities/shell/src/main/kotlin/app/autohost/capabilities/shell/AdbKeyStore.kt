package app.autohost.capabilities.shell

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * P9-01: the host's ADB identity — one 2048-bit RSA keypair, generated on first use and persisted
 * next to the private data dir (same model as `adb keygen`). adbd signs its 20-byte SHA-1 tokens
 * with the private half (`AUTH signature`); the public half travels in the Android "adb public
 * key" struct format (`AUTH rsapublickey`) so the user can approve the key on the device — exactly
 * the manual step `adb connect` triggers on a PC.
 *
 * Storage is plain base64 of PKCS#8/X.509 encodings in two files, chmod-friendly because the
 * directory is our own `filesDir`. Persistence sits behind [Store] so JVM tests use a temp dir.
 */
class AdbKeys(
    val keyPair: KeyPair,
    /** Label embedded in the public-key blob shown on the device's authorization dialog. */
    val name: String,
) {
    /** Signs an adbd challenge token (SHA1withRSA, PKCS#1 v1.5 — what adbd verifies). */
    fun signToken(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(token)
        return signature.sign()
    }

    /**
     * The public key in adbd's native struct format (`android_pubkey_encode`), base64-encoded with
     * the trailing `<space>name\0` adbd appends to its keystore: uint32 size-info (words),
     * uint32 n0inv (= -n^-1 mod 2^32), modulus and Montgomery rr (little-endian), uint32 exponent.
     */
    fun publicKeyBlob(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val modulus = pub.modulus
        val words = modulus.bitLength() / 32
        require(words == 64) { "ADB keys must be RSA-2048, got ${modulus.bitLength()} bits" }
        val n = littleEndian(modulus, 256)
        val n0inv = BigInteger.valueOf(2).pow(32)
            .subtract(modulus.modInverse(BigInteger.valueOf(2).pow(32)))
            .mod(BigInteger.valueOf(2).pow(32))
        val rr = BigInteger.valueOf(2).pow(4096).mod(modulus)
        val blob = ByteArray(4 + 4 + 256 + 256 + 4)
        writeLe32(blob, 0, words.toLong())
        writeLe32(blob, 4, n0inv.toLong())
        n.copyInto(blob, 8)
        littleEndian(rr, 256).copyInto(blob, 264)
        writeLe32(blob, 520, pub.publicExponent.toLong())
        val b64 = Base64.getEncoder().encodeToString(blob)
        return (b64 + " " + name + "\u0000").encodeToByteArray()
    }

    /** First 12 hex of the SHA-256 of the public-key blob — the fingerprint shown in Setup. */
    fun fingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(keyPair.public.encoded)
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun littleEndian(value: BigInteger, bytes: Int): ByteArray {
        val big = value.toByteArray() // big-endian, possibly with a leading zero for the sign
        val unsigned = if (big.size > bytes && big[0] == 0.toByte()) big.copyOfRange(1, big.size) else big
        require(unsigned.size <= bytes) { "value does not fit in $bytes bytes" }
        val out = ByteArray(bytes)
        for (i in unsigned.indices) out[i] = unsigned[unsigned.size - 1 - i]
        return out
    }

    private fun writeLe32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}

/** Persistence seam (filesDir in production, temp dirs in tests). */
interface AdbKeyStore {
    fun load(): AdbKeys
}

class AdbKeyFiles(private val dir: File, private val name: String) : AdbKeyStore {

    override fun load(): AdbKeys {
        val privateKeyFile = File(dir, "adbkey.pk8.b64")
        val keyPair = if (privateKeyFile.isFile) {
            restore(privateKeyFile.readText().trim())
        } else {
            generate().also { persist(it, privateKeyFile) }
        }
        return AdbKeys(keyPair, name)
    }

    private fun generate(): KeyPair =
        KeyPairGenerator.getInstance("RSA").run {
            initialize(2048, SecureRandom())
            generateKeyPair()
        }

    private fun persist(keyPair: KeyPair, target: File) {
        target.parentFile?.mkdirs()
        val b64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(b64)
        if (!tmp.renameTo(target)) {
            target.writeText(b64)
            tmp.delete()
        }
    }

    private fun restore(b64: String): KeyPair {
        val factory = KeyFactory.getInstance("RSA")
        val private = factory.generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)),
        ) as java.security.interfaces.RSAPrivateCrtKey
        val public = factory.generatePublic(
            RSAPublicKeySpec(private.modulus, private.publicExponent),
        )
        return KeyPair(public, private)
    }
}
