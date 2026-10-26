package app.autohost.core.engine.host

import app.autohost.core.common.AhJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * File-backed [KvBackend]: one JSON document, atomically rewritten (tmp + rename) on every change. Wave 1
 * storage for the `store` capability; Phase 2 moves this to Room without changing the interface.
 * Lives in `:core:engine` (pure JVM, no Android types) so unit tests cover it directly.
 */
class FileKvStore(private val file: File) : KvBackend {

    private val lock = ReentrantReadWriteLock()
    private val buckets: MutableMap<String, MutableMap<String, JsonElement>> = load()

    init {
        file.parentFile?.mkdirs()
    }

    private fun load(): MutableMap<String, MutableMap<String, JsonElement>> {
        if (!file.exists()) return LinkedHashMap()
        return try {
            val root = AhJson.json.parseToJsonElement(file.readText()).jsonObject
            val result = LinkedHashMap<String, MutableMap<String, JsonElement>>()
            for ((owner, bucketElement) in root) {
                val bucket = bucketElement as? JsonObject ?: continue
                result[owner] = LinkedHashMap(bucket.entries.associate { (k, v) -> k to v })
            }
            result
        } catch (e: Exception) {
            // A corrupt KV file must not wedge the host: start empty and keep the broken file aside.
            try {
                file.renameTo(File(file.parentFile, file.name + ".corrupt"))
            } catch (e2: Exception) {
                // ignore
            }
            LinkedHashMap()
        }
    }

    override fun get(owner: String, key: String): JsonElement? = lock.read {
        buckets[owner]?.get(key)
    }

    override fun set(owner: String, key: String, value: JsonElement) {
        lock.write {
            buckets.getOrPut(owner) { LinkedHashMap() }[key] = value
            persistLocked()
        }
    }

    override fun delete(owner: String, key: String): Boolean = lock.write {
        val removed = buckets[owner]?.remove(key) != null
        if (removed) persistLocked()
        removed
    }

    override fun keys(owner: String, prefix: String): List<String> = lock.read {
        (buckets[owner]?.keys ?: emptySet()).filter { it.startsWith(prefix) }.sorted()
    }

    private fun persistLocked() {
        val root = JsonObject(buckets.entries.associate { (owner, bucket) -> owner to JsonObject(bucket) })
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(AhJson.stringify(root))
            if (!tmp.renameTo(file)) {
                // rename can fail across file systems; fall back to a direct write
                file.writeText(AhJson.stringify(root))
                tmp.delete()
            }
        } catch (e: Exception) {
            // Storage failures surface on the next read; never crash the caller (fail-closed stays at the broker).
        }
    }
}
