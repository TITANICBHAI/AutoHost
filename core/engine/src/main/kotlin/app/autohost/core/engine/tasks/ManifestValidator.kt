package app.autohost.core.engine.tasks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.autohost.core.model.TaskManifest
import app.autohost.core.common.AhJson

/**
 * P2-01: pure-JVM validator for task manifests (schemas/task.schema.json subset).
 *
 * The JSON Schema is the source of truth (CI validates samples with Python); this validator re-states the
 * schema's rules so the *phone* can reject broken bundles without a schema engine, and produces the precise
 * `ImportReport` the UI needs. Where the two could drift, golden tests feed the same fixtures to both.
 *
 * Rules enforced here (schema "additionalProperties": false unless noted):
 *  - required: schema, id, name, version, runtime{type,entry}
 *  - `schema` must be <= [HOST_SCHEMA_VERSION]; newer → HOST_TOO_OLD (imports refuse)
 *  - `id` reverse-DNS pattern; `version` strict semver; name length; description length
 *  - runtime cross rules: python→.py entry, flow→.yaml/.yml/.json, javascript→.js
 *  - needs.capabilities/optional: permission-shaped ids; needs.apps: package + optional min_version_code
 *  - config_schema/profile_schema: the schema's `$defs.objectSchema` subset (type, required, properties,
 *    per-property type/title/default/enum/min/max/x-secret/x-ui)
 *  - execution bounds (timeout 1..1440, idle >= 10, attempts 1..10, verification enum)
 *  - ui: mode required; declarative/html require `file`
 *  - unknown top-level keys rejected except `x-*` extensions
 */
object ManifestValidator {

    const val HOST_SCHEMA_VERSION = 1

    /** One validation problem: where it is (JSON pointer-ish path), a stable rule id, and a human message. */
    data class Issue(val path: String, val rule: String, val message: String) {
        fun describe(): String = if (path.isEmpty()) message else "$path: $message"
    }

    data class Result(val errors: List<Issue>, val warnings: List<Issue> = emptyList()) {
        val ok: Boolean get() = errors.isEmpty()
        companion object {
            fun ok() = Result(emptyList())
        }
    }

