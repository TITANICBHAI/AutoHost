package app.autohost.plugin.example

import app.autohost.plugin.PluginCapabilityService
import app.autohost.plugin.PluginMethod
import org.json.JSONObject

/**
 * P11-02 (ADR-079): the example capability plugin — deliberately trivial so the contract is the
 * lesson: declare, implement, report honestly. Gate F4 exercises it from two tasks that declare
 * `plugin.example.clock` in `needs.capabilities` and change nothing when the plugin updates.
 *
 * The capability id and permission are namespaced under `plugin.example.` — the host refuses
 * anything that is not `plugin.*` or that collides with a host capability.
 */
class ExamplePluginService : PluginCapabilityService() {

    init {
        declare(
            capability = "plugin.example.clock",
            title = "Example clock",
            methods = listOf(
                PluginMethod("now", "plugin.example.clock.use", "Wall-clock now"),
                PluginMethod("tick", "plugin.example.clock.use", "Uptime ticks"),
            ),
        )
    }

    override fun onInvoke(method: String, args: JSONObject, runId: String): JSONObject = when (method) {
        "now" -> envelopeOk(
            JSONObject()
                .put("now_ms", System.currentTimeMillis())
                .put("honest", "plugin-provided wall clock; monotonic work should use ctx clock"),
        )
        "tick" -> envelopeOk(JSONObject().put("tick", (args.optInt("start", 0) + 1)))
        else -> envelopeErr("INVALID_ARGS", "unknown method '$method'")
    }
}
