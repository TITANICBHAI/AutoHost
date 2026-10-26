package app.autohost.core.engine.host

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Backing for the `store` capability: namespaced key/value buckets (task KV and profile KV). Implementations:
 * in-memory for tests, file-backed in the app (Phase 2 moves this to Room).
 */
interface KvBackend {
    fun get(owner: String, key: String): JsonElement?

    fun set(owner: String, key: String, value: JsonElement)

    /** Returns true when a value existed and was removed. */
    fun delete(owner: String, key: String): Boolean

    /** Keys under [owner] with the given prefix, sorted. */
    fun keys(owner: String, prefix: String): List<String>
}

class InMemoryKvBackend : KvBackend {
    private val buckets = ConcurrentHashMap<String, ConcurrentHashMap<String, JsonElement>>()

    override fun get(owner: String, key: String): JsonElement? = buckets[owner]?.get(key)

    override fun set(owner: String, key: String, value: JsonElement) {
        buckets.getOrPut(owner) { ConcurrentHashMap() }[key] = value
    }

    override fun delete(owner: String, key: String): Boolean {
        val bucket = buckets[owner] ?: return false
        val removed = bucket.remove(key)
        return removed != null
    }

    override fun keys(owner: String, prefix: String): List<String> =
        (buckets[owner]?.keys ?: emptySet()).filter { it.startsWith(prefix) }.sorted()
}