    private val ID_REGEX = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*)+$")
    private val SEMVER_REGEX = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
    private val CAP_ID_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*$")
    private val RUNTIME_TYPES = setOf("python", "flow", "javascript")
    private val PROPERTY_TYPES = setOf("string", "integer", "number", "boolean", "array", "object")
    private val VERIFICATION_MODES = setOf("off", "final", "checkpoints", "strict")

    /** Entry-extension requirements per runtime type; null entry rule = any non-empty. */
    private val ENTRY_RULES = mapOf(
        "python" to Regex("\\.py$"),
        "flow" to Regex("\\.(ya?ml|json)$"),
        "javascript" to Regex("\\.js$"),
    )

    /** Top-level keys the schema allows (plus any `x-` extension). */
    private val TOP_LEVEL_KEYS = setOf(
        "schema", "id", "name", "version", "description", "author", "host_api", "runtime",
        "needs", "uses_packs", "python_deps", "config_schema", "profile_schema",
        "stages", "triggers", "execution", "ui",
    )

    /** Validate raw JSON text → parse + structural validation. Parse failures are errors, not exceptions. */
    fun validateJson(text: String): Pair<TaskManifest?, Result> {
        val root = try {
            AhJson.json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null to Result(listOf(Issue("", "JSON_MALFORMED", "task.json is not a valid JSON object")))
        val manifest = try {
            AhJson.json.decodeFromString(TaskManifest.serializer(), text)
        } catch (e: Exception) {
            null
        }
        return manifest to validate(root, manifest)
    }

    /** Validate a parsed manifest against the raw JSON tree (the tree is needed for unknown-key checks). */
    fun validate(root: JsonObject, manifest: TaskManifest?): Result {
        val errors = ArrayList<Issue>()
        val warnings = ArrayList<Issue>()

        for (key in root.keys) {
            if (key !in TOP_LEVEL_KEYS && !key.startsWith("x-")) {
                errors += Issue("", "UNKNOWN_KEY", "unknown manifest key \"$key\"")
            }
        }

        // schema version
        val schemaVersion = (root["schema"] as? JsonPrimitive)?.let { (it.content.toIntOrNull()) }
        when {
            schemaVersion == null -> errors += Issue("/schema", "TYPE", "\"schema\" must be an integer")
            schemaVersion > HOST_SCHEMA_VERSION -> errors += Issue(
                "/schema", "HOST_TOO_OLD",
                "manifest schema $schemaVersion is newer than this host ($HOST_SCHEMA_VERSION); update AutoHost",
            )
            schemaVersion < 1 -> errors += Issue("/schema", "MINIMUM", "\"schema\" must be >= 1")
        }

        // id / name / version
        val id = (root["id"] as? JsonPrimitive)?.content
        if (id == null || !ID_REGEX.matches(id)) {
            errors += Issue("/id", "PATTERN", "\"id\" must be reverse-DNS like com.example.my-task")
        }
        val name = (root["name"] as? JsonPrimitive)?.content
        when {
            name == null -> errors += Issue("/name", "REQUIRED", "\"name\" is required")
            name.isEmpty() || name.length > 80 -> errors += Issue("/name", "LENGTH", "\"name\" must be 1..80 characters")
        }
        val version = (root["version"] as? JsonPrimitive)?.content
        if (version == null || !SEMVER_REGEX.matches(version)) {
            errors += Issue("/version", "PATTERN", "\"version\" must be semver like 1.2.3 (allowing -meta/+build)")
        }

        (root["description"] as? JsonPrimitive)?.content?.let {
            if (it.length > 500) errors += Issue("/description", "MAX_LENGTH", "\"description\" max 500 characters")
        }

        // runtime
        val runtime = root["runtime"] as? JsonObject
        when {
            runtime == null -> errors += Issue("/runtime", "REQUIRED", "\"runtime\" object is required")
            else -> {
                val type = (runtime["type"] as? JsonPrimitive)?.content
                if (type == null || type !in RUNTIME_TYPES) {
                    errors += Issue("/runtime/type", "ENUM", "\"runtime.type\" must be one of $RUNTIME_TYPES")
                }
                val entry = (runtime["entry"] as? JsonPrimitive)?.content
                when {
                    entry == null || entry.isEmpty() -> errors += Issue("/runtime/entry", "REQUIRED", "\"runtime.entry\" is required")
                    type != null && entry.contains("..") -> errors +=
                        Issue("/runtime/entry", "PATH", "\"runtime.entry\" must be a relative path inside the bundle")
                    type != null && entry.startsWith("/") -> errors +=
                        Issue("/runtime/entry", "PATH", "\"runtime.entry\" must be a relative path inside the bundle")
                    type != null && type in ENTRY_RULES && entry != null && !ENTRY_RULES[type]!!.matches(entry) -> errors +=
                        Issue(
                            "/runtime/entry", "PATTERN",
                            "runtime \"$type\" requires an entry ending in ${ENTRY_RULES[type]} (got \"$entry\")",
                        )
                }
                (runtime["python"] as? JsonPrimitive)?.content?.let { py ->
                    if (type != null && type != "python") warnings +=
                        Issue("/runtime/python", "IGNORED", "\"runtime.python\" is ignored for runtime \"$type\"")
                    if (!Regex("^3\\.(9|1[0-9])$").matches(py)) warnings +=
                        Issue("/runtime/python", "HINT", "python version \"$py\" may not be available; host bundles 3.13")
                }
            }
        }

        validateNeeds(root["needs"], errors, warnings)
        validateObjectSchema(root["config_schema"], "/config_schema", errors)
        validateObjectSchema(root["profile_schema"], "/profile_schema", errors)
        validateExecution(root["execution"], errors)
        validateUi(root["ui"], errors)

        (root["stages"] as? JsonArray)?.let { stages ->
            if (stages.size > 64) errors += Issue("/stages", "MAX_ITEMS", "\"stages\" allows at most 64 named stages")
            stages.forEachIndexed { i, s ->
                if (s !is JsonPrimitive) errors += Issue("/stages/$i", "TYPE", "stage names must be strings")
            }
        }

        // The deserializer is lenient by design; if it failed where the tree looked fine, surface it.
        if (manifest == null && errors.isEmpty()) {
            errors += Issue("", "DESERIALIZE", "manifest could not be decoded into the host model")
        }

        return Result(errors, warnings)
    }

    private fun validateNeeds(needs: Any?, errors: MutableList<Issue>, warnings: MutableList<Issue>) {
        if (needs == null) return
        val obj = needs as? JsonObject
        if (obj == null) {
            errors += Issue("/needs", "TYPE", "\"needs\" must be an object")
            return
        }
        val allowed = setOf("capabilities", "optional", "apps")
        for (key in obj.keys) {
            if (key !in allowed) errors += Issue("/needs/$key", "UNKNOWN_KEY", "unknown needs key \"$key\"")
        }
        fun validateList(key: String, kind: String) {
            val list = obj[key] as? JsonArray ?: return
            list.forEachIndexed { i, entry ->
                val cap = entry as? JsonObject
                if (cap == null) {
                    errors += Issue("/needs/$key/$i", "TYPE", "each $kind entry must be an object")
                    return@forEachIndexed
                }
                val allowedKeys = if (kind == "app") setOf("package", "min_version_code") else setOf("id", "scope")
                for (k in cap.keys) {
                    if (k !in allowedKeys) errors += Issue("/needs/$key/$i/$k", "UNKNOWN_KEY", "unknown key \"$k\"")
                }
                if (kind == "app") {
                    val pkg = (cap["package"] as? JsonPrimitive)?.content
                    if (pkg.isNullOrEmpty()) {
                        errors += Issue("/needs/$key/$i/package", "REQUIRED", "app entry needs a \"package\"")
                    }
                    (cap["min_version_code"] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: run { if (cap.containsKey("min_version_code")) errors += Issue("/needs/$key/$i/min_version_code", "TYPE", "must be an integer") }
                } else {
                    val cid = (cap["id"] as? JsonPrimitive)?.content
                    when {
                        cid == null -> errors += Issue("/needs/$key/$i/id", "REQUIRED", "$kind entry needs an \"id\"")
                        !CAP_ID_REGEX.matches(cid) -> errors += Issue("/needs/$key/$i/id", "PATTERN", "\"$cid\" is not a capability/permission id")
                    }
                    (cap["scope"] as? JsonObject) ?: run {
                        if (cap.containsKey("scope")) errors += Issue("/needs/$key/$i/scope", "TYPE", "\"scope\" must be an object")
                    }
                }
            }
        }
        validateList("capabilities", "capability")
        validateList("optional", "optional capability")
        validateList("apps", "app")
        if (obj.containsKey("capabilities") && obj["capabilities"] !is JsonArray) {
            errors += Issue("/needs/capabilities", "TYPE", "\"needs.capabilities\" must be an array")
        }
        if (obj.containsKey("optional") && obj["optional"] !is JsonArray) {
            errors += Issue("/needs/optional", "TYPE", "\"needs.optional\" must be an array")
        }
    }

    /** Validate the `$defs.objectSchema` subset used by config_schema/profile_schema. */
    private fun validateObjectSchema(node: Any?, path: String, errors: MutableList<Issue>) {
        if (node == null) return
        val obj = node as? JsonObject
        if (obj == null) {
            errors += Issue(path, "TYPE", "must be a JSON-schema object")
            return
        }
        val type = (obj["type"] as? JsonPrimitive)?.content
        if (type != null && type != "object") errors += Issue("$path/type", "CONST", "config/profile schemas must be type \"object\"")
        (obj["required"] as? JsonArray)?.forEachIndexed { i, r ->
            if (r !is JsonPrimitive) errors += Issue("$path/required/$i", "TYPE", "required entries must be strings")
        }
        val properties = obj["properties"] as? JsonObject ?: return
        for ((name, spec) in properties) {
            val propPath = "$path/properties/$name"
            val prop = spec as? JsonObject
            if (prop == null) {
                errors += Issue(propPath, "TYPE", "property schemas must be objects")
                continue
            }
            val propType = (prop["type"] as? JsonPrimitive)?.content
            if (propType == null || propType !in PROPERTY_TYPES) {
                errors += Issue("$propPath/type", "ENUM", "property \"$name\" needs a type from $PROPERTY_TYPES")
            }
            (prop["minimum"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                ?: run { if (prop.containsKey("minimum")) errors += Issue("$propPath/minimum", "TYPE", "must be a number") }
            (prop["maximum"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                ?: run { if (prop.containsKey("maximum")) errors += Issue("$propPath/maximum", "TYPE", "must be a number") }
            if (prop.containsKey("enum") && prop["enum"] !is JsonArray) {
                errors += Issue("$propPath/enum", "TYPE", "\"enum\" must be an array")
            }
            if (prop.containsKey("x-secret") && (prop["x-secret"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == null) {
                errors += Issue("$propPath/x-secret", "TYPE", "\"x-secret\" must be a boolean")
            }
            if (prop.containsKey("x-ui") && prop["x-ui"] !is JsonObject) {
                errors += Issue("$propPath/x-ui", "TYPE", "\"x-ui\" must be an object")
            }
            if (propType == "string" && prop.containsKey("x-secret") && (prop["x-secret"] as? JsonPrimitive)?.content != "true") {
                // non-boolean handled above; nothing else to check
            }
        }
    }

    private fun validateExecution(node: Any?, errors: MutableList<Issue>) {
        if (node == null) return
        val obj = node as? JsonObject
        if (obj == null) {
            errors += Issue("/execution", "TYPE", "\"execution\" must be an object")
            return
        }
        val allowed = setOf(
            "ui_exclusive", "needs_unlocked_screen", "pausable", "timeout_minutes",
            "idle_timeout_seconds", "max_attempts", "verification", "min_interval_minutes",
        )
        for (key in obj.keys) {
            if (key !in allowed) errors += Issue("/execution/$key", "UNKNOWN_KEY", "unknown execution key \"$key\"")
        }
        fun intBounds(key: String, min: Long, max: Long?) {
            val raw = (obj[key] as? JsonPrimitive)?.content ?: return
            val value = raw.toLongOrNull()
            when {
                value == null -> errors += Issue("/execution/$key", "TYPE", "\"$key\" must be an integer")
                value < min -> errors += Issue("/execution/$key", "MINIMUM", "\"$key\" must be >= $min")
                max != null && value > max -> errors += Issue("/execution/$key", "MAXIMUM", "\"$key\" must be <= $max")
            }
        }
        intBounds("timeout_minutes", 1, 1440)
        intBounds("idle_timeout_seconds", 10, null)
        intBounds("max_attempts", 1, 10)
        intBounds("min_interval_minutes", 0, null)
        (obj["verification"] as? JsonPrimitive)?.content?.let {
            if (it !in VERIFICATION_MODES) errors += Issue("/execution/verification", "ENUM", "\"verification\" must be one of $VERIFICATION_MODES")
        }
    }

    private fun validateUi(node: Any?, errors: MutableList<Issue>) {
        if (node == null) return
        val obj = node as? JsonObject
        if (obj == null) {
            errors += Issue("/ui", "TYPE", "\"ui\" must be an object")
            return
        }
        val mode = (obj["mode"] as? JsonPrimitive)?.content
        when (mode) {
            null -> errors += Issue("/ui/mode", "REQUIRED", "\"ui.mode\" is required")
            !in setOf("auto", "declarative", "html") ->
                errors += Issue("/ui/mode", "ENUM", "\"ui.mode\" must be auto | declarative | html")
        }
        val needsFile = mode == "declarative" || mode == "html"
        val file = (obj["file"] as? JsonPrimitive)?.content
        if (needsFile && file.isNullOrEmpty()) {
            errors += Issue("/ui/file", "REQUIRED", "ui mode \"$mode\" requires \"ui.file\"")
        }
        if (!needsFile && file != null) {
            errors += Issue("/ui/file", "FORBIDDEN", "\"ui.file\" only applies to declarative/html modes")
        }
        for (key in obj.keys) {
            if (key !in setOf("mode", "file")) errors += Issue("/ui/$key", "UNKNOWN_KEY", "unknown ui key \"$key\"")
        }
    }
}
