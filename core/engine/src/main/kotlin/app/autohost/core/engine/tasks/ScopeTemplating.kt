package app.autohost.core.engine.tasks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Substitutes `${config.x}` / `${profile.x}` placeholders (with simple dotted paths) inside grant scopes and
 * other task-provided JSON. Unresolved placeholders STAY LITERAL so a missing config value produces a scope that
 * matches nothing — fail closed (HANDOFF §3), never "matches everything".
 */
object ScopeTemplating {

    private val pattern = Regex("\\$\\{(config|profile)\\.([A-Za-z0-9_.]+)\\}")

    fun apply(scope: JsonObject, config: JsonObject, profile: JsonObject): JsonObject =
        applyElement(scope, mapOf("config" to config, "profile" to profile)) as JsonObject

    fun applyString(value: String, config: JsonObject, profile: JsonObject): String =
        applyString(value, mapOf("config" to config, "profile" to profile))

    private fun applyElement(element: JsonElement, sources: Map<String, JsonObject>): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (k, v) -> k to applyElement(v, sources) })
        is JsonArray -> JsonArray(element.map { applyElement(it, sources) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(applyString(element.content, sources)) else element
        else -> element
    }

    private fun applyString(value: String, sources: Map<String, JsonObject>): String =
        pattern.replace(value) { match ->
            val group = match.groupValues[1]
            val path = match.groupValues[2]
            resolve(sources[group], path) ?: match.value // unresolved: stay literal so the scope matches nothing
        }

    /** Dotted-path lookup; only object keys (scopes are strings). */
    private fun resolve(source: JsonObject?, path: String): String? {
        var current: JsonElement? = source
        for (segment in path.split('.')) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return when (val leaf = current) {
            is JsonPrimitive -> leaf.content
            else -> null
        }
    }
}
