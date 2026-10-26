package app.autohost.core.data

import app.autohost.core.common.Clock
import app.autohost.core.data.db.SecretBlobDao
import app.autohost.core.data.db.SecretBlobEntity
import app.autohost.core.data.secrets.Redactor
import app.autohost.core.data.secrets.SecretRegistry
import app.autohost.core.data.secrets.SecretStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStoreTest {

    private class FakeAead : app.autohost.core.data.secrets.AeadCipher {
        override val keyAlias = "fake"
        private val prefix = "enc:".encodeToByteArray()

        override fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> = prefix + plaintext.reversedArray() to byteArrayOf(1, 2, 3)

        override fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
            require(iv.size == 3) { "tampered iv" }
            require(ciphertext.size >= prefix.size && ciphertext.copyOfRange(0, prefix.size).contentEquals(prefix)) { "tampered" }
            return ciphertext.copyOfRange(prefix.size, ciphertext.size).reversedArray()
        }
    }

    private class FakeDao : SecretBlobDao {
        val rows = ConcurrentHashMap<String, SecretBlobEntity>()
        private fun idOf(profileId: String, key: String) = "$profileId/$key"

        override suspend fun byKey(profileId: String, key: String): SecretBlobEntity? = rows[idOf(profileId, key)]

        override suspend fun allOf(profileId: String): List<SecretBlobEntity> =
            rows.values.filter { it.profileId == profileId }

        override suspend fun upsert(blob: SecretBlobEntity) {
            rows[idOf(blob.profileId, blob.key)] = blob
        }

        override suspend fun deleteForProfile(profileId: String) {
            rows.keys.removeIf { it.startsWith("$profileId/") }
        }
    }

    private class FixedClock : Clock {
        override fun nowMs(): Long = 1_700_000_000_000
    }

    @Test
    fun `put encrypts and get decrypts; values never appear as plaintext rows`() = runTest {
        val dao = FakeDao()
        val registry = SecretRegistry()
        val store = SecretStore(dao, FakeAead(), registry, FixedClock())

        store.put("p1", "password", "s3cret-value")
        assertTrue(registry.snapshot().contains("s3cret-value"))
        // blob must not contain the plaintext
        val blob = dao.byKey("p1", "password")!!
        assertFalse(String(blob.ciphertext).contains("s3cret-value"))

        assertEquals("s3cret-value", store.get("p1", "password"))
        assertNull(store.get("p1", "missing"))
    }

    @Test
    fun `allOf and keysOf expose the profile snapshot; deleteForProfile removes blobs`() = runTest {
        val dao = FakeDao()
        val store = SecretStore(dao, FakeAead(), SecretRegistry(), FixedClock())
        store.put("p1", "user", "alice")
        store.put("p1", "password", "hunter2")
        store.put("p2", "password", "other")

        val all = store.allOf("p1")
        assertEquals(mapOf("user" to "alice", "password" to "hunter2"), all)
        assertEquals(listOf("user", "password").sorted(), store.keysOf("p1").sorted())

        store.deleteForProfile("p1")
        assertTrue(store.keysOf("p1").isEmpty())
        assertEquals("other", store.get("p2", "password"))
    }

    @Test
    fun `tampered blob decrypts to null (fail closed, no exception escapes)`() = runTest {
        val dao = FakeDao()
        val store = SecretStore(dao, FakeAead(), SecretRegistry(), FixedClock())
        store.put("p1", "password", "topsecret")
        val blob = dao.byKey("p1", "password")!!
        dao.upsert(blob.copy(ciphertext = "garbage".encodeToByteArray()))
        assertNull(store.get("p1", "password"))
    }

    @Test
    fun `redactor scrubs exact values and common encodings`() {
        val registry = SecretRegistry()
        registry.register("hunter2")
        val redactor = Redactor(registry)

        assertEquals("••••••", redactor.redact("hunter2"))
        assertEquals("login=•••••• ok", redactor.redact("login=hunter2 ok"))
        // URL-encoded form of a secret containing special characters is scrubbed too
        val registry2 = SecretRegistry()
        registry2.register("p@ss word")
        val redactor2 = Redactor(registry2)
        assertEquals("q=••••••", redactor2.redact("q=p%40ss%20word"))
        // JSON key scrubbing
        val json = kotlinx.serialization.json.buildJsonObject {
            put("username", kotlinx.serialization.json.JsonPrimitive("alice"))
            put("password", kotlinx.serialization.json.JsonPrimitive("whatever"))
        }
        val scrubbed = redactor.redactJsonKeys(json)
        assertEquals("alice", (scrubbed["username"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(Redactor.MASK, (scrubbed["password"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `canary secret never survives a redact pass over mixed output`() {
        val registry = SecretRegistry()
        registry.register("CANARY-canary-123")
        val redactor = Redactor(registry)
        val logLine = "audit: capability=device.http args={\"url\":\"https://x/?token=CANARY-canary-123\"}"
        assertFalse(redactor.redact(logLine).contains("CANARY-canary-123"))
    }
}
