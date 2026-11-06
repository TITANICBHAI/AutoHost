package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import app.autohost.core.model.AhError
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonArray
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
     */
    fun validate(tool: ToolCatalog.Tool, raw: JsonObject?, packages: Set<String>): AhResult<JsonObject> {
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
                        if (primitive.content !in packages) {
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
}
