package app.autohost.core.engine.tasks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * P11-05 (ADR-078): the task repository/index — the pure half of "task repository/index import"
 * (plan §18 P11). An `index.json` v1 document lists shippable task bundles (a local path or an
 * HTTPS URL — this class never fetches) with versions, an optional integrity hash and an
 * optional minimum host API. Selection ([select]) compares an index entry against the installed
 * manifest so the Task Studio / CLI (part 2) can show Install / Upgrade / Up to date / Rejected
 * without deciding anything itself. Pure: JSON in, decisions out, no IO — JVM-testable and
 * safe to reuse from any caller.
 */
object RepoIndex {

    const val SCHEMA = 1
    const val SHA_HEX_LENGTH = 64

    private val ID_REGEX = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*)+$")
    private val SEMVER_REGEX =
        Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
    private val SHA_REGEX = Regex("^[0-9a-fA-F]{$SHA_HEX_LENGTH}$")
    private val RUNTIME_TYPES = setOf("python", "flow", "javascript")
    private val ENTRY_KEYS = setOf("id", "version", "title", "runtime", "source", "sha256", "min_host_api", "notes")

    /** One shippable task bundle. [source] is a local path or URL — fetching is the caller's job. */
    data class Entry(
        val id: String,
        val version: String,
        val title: String?,
        val runtime: String?,
        val source: String,
        val sha256: String?,
        val minHostApi: Int?,
        val notes: String?,
    )

    data class Index(
        val name: String?,
        val updatedAt: String?,
        val entries: List<Entry>,
    )

    data class Issue(val path: String, val code: String, val message: String)

    data class ParseResult(val index: Index?, val issues: List<Issue>) {
        val ok: Boolean get() = index != null
    }

    /** Parses an index document. A malformed root yields `index = null`; per-entry problems land in issues. */
    fun parse(text: String): ParseResult {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }
        if (root !is JsonObject) {
            return ParseResult(null, listOf(Issue("/", "TYPE", "the index document must be a JSON object")))
        }
        val issues = ArrayList<Issue>()
        val schema = (root["schema"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (schema != SCHEMA) {
            issues += Issue("/schema", "SCHEMA", "\"schema\" must be $SCHEMA (got $schema)")
        }
        val name = (root["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val updatedAt = (root["updated_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val tasks = root["tasks"] as? JsonArray
        if (tasks == null) {
            issues += Issue("/tasks", "REQUIRED", "\"tasks\" must be an array")
            return ParseResult(null, issues)
        }
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        tasks.forEachIndexed { i, element ->
            val obj = element as? JsonObject
            if (obj == null) {
                issues += Issue("/tasks/$i", "TYPE", "each entry must be an object")
                return@forEachIndexed
            }
            parseEntry(obj, i, issues)?.let { entry ->
                if (!seen.add(entry.id)) {
                    issues += Issue(
                        "/tasks/$i/id", "DUP_ID",
                        "duplicate id '${entry.id}' — the first occurrence wins, this entry is dropped",
                    )
                } else {
                    entries += entry
                }
            }
        }
        return ParseResult(Index(name, updatedAt, entries), issues)
    }

    private fun parseEntry(obj: JsonObject, i: Int, issues: MutableList<Issue>): Entry? {
        val path = "/tasks/$i"
        var valid = true
        for (key in obj.keys) {
            if (key !in ENTRY_KEYS) {
                issues += Issue("$path/$key", "UNKNOWN_KEY", "\"$key\" is not part of index v1")
            }
        }
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val id = str("id")
        if (id == null) {
            issues += Issue("$path/id", "REQUIRED", "entry needs an \"id\"")
            valid = false
        } else if (!ID_REGEX.matches(id)) {
            issues += Issue("$path/id", "PATTERN", "\"$id\" is not a reverse-DNS task id")
            valid = false
        }
        val version = str("version")
        if (version == null) {
            issues += Issue("$path/version", "REQUIRED", "entry needs a \"version\"")
            valid = false
        } else if (!SEMVER_REGEX.matches(version)) {
            issues += Issue("$path/version", "PATTERN", "\"$version\" is not strict semver (MAJOR.MINOR.PATCH)")
            valid = false
        }
        val source = str("source")
        if (source.isNullOrEmpty()) {
            issues += Issue("$path/source", "REQUIRED", "entry needs a \"source\" (a local path or an https URL)")
            valid = false
        }
        val sha = str("sha256")
        if (sha != null && !SHA_REGEX.matches(sha)) {
            issues += Issue("$path/sha256", "PATTERN", "\"sha256\" must be $SHA_HEX_LENGTH hex characters")
            valid = false
        }
        val runtime = str("runtime")
        if (runtime != null && runtime !in RUNTIME_TYPES) {
            issues += Issue("$path/runtime", "ENUM", "\"runtime\" must be one of $RUNTIME_TYPES")
            valid = false
        }
        val minApi = (obj["min_host_api"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (obj.containsKey("min_host_api") && (minApi == null || minApi < 1)) {
            issues += Issue("$path/min_host_api", "TYPE", "\"min_host_api\" must be a positive integer")
            valid = false
        }
        val title = str("title")
        if (title != null && title.length > 200) {
            issues += Issue("$path/title", "MAX_LENGTH", "\"title\" max 200 characters")
            valid = false
        }
        val notes = str("notes")
        if (notes != null && notes.length > 500) {
            issues += Issue("$path/notes", "MAX_LENGTH", "\"notes\" max 500 characters")
            valid = false
        }
        if (!valid) return null
        return Entry(
            id = id!!,
            version = version!!,
            title = title,
            runtime = runtime,
            source = source!!,
            sha256 = sha,
            minHostApi = minApi,
            notes = notes,
        )
    }

    /** What a caller should do with [entry] given the installed version and the host's API level. */
    sealed class Verdict {
        /** Not installed — offer the install. */
        object Install : Verdict()

        /** Installed at an older version — offer the upgrade. */
        data class Upgrade(val from: String, val to: String) : Verdict()

        /** Same version installed — nothing to do (a downgrade is a deliberate uninstall+install). */
        object UpToDate : Verdict()

        /** The entry cannot be offered: reason is a stable code (HOST_TOO_OLD / REPO_OLDER). */
        data class Reject(val reason: String, val message: String) : Verdict()
    }

    fun select(entry: Entry, installedVersion: String?, hostApi: Int): Verdict {
        val minApi = entry.minHostApi
        if (minApi != null && hostApi < minApi) {
            return Verdict.Reject(
                "HOST_TOO_OLD",
                "task ${entry.id} needs host API $minApi; this host is $hostApi",
            )
        }
        if (installedVersion == null) return Verdict.Install
        return when (SemVer.compare(entry.version, installedVersion)) {
            0 -> Verdict.UpToDate
            in Int.MIN_VALUE..-1 -> Verdict.Reject(
                "REPO_OLDER",
                "repo has ${entry.version} but $installedVersion is installed; downgrades need an explicit uninstall",
            )
            else -> Verdict.Upgrade(installedVersion, entry.version)
        }
    }

    /** Integrity check once the caller fetched a bundle: exact hex match, case-insensitive. */
    fun verifySha(expectedHex: String, actualHex: String): Boolean {
        if (expectedHex.length != SHA_HEX_LENGTH || actualHex.length != SHA_HEX_LENGTH) return false
        if (!SHA_REGEX.matches(expectedHex) || !SHA_REGEX.matches(actualHex)) return false
        return expectedHex.equals(actualHex, ignoreCase = true)
    }
}
