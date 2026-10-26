package app.autohost.core.model

import kotlinx.serialization.json.JsonObject

/**
 * P6-01b: typed flow document (plan §7.9). These are the in-memory shapes the `:runtime:flow`
 * parser produces and the interpreter consumes; the wire contract is `schemas/flow.schema.json`
 * plus the body shapes pinned by `sdk-python/autohost_ref/flows.py` and
 * `testing/golden/flow_vectors.json` (the Python reference is the executable spec).
 *
 * Selectors are kept as JSON objects: `whenCondition` holds them in broker wire form
 * (schemas/broker.schema.json#/$defs/Selector, normalised at parse time), command bodies keep
 * the flow form and are converted at execution time by the interpreter's selector resolver.
 */
data class FlowDocument(
    val schema: Int = 1,
    val name: String? = null,
    val tags: List<String> = emptyList(),
    /** Flow-level variables (`env` key); overlaid by the caller and by runFlow `env`. */
    val env: JsonObject = JsonObject(emptyMap()),
    val onFlowStart: List<FlowStep> = emptyList(),
    val onFlowComplete: List<FlowStep> = emptyList(),
    val steps: List<FlowStep> = emptyList(),
)

/**
 * One normalised step. [body] is the raw command body (JSON object). repeat/retry bodies carry
 * their nested [steps] already parsed; `times` serves repeat.times and retry.times.
 */
data class FlowStep(
    val command: String,
    val body: JsonObject = JsonObject(emptyMap()),
    val optional: Boolean = false,
    val label: String? = null,
    /** `timeout` modifier converted to milliseconds (the flow format states seconds). */
    val timeoutMs: Long? = null,
    val whenCondition: FlowWhen? = null,
    val steps: List<FlowStep> = emptyList(),
    val times: Int? = null,
    val whileExpr: String? = null,
    val backoffMs: Long = 500L,
) {
    /** Evidence/report label: the step's own label or the command name. */
    val reportLabel: String get() = label ?: command
}

/** `when` guard: every present key must hold (AND). Selectors are in broker wire form. */
data class FlowWhen(
    val visible: JsonObject? = null,
    val notVisible: JsonObject? = null,
    val trueExpr: String? = null,
    val minApi: Int? = null,
)
