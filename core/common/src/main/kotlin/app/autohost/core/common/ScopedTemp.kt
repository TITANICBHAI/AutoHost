package app.autohost.core.common

import java.io.File

/**
 * Scoped temp directories: every run (or other unit of work) gets its own directory under [root] and everything
 * it created disappears when the scope closes (CONVENTIONS.md: temp files always via ScopedTemp). Run artifacts
 * live in buckets: inputs (bundle, read-only), outputs (evidence, exports) — and temp, which is this.
 */
class ScopedTemp(private val root: File) : AutoCloseable {
    private val created = mutableListOf<File>()
    private val lock = Any()

    init {
        root.mkdirs()
    }

    /** New unique directory named [name] (suffixed to stay unique). Caller cleans up via [close]. */
    fun newDir(name: String): File {
        val dir = unique(name)
        check(dir.mkdirs() || dir.isDirectory) { "could not create temp dir ${dir.path}" }
        synchronized(lock) { created.add(dir) }
        return dir
    }

    fun newFile(name: String, bytes: ByteArray = ByteArray(0)): File {
        val file = unique(name)
        file.writeBytes(bytes)
        synchronized(lock) { created.add(file) }
        return file
    }

    private fun unique(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var n = 0
        var candidate = File(root, "$safe-$n")
        while (candidate.exists()) {
            n++
            candidate = File(root, "$safe-$n")
        }
        return candidate
    }

    /** Deletes everything created through this scope, then the root itself. Idempotent. */
    override fun close() {
        synchronized(lock) {
            for (f in created.asReversed()) f.deleteRecursively()
            created.clear()
        }
        root.deleteRecursively()
    }

    companion object {
        /** Best-effort recursive delete, safe on already-deleted files. */
        fun deleteRecursively(file: File) {
            file.deleteRecursively()
        }
    }
}
