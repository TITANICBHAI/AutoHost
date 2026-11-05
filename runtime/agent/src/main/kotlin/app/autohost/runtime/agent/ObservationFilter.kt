package app.autohost.runtime.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * P13-08 (AGENT_RUNTIME §8, ADR-091 (6)): the observation floor. Before anything reaches the
 * model: (a) if the foreground package or ANY node's package is BLOCKED or outside the run's
 * packages `P`, the whole observation is WITHHELD (`{withheld: true, reason}`) — a snapshot can
 * mix packages when a dialog from another app overlays the target; (b) `device.app.foreground`
 * results out of scope are replaced with `{in_scope:false}`; (c) password nodes are rendered
 * `{password}` (they arrive with empty text by construction — `UiTreeBuilder.kt:65-66`).
 */
object ObservationFilter {

    data class Result(val withheld: Boolean, val reason: String?, val observation: JsonObject?)

    /**
     * Filters a `device.ui.snapshot` result. [packages] is the run's granted package set;
     * [sensitivity] decides BLOCKED (CAUTIOUS/NORMAL pass the scope check alone).
     */
    fun filterSnapshot(
        result: JsonObject,
        packages: Set<String>,
        sensitivity: AppSensitivity,
    ): Result {
        val foreground = (result["package"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (foreground != null && (sensitivity.isBlocked(foreground) || foreground !in packages)) {
            return Result(withheld = true, reason = "FOREIGN_OR_BLOCKED_APP", observation = null)
        }
        val nodes = result["nodes"] as? JsonArray
        if (nodes != null) {
            for (node in nodes) {
                val pkg = (node.jsonObject["pkg"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                if (pkg != null && (sensitivity.isBlocked(pkg) || pkg !in packages)) {
                    return Result(withheld = true, reason = "NODE_FROM_FOREIGN_OR_BLOCKED_APP", observation = null)
                }
            }
        }
        return Result(withheld = false, reason = null, observation = result)
    }

    /**
     * Filters a `device.app.foreground` result: the provider returns the REAL foreground package
     * even when out of scope (ADR-098 (4)) — the model gets `{in_scope:false}` instead.
     */
    fun filterForeground(result: JsonObject, packages: Set<String>): JsonObject {
        val pkg = (result["package"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        return if (pkg != null && pkg in packages) {
            buildJsonObject {
                put("in_scope", true)
                put("package", pkg)
            }
        } else {
            buildJsonObject { put("in_scope", false) }
        }
    }

    /**
     * Renders one node for the model: password nodes are `{password}` (the text is empty at
     * source); everything else keeps the summary as the provider built it.
     */
    fun renderNode(node: JsonObject): JsonObject {
        val flags = (node["flags"] as? JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.toSet()
            ?: emptySet()
        return if ("password" in flags) {
            buildJsonObject {
                for ((k, v) in node) if (k != "text" && k != "desc") put(k, v)
                put("text", "{password}")
            }
        } else {
            node
        }
    }
}
