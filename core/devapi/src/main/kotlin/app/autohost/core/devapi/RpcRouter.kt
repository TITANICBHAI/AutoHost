package app.autohost.core.devapi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * P2-22: method routing for the Dev API v0 (docs/DEVAPI.md §3). Methods map 1:1 onto the [DevApiBackend];
 * argument extraction is explicit and every failure becomes a typed error envelope — nothing throws across.
 */
class RpcRouter {

    private val handlers = HashMap<String, (JsonRpc.Request, DevApiBackend) -> JsonObject>()

    init {
        register("system.ping") { _, _ -> buildJsonObject { put("status", "ok") } }
        register("system.info") { _, backend -> backend.systemInfo() }
        register("system.capabilities") { _, backend -> backend.capabilities() }

        register("health.run") { req, backend ->
            val only = (req.params["only"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
            backend.healthRun(only)
        }

        // device.* — passed through the broker with the devapi caller's grants
        register("device.ui.snapshot") { req, backend ->
            backend.call("device.ui", "snapshot", req.params)
        }
        register("device.ui.find") { req, backend ->
            val selector = req.params["selector"] as? JsonObject
                ?: return@register errorBody("INVALID_ARGS", "\"selector\" object is required")
            val args = req.params.minus("selector").toMutableMap().apply { put("selector", selector) }
            backend.call("device.ui", "find", JsonObject(args))
        }
        register("device.ui.click") { req, backend -> backend.call("device.ui", "click", req.params) }
        register("device.ui.set_text") { req, backend -> backend.call("device.ui", "set_text", req.params) }
        register("device.ui.press") { req, backend -> backend.call("device.ui", "press", req.params) }
        register("device.ui.wait_for") { req, backend -> backend.call("device.ui", "wait_for", req.params) }
        register("device.app.launch") { req, backend -> backend.call("device.app", "launch", req.params) }
        register("device.app.foreground") { req, backend -> backend.call("device.app", "foreground", req.params) }

        register("tasks.list") { _, backend -> wrapArray("tasks", backend.tasksList()) }
        register("tasks.get") { req, backend ->
            val id = req.params.string("id") ?: return@register errorBody("INVALID_ARGS", "\"id\" is required")
            backend.tasksGet(id) ?: errorBody("NOT_FOUND", "no task $id")
        }
        register("tasks.remove") { req, backend ->
            val id = req.params.string("id") ?: return@register errorBody("INVALID_ARGS", "\"id\" is required")
            backend.tasksRemove(id, req.params.boolean("keep_history") ?: true)
        }
        register("tasks.set_config") { req, backend ->
            val id = req.params.string("id") ?: return@register errorBody("INVALID_ARGS", "\"id\" is required")
            val values = req.params["values"] as? JsonObject
                ?: return@register errorBody("INVALID_ARGS", "\"values\" object is required")
            backend.tasksSetConfig(id, values)
        }
        register("runs.start") { req, backend ->
            val task = req.params.string("task") ?: return@register errorBody("INVALID_ARGS", "\"task\" is required")
            backend.runsStart(task, req.params.string("profile"), req.params["args"] as? JsonObject)
        }
        register("runs.list") { _, backend -> wrapArray("runs", backend.runsList()) }
        register("runs.status") { req, backend ->
            val id = req.params.string("id") ?: return@register errorBody("INVALID_ARGS", "\"id\" is required")
            backend.runsStatus(id) ?: errorBody("NOT_FOUND", "no run $id")
        }
        register("runs.tail") { req, backend ->
            val id = req.params.string("id") ?: return@register errorBody("INVALID_ARGS", "\"id\" is required")
            wrapArray("events", backend.runsTail(id, req.params.long("since_seq") ?: 0L))
        }
        register("runs.pause") { req, backend ->
            val id = req.params.string("id") ?: return@register errorBody("INVALID_ARGS", "\"id\" is required")
            backend.runsPause(id)
        }
        register("runs.resume") { req, backend ->
            val id = req.params.string("id") ?: return@register errorBody("INVALID_ARGS", "\"id\" is required")
            backend.runsResume(id)
        }
        register("runs.cancel") { req, backend ->
            val id = req.params.string("id") ?: return@register errorBody("INVALID_ARGS", "\"id\" is required")
            backend.runsCancel(id)
        }

        // ---- P5-11: the full surface (docs/DEVAPI.md §3) --------------------------------
        register("triggers.list") { _, backend -> wrapArray("triggers", backend.triggersList()) }
        register("triggers.status") { _, backend -> wrapArray("triggers", backend.triggersStatus()) }
        // P8-03: the event-source catalog (plan §11.9 operations).
        register("triggers.catalog") { _, backend -> backend.triggersCatalog() }
        register("triggers.upsert") { req, backend ->
            val rule = req.params["rule"] as? JsonObject
                ?: return@register errorBody("INVALID_ARGS", "\"rule\" object is required")
            backend.triggerUpsert(rule)
        }
        register("triggers.delete") { req, backend ->
            val id = req.params.string("id") ?: return@register errorBody("INVALID_ARGS", "\"id\" is required")
            backend.triggerDelete(id)
        }
        register("triggers.test") { req, backend ->
            val rule = req.params["rule"] as? JsonObject
                ?: return@register errorBody("INVALID_ARGS", "\"rule\" object is required")
            // P8-03: fire=true dispatches a synthetic event through the live runtime; the default
            // stays a dry validation/preview.
            wrapArray("fires", backend.triggerTest(rule, req.params.long("count")?.toInt() ?: 5, fire = req.params.boolean("fire") ?: false))
        }
        register("health.checks") { _, backend -> wrapArray("checks", backend.healthChecks()) }
        register("runs.export") { req, backend ->
            val id = req.params.string("id") ?: return@register errorBody("INVALID_ARGS", "\"id\" is required")
            backend.runsExport(id) ?: errorBody("NOT_FOUND", "no run $id")
        }
        register("device.screenshot") { req, backend -> backend.call("device.screenshot", "capture", req.params) }
        register("recorder.start") { _, backend -> backend.recorderStart() }
        register("recorder.stop") { _, backend -> backend.recorderStop() }
    }

    private fun register(method: String, handler: (JsonRpc.Request, DevApiBackend) -> JsonObject) {
        handlers[method] = handler
    }

    fun route(request: JsonRpc.Request, backend: DevApiBackend): JsonRpc.Response {
        val handler = handlers[request.method]
            ?: return JsonRpc.Response.Failure(request.id, "METHOD_NOT_FOUND", "unknown method ${request.method}")
        return try {
            JsonRpc.Response.Success(request.id, handler(request, backend))
        } catch (e: Exception) {
            JsonRpc.Response.Failure(request.id, "INTERNAL", e.message ?: "unexpected failure")
        }
    }

    fun methods(): Set<String> = handlers.keys

    private fun wrapArray(key: String, array: JsonArray): JsonObject = buildJsonObject { put(key, array) }

    private fun errorBody(code: String, message: String): JsonObject = buildJsonObject {
        put("__error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun JsonPrimitive.asLong(): Long? = content.toLongOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.asLong()

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
}

/**
 * The router returns error envelopes via a `__error` marker for INVALID_ARGS-style rejections raised
 * *inside* handlers; this post-step converts them into JSON-RPC failures so callers see proper envelopes.
 * (Registered handlers mostly delegate to the backend, whose failures already arrive as data.)
 */
object RpcPostProcess {
    fun finalize(response: JsonRpc.Response): JsonRpc.Response = when (response) {
        is JsonRpc.Response.Failure -> response
        is JsonRpc.Response.Success -> {
            val marker = response.result["__error"] as? JsonObject
            if (marker != null) {
                val code = (marker["code"] as? JsonPrimitive)?.content ?: "INTERNAL"
                val message = (marker["message"] as? JsonPrimitive)?.content ?: "request failed"
                JsonRpc.Response.Failure(response.id, code, message)
            } else {
                response
            }
        }
    }
}
