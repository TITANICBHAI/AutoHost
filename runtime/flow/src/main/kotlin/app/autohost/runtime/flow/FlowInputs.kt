package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * INT-B (docs/INTEGRATION_V3.md): how a caller hands values to a flow. A script (`tasks.run` with `args`), later an
 * agent or a trigger, sends `args`; the run's trigger payload carries them as `args`.
 *
 * A flow lists the variables it accepts in its own `env` block, and that block also holds their defaults. A caller may
 * set only those names, and only to a string, number or boolean, so it can never shadow config, profile or a variable
 * the flow did not offer. When the default is text, a number or boolean from the caller is turned into text, so
 * `${title}` in an `inputText` still resolves to a string. Everything the caller sent stays readable, unchanged, as
 * `run.args`, so a flow can still look at it. To keep a value private, set it with `defineVariables` instead of `env`.
 */
internal object FlowInputs {

    /** The caller's values for the names [declared] offers; undeclared names and non-plain values are left out. */
    fun accepted(declared: JsonObject, args: JsonObject?): JsonObject {
        if (args == null) return JsonObject(emptyMap())
        val out = LinkedHashMap<String, JsonElement>()
        for ((name, value) in args) {
            val default = declared[name] ?: continue
            if (!JsonValues.isScalar(value)) continue
            out[name] = if (JsonValues.isStr(default) && !JsonValues.isStr(value)) {
                JsonPrimitive(FlowScope.interpolate(value))
            } else {
                value
            }
        }
        return JsonObject(out)
    }

    /** Names the caller sent that the flow did not take (not declared, or not a plain value), sorted, for the run log. */
    fun ignored(declared: JsonObject, args: JsonObject?): List<String> {
        if (args == null) return emptyList()
        val taken = accepted(declared, args).keys
        return args.keys.filter { it !in taken }.sorted()
    }

    /** The `run` object a flow sees: the attempt, how the run was started and the caller's args as they were sent. */
    fun runObject(attempt: Int, triggerType: String, args: JsonObject?): JsonObject = buildJsonObject {
        put("attempt", attempt)
        put("trigger", triggerType)
        put("args", args ?: JsonObject(emptyMap()))
    }
}
