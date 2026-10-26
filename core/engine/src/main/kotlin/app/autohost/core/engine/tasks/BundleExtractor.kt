package app.autohost.core.engine.tasks

import app.autohost.core.common.AhJson
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * P2-02: safe extraction + integrity hashing + read-only install of `.aatask` bundles (plan §8).
 *
 * Fail-closed rules:
 *  - the zip must contain `task.json` (or `task.yaml` is NOT accepted — manifest is JSON only);
 *  - entries whose resolved path escapes the staging dir (zip-slip), whose name is absolute, contains `..`
 *    or `\0`, or that are suspiciously large are rejected with [BundleException];
 *  - hard caps: [MAX_ENTRIES], [MAX_TOTAL_BYTES], [MAX_ENTRY_BYTES] — no decompression bombs;
 *  - install() moves the staged dir under `tasksRoot/<taskId>/<version>/` and makes it read-only; bytes of an
 *    installed version are immutable (a re-install of the same version must first remove it).
 */
class BundleExtractor(
    private val stagingRoot: File,
    private val tasksRoot: File,
) {

    class BundleException(message: String, val rule: String) : Exception(message)

    data class Staged(
        val dir: File,
        val sha256: String,
        val manifest: String, // raw task.json text
        val taskId: String,
        val version: String,
        val entryPath: String,
    )

    fun stage(source: InputStream, bundleName: String): Staged {
        val staging = File(stagingRoot, "import-${System.identityHashCode(source)}-${bundleName.hashCode()}")
        staging.deleteRecursively()
        staging.mkdirs() || throw BundleException("cannot create staging dir", "IO")
        var sha = MessageDigest.getInstance("SHA-256")
        var sawManifest = false
        var manifestText: String? = null
        var taskId: String? = null
        var version: String? = null
        var entryPath: String? = null
        var entries = 0
        var totalBytes = 0L

        source.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    if (entries > MAX_ENTRIES) throw BundleException("bundle has more than $MAX_ENTRIES entries", "TOO_MANY_ENTRIES")
                    val name = entry.name
                    if (name.isEmpty() || name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) {
                        throw BundleException("unsafe zip entry name: \"$name\"", "UNSAFE_PATH")
                    }
                    val normalized = File(name).invariantSeparatorsPath
                    if (normalized.split('/').contains("..")) {
                        throw BundleException("zip entry escapes the bundle root: \"$name\"", "ZIP_SLIP")
                    }
                    if (entry.isDirectory) {
                        File(staging, normalized).mkdirs()
                        continue
                    }
                    val target = File(staging, normalized)
                    // defence in depth: resolved path must stay inside staging
                    if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        throw BundleException("zip entry escapes the bundle root: \"$name\"", "ZIP_SLIP")
                    }
                    target.parentFile?.mkdirs()
                    var written = 0L
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > MAX_ENTRY_BYTES) throw BundleException("entry \"$name\" exceeds $MAX_ENTRY_BYTES bytes", "ENTRY_TOO_LARGE")
                            out.write(buf, 0, n)
                        }
                    }
                    totalBytes += written
                    if (totalBytes > MAX_TOTAL_BYTES) throw BundleException("bundle exceeds $MAX_TOTAL_BYTES bytes", "BUNDLE_TOO_LARGE")
                    sha.update(name.encodeToByteArray())
                    sha.update(":".encodeToByteArray())
                    sha.update(written.toString().encodeToByteArray())
                    sha.update("\n".encodeToByteArray())
                    if (name == MANIFEST_ENTRY) {
                        sawManifest = true
                        manifestText = target.readText()
                    }
                    zip.closeEntry()
                }
            }
        }

        if (!sawManifest) throw BundleException("bundle has no task.json", "NO_MANIFEST")
        val text = manifestText!!
        val (manifest, validation) = ManifestValidator.validateJson(text)
        if (!validation.ok) {
            val first = validation.errors.first()
            throw BundleException("manifest invalid — ${first.describe()}", "MANIFEST_INVALID")
        }
        val m = manifest!!
        taskId = m.id
        version = m.version
        entryPath = m.runtime.entry
        if (!File(staging, entryPath).isFile) {
            throw BundleException("runtime entry \"$entryPath\" is missing from the bundle", "ENTRY_MISSING")
        }
        if (m.ui.file != null && !File(staging, m.ui.file).isFile) {
            throw BundleException("ui file \"${m.ui.file}\" is missing from the bundle", "UI_FILE_MISSING")
        }
        val digest = sha.digest().joinToString("") { "%02x".format(it) }
        return Staged(staging, digest, text, taskId, version, entryPath)
    }

    /** Move the staged bundle to its read-only home. Same version already present → refuse (remove first). */
    fun install(staged: Staged): File {
        val destination = File(File(tasksRoot, staged.taskId), staged.version)
        if (destination.exists()) {
            throw BundleException("version ${staged.version} of ${staged.taskId} is already installed", "VERSION_EXISTS")
        }
        destination.parentFile?.mkdirs() || throw BundleException("cannot create task dir", "IO")
        if (!staged.dir.renameTo(destination)) {
            // cross-device fallback: copy then clean
            staged.dir.copyRecursively(destination, overwrite = true)
            staged.dir.deleteRecursively()
        }
        makeReadOnly(destination)
        return destination
    }

    private fun makeReadOnly(root: File) {
        root.walkTopDown().forEach {
            it.setWritable(false, false)
            if (it.isDirectory) it.setExecutable(true, false) // keep dirs listable for the worker
        }
    }

    companion object {
        const val MANIFEST_ENTRY = "task.json"
        const val MAX_ENTRIES = 2_000
        const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
