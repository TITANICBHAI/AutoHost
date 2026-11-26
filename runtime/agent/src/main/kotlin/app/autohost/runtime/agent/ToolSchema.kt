package app.autohost.runtime.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * INT-A1: what the MODEL is told about one tool's arguments.
 *
 * The catalog keeps each tool's arguments in its own whitelist form, e.g. `{"selector": {}, "limit?": {}, "timeout_ms?":
 * {"max": 5000}, "package": {"enum_of": "run_packages"}}`: a trailing `?` marks an optional key, and `enum_of` and `max` are
 * our words, not JSON Schema's. [ArgValidator] reads that form. A chat API does not accept it as a function's `parameters`
 * (it wants an object schema: `type`, `properties`, `required`), and before this change it was sent as it stood, so the
 * model saw property names such as `limit?` and keywords no provider knows. This turns the one form into the other.
 *
 * Nothing here decides what is allowed: the validator still checks every call. Left out on purpose: `additionalProperties`
 * (Gemini's own API refuses that key, and the validator already refuses an unknown argument) and keywords that not every
 * provider knows (`minLength`, `maxLength`, `maxProperties`).
 */
object ToolSchema {

    private const val APPS_HELP = "Package name of one of the apps this run may use."
    private const val TASKS_HELP = "Id of one of the saved tasks this run may start."
    private const val PAIRS_HELP =
        "Values to give the task, each written as name=value, for example title=My video. Use only the names listed for that task."

    private val LIST_HELP = "Short texts, one per entry, at most ${ArgValidator.MAX_LIST_ENTRY} characters each."

    /** The object schema for a tool's whitelist arguments (a tool without arguments gets an empty `properties`). */
    fun parameters(args: JsonObject): JsonObject {
        val required = ArrayList<String>()
        val properties = buildJsonObject {
            for ((specKey, spec) in args) {
                val name = specKey.removeSuffix("?")
                if (!specKey.endsWith("?")) required.add(name)
                put(name, property(spec as? JsonObject ?: JsonObject(emptyMap())))
            }
        }
        return buildJsonObject {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

    private fun property(spec: JsonObject): JsonObject = buildJsonObject {
        when {
            spec.containsKey("enum") -> {
                put("type", "string")
                put("enum", spec["enum"] as? JsonArray ?: JsonArray(emptyList()))
            }
            spec.containsKey("enum_of") -> {
                put("type", "string")
                put(
                    "description",
                    if ((spec["enum_of"] as? JsonPrimitive)?.content == "run_tasks") TASKS_HELP else APPS_HELP,
                )
            }
            spec.containsKey("max") -> {
                put("type", "integer")
                put("minimum", 0)
                (spec["max"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { put("maximum", it) }
            }
            spec.containsKey("pairs") -> {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", PAIRS_HELP)
            }
            spec.containsKey("list") -> {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", LIST_HELP)
            }
            else -> put("type", "string")
        }
    }
}
