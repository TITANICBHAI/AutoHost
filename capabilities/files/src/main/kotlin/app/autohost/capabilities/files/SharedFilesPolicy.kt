package app.autohost.capabilities.files

/**
 * P8-07: pure policy for the `files.shared` capability (plan §6.1 W2: "Read/write user-picked
 * folders (SAF), MediaStore").
 *
 * The grant scope for `files.shared` is:
 * ```
 * { "trees": ["content://…treeUri…", …], "media": "bool?" }
 * ```
 * - Every SAF op names a `tree` that must literally appear in the granted list (fail closed:
 *   unknown URI → SCOPE_VIOLATION). Actual containment of relative paths inside the tree is
 *   enforced a second time by [safeRelative] and, on Android, by DocumentsContract itself.
 * - MediaStore methods additionally need `"media": true` in the scope; runtime read permission
 *   is checked lazily at call time (P8-14) because Android's rules differ per API level.
 *
 * Size caps protect the worker from a runaway read/write; the envelope carries `truncated` for
 * reads larger than the cap instead of failing (honest, matches net.http's behaviour).
 */
object SharedFilesPolicy {

    const val MAX_TRANSFER_BYTES = 5 * 1024 * 1024 // 5 MB per op
    const val MAX_LIST_ENTRIES = 2_000

    sealed class TreeVerdict {
        /** The named tree is granted — carries the exact granted URI string. */
        data class Allowed(val treeUri: String) : TreeVerdict()

        /** The grant exists but this tree is not in it. */
        data object NotGranted : TreeVerdict()

        /** No `files.shared` grant at all. */
        data object NoGrant : TreeVerdict()
    }

    fun treeVerdict(grant: kotlinx.serialization.json.JsonObject?, tree: String?): TreeVerdict {
        if (tree.isNullOrBlank()) return TreeVerdict.NotGranted
        if (grant == null) return TreeVerdict.NoGrant
        val trees = (grant["trees"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: return TreeVerdict.NoGrant
        return if (tree in trees) TreeVerdict.Allowed(tree) else TreeVerdict.NotGranted
    }

    /** MediaStore ops need the explicit `media: true` scope flag. */
    fun mediaAllowed(grant: kotlinx.serialization.json.JsonObject?): Boolean =
        (grant?.get("media") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() == true

    /**
     * Relative paths inside a tree: forward-slash separated, no `..` escaping, no absolute or
     * scheme-bearing input. Empty string = the tree root. Returns null when unsafe.
     */
    fun safeRelative(path: String?): List<String>? {
        if (path.isNullOrBlank()) return emptyList()
        if (path.startsWith("/") || path.contains("\\") || path.contains(":")) return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return null
        if (segments.any { it.length > 255 }) return null
        return segments
    }

    /** Reads over the cap are truncated and reported honestly; callers decide whether to fail. */
    fun readPlan(bytesAvailable: Long): ReadPlan = when {
        bytesAvailable <= MAX_TRANSFER_BYTES -> ReadPlan.Fit(bytesAvailable.toInt())
        else -> ReadPlan.Truncated(MAX_TRANSFER_BYTES)
    }

    sealed class ReadPlan {
        data class Fit(val bytes: Int) : ReadPlan()
        data class Truncated(val bytes: Int) : ReadPlan()
    }
}
