package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import app.autohost.core.model.AhError
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * P13-11 (AGENT_RUNTIME §10): validate a tool call's arguments against the GENERATED catalog
 * whitelist (P13-09) — a key that is not in the schema does not exist, a value outside its
 * enum/max is refused, and `enum_of: run_packages` is where the run's package scope meets the
 * model's arguments. Malformed JSON was already caught by the codec (arguments == null).
 */
object ArgValidator {

    /**
     * Returns the SANITIZED arguments (declared keys only) or a typed error. The catalog schema
     * per key: `{}` (any primitive), `{"enum": [...]}`, `{"enum_of": "run_packages"}`,
     * `{"max": N}` (integer ≤ N); a trailing `?` in the KEY marks it optional.
     *
     * `enum_of` names WHICH of the run's lists the value must come from: `run_packages` (the target apps) or
     * `run_tasks` (the saved tasks the person allowed for this run, INT-E1). Any other name fails closed.
     *
     * INT-E4: `{"pairs": N}` is the one spec that takes a list instead of a primitive: up to N texts written
     * `name=value`. The sanitized result holds them as an object (name to text) under the same key, and an empty list
     * is dropped. Which names are allowed is not decided here (that depends on the task): the pipeline checks it.
     *
     * INT-A2: `{"list": N}` is the other list spec: up to N short texts (1 to [MAX_LIST_ENTRY] characters each, trimmed,
     * no repeats) kept as a list, which is what the prompt screen reads for `ask_user`'s `choices`. An empty list is dropped.
     */
    fun validate(
        tool: ToolCatalog.Tool,
        raw: JsonObject?,
        packages: Set<String>,
        tasks: Set<String> = emptySet(),
    ): AhResult<JsonObject> {
        if (raw == null) {
            return AhResult.err(AhError.of(ErrorCode.INVALID_ARGS, "the call had no parseable arguments"))
        }
        val sanitized = buildJsonObject {
            for ((specKey, specElement) in tool.args) {
                // JsonObject iteration yields JsonElement values — the spec accessors need the
                // cast to JsonObject (fail closed on a non-object spec)
                val spec = specElement as? JsonObject ?: continue
                val optional = specKey.endsWith("?")
                val key = specKey.removeSuffix("?")
                val value = raw[key]
                if (value == null) {
                    if (!optional) {
                        return AhResult.err(
                            AhError.of(ErrorCode.INVALID_ARGS, "missing required argument '$key'"),
                        )
                    }
                    continue
                }
                if (spec.containsKey("pairs")) {
                    val limit = (spec["pairs"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                    val pairs = LinkedHashMap<String, String>()
                    val problem = readPairs(key, value, limit, pairs)
                    if (problem != null) return AhResult.err(AhError.of(ErrorCode.INVALID_ARGS, problem))
                    if (pairs.isNotEmpty()) {
                        put(key, buildJsonObject { for ((name, text) in pairs) put(name, JsonPrimitive(text)) })
                    }
                    continue
                }
                if (spec.containsKey("list")) {
                    val limit = (spec["list"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                    val items = ArrayList<String>()
                    val problem = readList(key, value, limit, items)
                    if (problem != null) return AhResult.err(AhError.of(ErrorCode.INVALID_ARGS, problem))
                    if (items.isNotEmpty()) put(key, JsonArray(items.map { JsonPrimitive(it) }))
                    continue
                }
                val primitive = value as? JsonPrimitive
                if (primitive == null) {
                    return AhResult.err(AhError.of(ErrorCode.INVALID_ARGS, "argument '$key' must be a primitive value"))
                }
                when {
                    spec.containsKey("enum") -> {
                        val allowed = (spec["enum"] as? JsonArray)
                            ?.mapNotNull { e -> (e as? JsonPrimitive)?.content }
                            .orEmpty()
                        if (primitive.content !in allowed) {
                            return AhResult.err(
                                AhError.of(
                                    ErrorCode.INVALID_ARGS,
                                    "argument '$key' must be one of ${allowed.joinToString(", ") { "'$it'" }}",
                                ),
                            )
                        }
                    }
                    spec.containsKey("enum_of") -> {
                        val listName = (spec["enum_of"] as? JsonPrimitive)?.content
                        if (listName == "run_tasks") {
                            if (primitive.content !in tasks) {
                                return AhResult.err(
                                    AhError.of(
                                        ErrorCode.PERMISSION_DENIED,
                                        "'${primitive.content}' is not one of the saved tasks allowed for this run",
                                        reason = "CAPABILITY_NOT_GRANTED",
                                    ),
                                )
                            }
                        } else if (listName != "run_packages" || primitive.content !in packages) {
                            return AhResult.err(
                                AhError.of(
                                    ErrorCode.PERMISSION_DENIED,
                                    "'${primitive.content}' is outside this run's packages",
                                    reason = "CAPABILITY_NOT_GRANTED",
                                ),
                            )
                        }
                    }
                    spec.containsKey("max") -> {
                        val max = (spec["max"] as? JsonPrimitive)?.content?.toIntOrNull()
                        val number = primitive.content.toIntOrNull()
                        if (max == null || number == null || number < 0 || number > max) {
                            return AhResult.err(
                                AhError.of(ErrorCode.INVALID_ARGS, "argument '$key' must be an integer in 0..$max"),
                            )
                        }
                    }
                }
                put(key, value)
            }
        }
        for (key in raw.keys) {
            val declared = tool.args.keys.any { it.removeSuffix("?") == key }
            if (!declared) {
                return AhResult.err(AhError.of(ErrorCode.INVALID_ARGS, "unknown argument '$key'"))
            }
        }
        return AhResult.ok(sanitized)
    }

    /** INT-E4: the longest name and value a task value may have (a video description is long; nothing needs more). */
    const val MAX_PAIR_NAME = 40
    const val MAX_PAIR_VALUE = 4_000

    /**
     * Reads `["name=value", ...]` into [out]. Returns the problem in words for the model, or null when the list is fine.
     * The name is what stands before the first `=` (trimmed); the value is everything after it, as it is.
     */
    private fun readPairs(key: String, value: JsonElement, limit: Int, out: MutableMap<String, String>): String? {
        val list = value as? JsonArray ?: return "argument '$key' must be a list of \"name=value\" texts"
        if (list.size > limit) return "argument '$key' may hold at most $limit values"
        for (item in list) {
            val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return "every entry of '$key' must be a \"name=value\" text"
            val cut = text.indexOf('=')
            val name = if (cut > 0) text.substring(0, cut).trim() else ""
            if (name.isEmpty() || name.length > MAX_PAIR_NAME) {
                return "every entry of '$key' needs a name before the '=' (at most $MAX_PAIR_NAME characters)"
            }
            val content = text.substring(cut + 1)
            if (content.length > MAX_PAIR_VALUE) return "the value of '$name' is longer than $MAX_PAIR_VALUE characters"
            if (out.put(name, content) != null) return "'$name' is given twice"
        }
        return null
    }

    /** INT-A2: the longest text one entry of a `list` argument may be (a button on a small screen shows nothing longer). */
    const val MAX_LIST_ENTRY = 60

    /** Reads `["a", "b"]` into [out], each entry trimmed. Returns the problem in words for the model, or null when the list is fine. */
    private fun readList(key: String, value: JsonElement, limit: Int, out: MutableList<String>): String? {
        val list = value as? JsonArray ?: return "argument '$key' must be a list of short texts"
        if (list.size > limit) return "argument '$key' may hold at most $limit entries"
        for (item in list) {
            val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?: return "every entry of '$key' must be a text"
            if (text.isEmpty() || text.length > MAX_LIST_ENTRY) {
                return "every entry of '$key' must be 1 to $MAX_LIST_ENTRY characters"
            }
            if (text in out) return "'$text' is given twice in '$key'"
            out.add(text)
        }
        return null
    }
}
