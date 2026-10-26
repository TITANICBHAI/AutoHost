package app.autohost.runtime.js

import java.io.File

/**
 * P11-01: JS entry + module resolution (the `JsSources` mirror of `FlowSources`). A JS task ships
 * its entry beside `task.json` (`runtime.entry`, e.g. `main.js`); when the entry is blank the
 * resolver falls back to [DEFAULT_ENTRY]. An explicit entry that does not exist must NOT silently
 * fall back (same rule as flows). ES-module imports from `lib/` are resolved through the
 * [moduleLoaderFor] seam: relative names only, containment-checked under the task folder,
 * size-capped. Every failure is a typed [JsSourceException]; the provider maps it to
 * FAILED:START_FAILED exactly like the flow runtime.
 */
object JsSources {

    const val DEFAULT_ENTRY = "main.js"
    const val LIB_DIR = "lib"

    /** Per-file source cap. A task bundle is small by definition; 512 KiB is generous for one file. */
    const val MAX_FILE_BYTES = 512 * 1024

    /** Upper bound on lib modules handed to the engine for one run. */
    const val MAX_LIB_FILES = 32

    /** Finds the entry file for [entry] (blank = [DEFAULT_ENTRY]). Null when nothing matches. */
    fun resolveEntryFile(taskPath: File, entry: String?): File? {
        val named = entry?.takeIf { it.isNotBlank() } ?: DEFAULT_ENTRY
        val file = File(taskPath, named)
        if (file.isFile) return file
        return null
    }

    /** Reads a source file with the size cap; [JsSourceException] on IO/size failures. */
    fun readSource(file: File): String {
        if (file.length() > MAX_FILE_BYTES) {
            throw JsSourceException(
                "JS source '${file.name}' is ${file.length()} bytes; the cap is $MAX_FILE_BYTES",
            )
        }
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw JsSourceException("cannot read JS file ${file.name}: ${e.message}", e)
        }
    }

    /**
     * Builds the module loader the engine uses for `import … from './lib/x.js'`. Rules:
     * relative names only (`./` or `../` prefix), path-traversal refused, resolved and
     * containment-checked under [taskPath], size-capped, `lib/` file-count capped. Returns null
     * for anything it refuses — the engine turns that into an honest module-resolution error.
     */
    fun moduleLoaderFor(taskPath: File): (String) -> String? {
        return loader@{ name: String ->
            if (name.isBlank()) return@loader null
            if (!(name.startsWith("./") || name.startsWith("../"))) return@loader null
            if (name.contains("\\")) return@loader null
            val resolved = try {
                File(taskPath, name).canonicalFile
            } catch (e: Exception) {
                return@loader null
            }
            val root = taskPath.canonicalFile
            if (!resolved.path.startsWith(root.path + File.separator)) return@loader null
            if (!resolved.isFile) return@loader null
            if (resolved.length() > MAX_FILE_BYTES) return@loader null
            val libDir = File(root, LIB_DIR)
            if (!resolved.path.startsWith(libDir.canonicalFile.path + File.separator)) return@loader null
            val count = libDir.walkTopDown().count { it.isFile }
            if (count > MAX_LIB_FILES) return@loader null
            try {
                readSource(resolved)
            } catch (e: JsSourceException) {
                null
            }
        }
    }
}

/** Typed source failure (missing entry handled by the provider; this covers read/size problems). */
class JsSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
