package app.autohost.core.engine.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P11-08 (ADR-082): the backup document codec — tasks + profiles, minus secrets, by construction
 * (plan §18 P11 "backup/restore (tasks + profiles minus secrets)"; plan §21-13 "secrets never
 * exported").
 *
 * Document v1:
 * ```
 * {"schema":1, "kind":"autohost-backup", "created_at":"<iso>", "app_version":"<semver|host>",
 *  "tasks":[{"id":"…", "manifest":"<task.json verbatim>", "sha256":"<bundle hash or empty>",
 *             "bundle_b64":"<the .aatask zip, base64 — present unless the task came from a dev folder>"}],
 *  "profiles":[{"id":"…", "task_id":"…", "name":"…", "values":{…}, "secret_names":["…"], "target":{…}?}]}
 * ```
 *
 * Secrets appear ONLY as their NAMES (`secret_names`) so restore knows what to re-ask; the codec
 * REFUSES any document that carries a `secrets` key anywhere (defense against a hand-edited or
 * third-party backup smuggling them in). Restore planning is pure: the caller resolves each row
 * against its own stores and applies the returned action.
 */
object BackupCodec {

    const val SCHEMA = 1
    const val KIND = "autohost-backup"
    /** Never allowed inside a backup document — not at the root, not nested. */
    private val FORBIDDEN_KEYS = setOf("secrets", "secret_blobs", "secret_values", "ciphertext")

    private val TOP_KEYS = setOf("schema", "kind", "created_at", "app_version", "tasks", "profiles")
    private val TASK_KEYS = setOf("id", "manifest", "sha256", "bundle_b64")
    private val PROFILE_KEYS = setOf("id", "task_id", "name", "values", "secret_names", "target")

    data class BackupTask(
        val id: String,
        val manifest: String,
        val sha256: String,
        /** The re-installable bundle, base64; null for dev-folder tasks (an honest gap, not a silent one). */
        val bundleB64: String? = null,
    )
    data class BackupProfile(
        val id: String,
        val taskId: String,
        val name: String,
        val values: JsonObject,
        val secretNames: List<String>,
        val target: JsonObject?,
    )

    data class Document(
        val createdAt: String,
        val appVersion: String?,
        val tasks: List<BackupTask>,
        val profiles: List<BackupProfile>,
    )

    data class Issue(val path: String, val code: String, val message: String)

    data class EncodeResult(val json: String?, val issues: List<Issue>) {
        val ok: Boolean get() = json != null
    }

    data class DecodeResult(val document: Document?, val issues: List<Issue>) {
        val ok: Boolean get() = document != null
    }

    // ---- encode ------------------------------------------------------------------------------

    fun encode(document: Document): EncodeResult {
        val issues = ArrayList<Issue>()
        val tasks = ArrayList<JsonObject>()
        for (task in document.tasks) {
            if (task.id.isBlank()) issues += Issue("/tasks", "BLANK_ID", "a task row has a blank id")
            if (task.manifest.isBlank()) issues += Issue("/tasks/${task.id}", "NO_MANIFEST", "task ${task.id} has no manifest text")
            tasks += kotlinx.serialization.json.buildJsonObject {
                put("id", JsonPrimitive(task.id))
                put("manifest", JsonPrimitive(task.manifest))
                put("sha256", JsonPrimitive(task.sha256))
                task.bundleB64?.let { put("bundle_b64", JsonPrimitive(it)) }
            }
        }
        val profiles = ArrayList<JsonObject>()
        for (profile in document.profiles) {
            if (profile.id.isBlank()) issues += Issue("/profiles", "BLANK_ID", "a profile row has a blank id")
            profiles += kotlinx.serialization.json.buildJsonObject {
                put("id", JsonPrimitive(profile.id))
                put("task_id", JsonPrimitive(profile.taskId))
                put("name", JsonPrimitive(profile.name))
                put("values", profile.values)
                put("secret_names", JsonArray(profile.secretNames.map { JsonPrimitive(it) }))
                profile.target?.let { put("target", it) }
            }
        }
        if (issues.any { it.code == "BLANK_ID" || it.code == "NO_MANIFEST" }) return EncodeResult(null, issues)
        val json = kotlinx.serialization.json.buildJsonObject {
            put("schema", JsonPrimitive(SCHEMA))
            put("kind", JsonPrimitive(KIND))
            put("created_at", JsonPrimitive(document.createdAt))
            document.appVersion?.let { put("app_version", JsonPrimitive(it)) }
            put("tasks", JsonArray(tasks))
            put("profiles", JsonArray(profiles))
        }.toString()
        // Belt and braces: the encoded document itself must be free of forbidden keys.
        return if (containsForbiddenKey(json)) {
            EncodeResult(null, listOf(Issue("/", "SECRET_SHAPED_KEY", "the encoded document contains a forbidden key")))
        } else {
            EncodeResult(json, issues)
        }
    }

    // ---- decode ------------------------------------------------------------------------------

    fun decode(text: String): DecodeResult {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }
        if (root !is JsonObject) {
            return DecodeResult(null, listOf(Issue("/", "TYPE", "the backup must be a JSON object")))
        }
        val issues = ArrayList<Issue>()
        if (containsForbiddenKey(root)) {
            return DecodeResult(null, listOf(Issue("/", "SECRET_SHAPED_KEY", "backups never carry secrets — refusing a document that does")))
        }
        for (key in root.keys) {
            if (key !in TOP_KEYS) issues += Issue("/$key", "UNKNOWN_KEY", "\"$key\" is not part of backup v1")
        }
        val schema = (root["schema"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (schema != SCHEMA) issues += Issue("/schema", "SCHEMA", "\"schema\" must be $SCHEMA (got $schema)")
        val kind = (root["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (kind != KIND) issues += Issue("/kind", "KIND", "\"kind\" must be \"$KIND\"")
        val createdAt = (root["created_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (createdAt.isNullOrBlank()) issues += Issue("/created_at", "REQUIRED", "\"created_at\" is required")
        val appVersion = (root["app_version"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val tasks = ArrayList<BackupTask>()
        val taskRows = root["tasks"] as? JsonArray ?: JsonArray(emptyList())
        taskRows.forEachIndexed { i, element ->
            val obj = element as? JsonObject ?: run {
                issues += Issue("/tasks/$i", "TYPE", "each task row must be an object")
                return@forEachIndexed
            }
            for (key in obj.keys) {
                if (key !in TASK_KEYS) issues += Issue("/tasks/$i/$key", "UNKNOWN_KEY", "\"$key\" is not part of a task row")
            }
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val manifest = (obj["manifest"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id == null || manifest == null) {
                issues += Issue("/tasks/$i", "REQUIRED", "task rows need \"id\" and \"manifest\"")
                return@forEachIndexed
            }
            tasks += BackupTask(
                id = id,
                manifest = manifest,
                sha256 = (obj["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                bundleB64 = (obj["bundle_b64"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }

        val profiles = ArrayList<BackupProfile>()
        val profileRows = root["profiles"] as? JsonArray ?: JsonArray(emptyList())
        profileRows.forEachIndexed { i, element ->
            val obj = element as? JsonObject ?: run {
                issues += Issue("/profiles/$i", "TYPE", "each profile row must be an object")
                return@forEachIndexed
            }
            for (key in obj.keys) {
                if (key !in PROFILE_KEYS) issues += Issue("/profiles/$i/$key", "UNKNOWN_KEY", "\"$key\" is not part of a profile row")
            }
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val taskId = (obj["task_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id == null || taskId == null || name == null) {
                issues += Issue("/profiles/$i", "REQUIRED", "profile rows need \"id\", \"task_id\" and \"name\"")
                return@forEachIndexed
            }
            val values = obj["values"] as? JsonObject ?: JsonObject(emptyMap())
            val secretNames = (obj["secret_names"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: emptyList()
            profiles += BackupProfile(id, taskId, name, values, secretNames, obj["target"] as? JsonObject)
        }

        if (issues.any { it.code in setOf("TYPE", "REQUIRED", "SCHEMA", "KIND", "SECRET_SHAPED_KEY") }) {
            return DecodeResult(null, issues)
        }
        return DecodeResult(Document(createdAt ?: "", appVersion, tasks, profiles), issues)
    }

    // ---- restore planning --------------------------------------------------------------------

    enum class TaskAction { RESTORE_NEW, OVERWRITE, SKIP }

    enum class ProfileAction { RESTORE_NEW, SKIP_EXISTS, SECRET_CLEARED }

    data class TaskPlan(val task: BackupTask, val action: TaskAction, val detail: String)
    data class ProfilePlan(val profile: BackupProfile, val action: ProfileAction, val detail: String)

    data class RestorePlan(val tasks: List<TaskPlan>, val profiles: List<ProfilePlan>)

    /**
     * The per-row restore decisions: tasks overwrite only when [allowOverwrite] (the caller
     * confirms); profiles always restore as new rows when the id is free, keep out when taken,
     * and ALWAYS report their secrets as cleared (the backup never carried them).
     */
    fun planRestore(
        document: Document,
        installedTaskIds: Set<String>,
        installedProfileIds: Set<String>,
        allowOverwrite: Boolean,
    ): RestorePlan {
        val taskPlans = document.tasks.map { task ->
            when {
                // No bundle bytes in the backup (a dev-folder task) and nothing installed: the
                // manifest alone cannot resurrect the code — an honest skip, never a guess.
                task.id !in installedTaskIds && task.bundleB64 == null ->
                    TaskPlan(task, TaskAction.SKIP, "no bundle in the backup (dev-folder task) — re-add the dev folder")
                task.id !in installedTaskIds -> TaskPlan(task, TaskAction.RESTORE_NEW, "not installed")
                allowOverwrite && task.bundleB64 != null -> TaskPlan(task, TaskAction.OVERWRITE, "installed — overwrite confirmed")
                task.id in installedTaskIds -> TaskPlan(task, TaskAction.SKIP, "already installed; enable overwrite to replace")
                else -> TaskPlan(task, TaskAction.SKIP, "already installed without a bundle in the backup")
            }
        }
        val profilePlans = document.profiles.map { profile ->
            when {
                profile.id !in installedProfileIds -> ProfilePlan(
                    profile,
                    ProfileAction.RESTORE_NEW,
                    if (profile.secretNames.isEmpty()) "no secrets to re-enter" else "secrets to re-enter: ${profile.secretNames.joinToString()}",
                )
                else -> ProfilePlan(profile, ProfileAction.SKIP_EXISTS, "profile id already exists")
            }
        }
        return RestorePlan(taskPlans, profilePlans)
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun containsForbiddenKey(text: String): Boolean =
        FORBIDDEN_KEYS.any { text.contains("\"$it\"") }

    private fun containsForbiddenKey(element: kotlinx.serialization.json.JsonElement): Boolean = when (element) {
        is JsonObject -> element.keys.any { it in FORBIDDEN_KEYS } || element.values.any { containsForbiddenKey(it) }
        is JsonArray -> element.any { containsForbiddenKey(it) }
        else -> false
    }
}
