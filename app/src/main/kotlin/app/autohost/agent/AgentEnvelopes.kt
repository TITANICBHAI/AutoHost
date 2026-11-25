package app.autohost.agent

import app.autohost.core.model.Envelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * INT-A3: the ONE shape of a tool result inside the agent runtime, and the readers that agree with it.
 *
 * A broker envelope that worked becomes `{"ok": true, <the provider's own fields>}`. One that failed becomes
 * `{"ok": false, "error": {"code", "reason"?, "message"}}`. The agent loop counts a call as a success only when `ok` is
 * true, the pipeline reads `status` or `answered` straight off the top level, and the observation filter reads
 * `package` and `nodes` there too. Before this, the transport handed back the provider's fields WITHOUT `ok` (so a call
 * that worked counted as a failure: three in a row stopped the run), and the two host reads of the factory looked for a
 * nested `result` that was never there (so the screen was always empty and the foreground app was never known).
 * The test doubles used the right shape, which is why no test noticed.
 */
internal object AgentEnvelopes {

    /** The agent-side shape of a broker [envelope] (see the class comment). A provider that set `ok` itself keeps its word. */
    fun toolResult(envelope: Envelope): JsonObject {
        if (!envelope.ok) {
            return buildJsonObject {
                put("ok", false)
                put(
                    "error",
                    buildJsonObject {
                        put("code", envelope.error?.code ?: "INTERNAL")
                        envelope.error?.reason?.let { put("reason", it) }
                        put("message", envelope.error?.message ?: "")
                    },
                )
            }
        }
        return when (val result = envelope.result) {
            is JsonObject ->
                if (result.containsKey("ok")) {
                    result
                } else {
                    buildJsonObject {
                        put("ok", true)
                        for ((key, value) in result) put(key, value)
                    }
                }
            null -> buildJsonObject { put("ok", true) }
            else -> buildJsonObject {
                put("ok", true)
                put("value", result)
            }
        }
    }

    /**
     * The provider's own fields of a tool result, for the host's own reads (the screen, the foreground app): the `ok`
     * marker is left out, and a result that failed has no fields at all.
     */
    fun fields(toolResult: JsonObject): JsonObject {
        if ((toolResult["ok"] as? JsonPrimitive)?.content == "false") return JsonObject(emptyMap())
        return JsonObject(toolResult.filterKeys { it != "ok" })
    }

    /** The foreground package out of a `device.app.foreground` tool result. Null when it failed or the app is outside the run's scope. */
    fun foregroundPackage(toolResult: JsonObject): String? =
        (fields(toolResult)["package"] as? JsonPrimitive)?.content
}
