package app.autohost.core.data.secrets

import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import app.autohost.core.data.db.SecretBlobDao
import app.autohost.core.data.db.SecretBlobEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2-08: secrets are Keystore-wrapped and only leave the store per call to the broker (plan §9.1, §14.1).
 *
 * Layers:
 *  - [AeadCipher] — the crypto seam. The Android implementation wraps an AndroidKeyStore AES-GCM key
 *    ([AndroidKeystoreAead]); JVM tests use a fake. Plaintexts never touch this module's API surface twice.
 *  - [SecretStore] — per-profile key/value storage: encrypt → blob row; decrypt on explicit read only.
 *  - [SecretRegistry] — remembers which values are live secrets so the Redactor scrubs them everywhere.
 *
 * Rules enforced here (AGENTS.md §3.6): never log values; never persist plaintext; deleting a profile
 * deletes its blobs; the canary-secret test relies on the redactor being fed every value.
 */
interface AeadCipher {
    val keyAlias: String

    /** Encrypts plaintext; returns (ciphertext, iv). */
    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray>

    /** Decrypts; throws on tamper (AEAD tag mismatch) — callers fail closed. */
    fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray
}

class SecretStore(
    private val dao: SecretBlobDao,
    private val aead: AeadCipher,
    private val registry: SecretRegistry,
    private val clock: Clock,
) {

    /** Stores (or replaces) one secret; the value joins the redactor's set immediately. */
    suspend fun put(profileId: String, key: String, value: String) {
        registry.register(value)
        val (ciphertext, iv) = aead.encrypt(value.encodeToByteArray())
        dao.upsert(
            SecretBlobEntity(
                profileId = profileId,
                key = key,
                ciphertext = ciphertext,
                iv = iv,
                keyAlias = aead.keyAlias,
                updatedAt = clock.nowMs(),
            ),
        )
    }

    /** Decrypts one secret. Tampered blobs throw (fail closed) — surfaced as INTERNAL, never as the value. */
    suspend fun get(profileId: String, key: String): String? {
        val blob = dao.byKey(profileId, key) ?: return null
        return try {
            aead.decrypt(blob.ciphertext, blob.iv).decodeToString()
        } catch (e: Exception) {
            AhLog.e(TAG, "secret decrypt failed; treating as absent (possible tamper)", buildJsonObject { put("key", key) })
            null
        }
    }

    /** Snapshot of every secret in a profile as (key → value); used to hand the run its declared fields only. */
    suspend fun allOf(profileId: String): Map<String, String> =
        dao.allOf(profileId).mapNotNull { blob ->
            try {
                blob.key to aead.decrypt(blob.ciphertext, blob.iv).decodeToString()
            } catch (e: Exception) {
                AhLog.e(TAG, "secret decrypt failed during bulk read", buildJsonObject { put("key", blob.key) })
                null
            }
        }.toMap()

    suspend fun keysOf(profileId: String): List<String> = dao.allOf(profileId).map { it.key }

    suspend fun deleteForProfile(profileId: String) {
        dao.deleteForProfile(profileId)
        // values are gone from the store; the redactor keeps name-agnostic patterns for the session only
    }

    /** The grant view of secrets: only the key names ever appear in reviews or exports (plan §9.1). */
    fun declaredKeys(secretKeys: List<String>): JsonObject = buildJsonObject {
        put("keys", secretKeys.joinToString(", "))
    }

    companion object {
        private const val TAG = "secrets"
    }
}

/**
 * Holds live secret values for the Redactor. Values are held in memory only, never serialized.
 * `Redactor.redact` asks this registry for exact-value matches plus a few encodings.
 */
class SecretRegistry {
    private val values = LinkedHashSet<String>()

    fun register(value: String) {
        if (value.isNotBlank()) values.add(value)
    }

    fun unregister(value: String) {
        values.remove(value)
    }

    /** Exact values currently considered secret. Copy, not live view. */
    fun snapshot(): List<String> = values.toList()

    fun clear() = values.clear()
}

/**
 * P2-08: the central redactor (plan §14.4). Applied by logs, evidence writer, exports, audit and the SDK.
 * Scrubs: registered secret values, common encodings of them (URL-encoded, base64 of the utf-8 bytes,
 * percent-escapes), password-node markers, and JSON keys named like credentials.
 */
class Redactor(private val registry: SecretRegistry) {

    fun redact(text: String): String {
        var result = text
        for (secret in registry.snapshot()) {
            if (secret.isBlank()) continue
            result = result.replace(secret, MASK)
            result = result.replace(urlEncoded(secret), MASK)
            result = result.replace(base64(secret), MASK)
        }
        return result
    }

    fun redactJsonKeys(value: JsonObject): JsonObject {
        var changed = false
        val builder = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((key, element) in value) {
            if (key in SENSITIVE_JSON_KEYS) {
                builder[key] = kotlinx.serialization.json.JsonPrimitive(MASK)
                changed = true
            } else {
                val inner = element as? JsonObject
                builder[key] = if (inner != null) redactJsonKeys(inner) else element
            }
        }
        return if (changed) kotlinx.serialization.json.JsonObject(builder) else value
    }

    private fun urlEncoded(value: String): String = value
        .map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') "$ch" else "%%%02X".format(ch.code) }
        .joinToString("")

    private fun base64(value: String): String = try {
        java.util.Base64.getEncoder().encodeToString(value.encodeToByteArray())
    } catch (e: Exception) {
        ""
    }

    companion object {
        const val MASK = "••••••"
        val SENSITIVE_JSON_KEYS = setOf("password", "secret", "token", "authorization", "credential", "api_key", "private_key")
    }
}
