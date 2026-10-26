package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * P6-03: execution-time selector handling for flows. The parser normalises the flow form into
 * the broker wire form (FlowParser.toWireSelector); here the `${...}` placeholders inside match
 * labels are resolved against the scope right before the call. An unresolved placeholder stays
 * literal — fail closed, matching the host's scope-templating rule (it then simply matches
 * nothing).
 */
internal object SelectorResolver {
    private val LABEL_TERMS = setOf("text", "desc", "id", "class", "pkg")

    /** Selector keys inside a selector-command body (everything except modifiers). */
    val SELECTOR_KEYS: Set<String> = FlowParser.SELECTOR_KEYS

    fun selectorPart(body: JsonObject): JsonObject =
        JsonObject(body.filterKeys { it in SELECTOR_KEYS })

    /** Resolves ${...} in match labels; non-string results are a typed INVALID_ARGS failure. */
    fun resolveLabels(wire: JsonObject, scope: FlowScope, label: String): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in wire) {
            when (key) {
                "terms" -> out["terms"] = buildJsonArray {
                    for (termRaw in value as JsonArray) {
                        val term = termRaw as JsonObject
                        val (termKey, spec) = term.entries.first()
                        add(
                            when (termKey) {
                                in LABEL_TERMS -> buildJsonObject {
                                    put(termKey, resolveMatch(spec as JsonObject, scope, label))
                                }
                                "relation" -> buildJsonObject {
                                    val relation = spec as JsonObject
                                    put("relation", buildJsonObject {
                                        relation.forEach { (k, v) ->
                                            put(k, if (k == "selector") resolveLabels(v as JsonObject, scope, label) else v)
                                        }
                                    })
                                }
                                "sel" -> buildJsonObject { put("sel", resolveLabels(spec as JsonObject, scope, label)) }
                                else -> term
                            },
                        )
                    }
                }
                "within" -> out["within"] = resolveLabels(value as JsonObject, scope, label)
                else -> out[key] = value
            }
        }
        return JsonObject(out)
    }

    private fun resolveMatch(spec: JsonObject, scope: FlowScope, label: String): JsonObject {
        val labels = (spec.getValue("labels") as JsonArray).map { element ->
            val resolved = scope.resolveString(element, allowSecrets = false)
            if (resolved !is JsonPrimitive || !resolved.isString) {
                throw FlowFailure("INVALID_ARGS", "$label: selector label resolved to a non-string")
            }
            resolved
        }
        return buildJsonObject {
            spec.forEach { (k, v) -> put(k, if (k == "labels") buildJsonArray { labels.forEach { add(it) } } else v) }
        }
    }

    /** Resolves ${...} inside a selector nested in a command body (`into`, waitUntil sides). */
    fun resolveNested(raw: JsonElement, scope: FlowScope, label: String, where: String): JsonObject {
        val wire = FlowParser.toWireSelector(raw, where)
        return resolveLabels(wire, scope, label)
    }
}

/**
 * One typed failure inside the flow. [code] is a broker error code (schemas/errors.json);
 * retryable failures are the only ones `retry` may re-run.
 */
class FlowFailure(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val selector: JsonObject? = null,
    val label: String? = null,
) : Exception(message)

/** Raised by the host's broker adapter when the broker returns a typed error. */
class BrokerCallException(val code: String, message: String, val retryable: Boolean) :
    Exception(message.ifEmpty { code })
