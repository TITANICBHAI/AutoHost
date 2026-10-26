package app.autohost.core.engine.tasks

import app.autohost.core.common.AhJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * P2-03: structured result of importing one `.aatask` bundle (plan §8.2 — "Invalid ⇒ not installed").
 * Persisted as JSON on the TaskVersion row so the UI can re-show the report at any time.
 */
@Serializable
data class ImportReport(
    val source: String,
    val bundleName: String,
    val sha256: String,
    val taskId: String?,
    val taskName: String?,
    val version: String?,
    val manifestSummary: String? = null,
    val errors: List<Issue> = emptyList(),
    val warnings: List<Issue> = emptyList(),
    val scan: ScanSummary? = null,
    val compat: List<CompatRow> = emptyList(),
    val grantsDiff: List<GrantDiffRow> = emptyList(),
    val installed: Boolean = false,
) {
    @Serializable
    data class Issue(val path: String, val rule: String, val message: String)

    /** Python import scan outcome (P2-13), attached when a python bundle was scanned. */
    @Serializable
    data class ScanSummary(
        val files: Int,
        val stdlib: Int,
        val bundled: Int,
        val vendored: Int,
        val unavailable: List<String> = emptyList(),
        val desktopOnly: List<DesktopHit> = emptyList(),
        /** P8-12: files using the raw Android bridge — the import guard checks the declaration. */
        val rawBridge: List<String> = emptyList(),
    )

    @Serializable
    data class DesktopHit(val module: String, val file: String, val suggestion: String)

    /** One CompatReport row (plan §15): a requirement and whether this device can satisfy it. */
    @Serializable
    data class CompatRow(
        val kind: String, // capability | app | api
        val id: String,
        val status: String, // AVAILABLE | NEEDS_SETUP | UNSUPPORTED | DENIED | MISSING
        val required: Boolean,
        val detail: String? = null,
        val fixAction: String? = null,
    )

    /** Dev-mode permission diff (plan §8.3): what changed vs the currently installed version. */
    @Serializable
    data class GrantDiffRow(val capability: String, val change: String, val detail: String? = null) {
        companion object {
            const val ADDED = "added"
            const val REMOVED = "removed"
            const val WIDENED = "widened"
            const val NARROWED = "narrowed"
        }
    }

    val blocking: Boolean get() = errors.isNotEmpty()
    val needsReview: Boolean get() = grantsDiff.isNotEmpty()

    fun toJson(): JsonObject = AhJson.json.decodeFromString(JsonObject.serializer(), AhJson.json.encodeToString(serializer(), this))

    companion object {
        fun fromJson(json: JsonObject): ImportReport? = try {
            AhJson.json.decodeFromJsonElement(serializer(), json)
        } catch (e: Exception) {
            null
        }

        fun fromValidator(source: String, bundleName: String, sha256: String, result: ManifestValidator.Result): ImportReport =
            ImportReport(
                source = source,
                bundleName = bundleName,
                sha256 = sha256,
                taskId = null,
                taskName = null,
                version = null,
                errors = result.errors.map { Issue(it.path, it.rule, it.message) },
                warnings = result.warnings.map { Issue(it.path, it.rule, it.message) },
            )
    }
}
