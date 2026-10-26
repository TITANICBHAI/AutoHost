package app.autohost.plugin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject

/**
 * P11-02 (ADR-079): the base class external plugin APKs extend. Subclasses declare their
 * capabilities once via [declare] and implement [onInvoke]; the base serves the AIDL contract
 * (`ICapabilityProvider`) the host discovers with the
 * `app.autohost.plugin.CAPABILITY_PROVIDER` intent action.
 *
 * Dependency-free on purpose: the plugin SDK pulls in nothing but the Android framework — the
 * JSON helpers below build the describe()/availability()/envelope strings the host validates.
 * The host parses and gates EVERYTHING (api level, ids, methods, permissions) fail closed, so a
 * plugin cannot widen its own contract.
 *
 * Minimal plugin:
 * ```kotlin
 * class MyPlugin : PluginCapabilityService() {
 *     init {
 *         declare(capability = "plugin.vendor.thing", title = "Vendor Thing",
 *                 methods = listOf(PluginMethod("ping", "plugin.vendor.thing.use", "Ping")))
 *     }
 *     override fun onInvoke(method: String, args: JSONObject, runId: String): JSONObject {
 *         if (method == "ping") return envelopeOk(JSONObject().put("pong", true))
 *         return envelopeErr("INVALID_ARGS", "unknown method")
 *     }
 * }
 * ```
 * Manifest: `<service android:name=".MyPlugin" android:exported="true">` with an intent filter
 * for `app.autohost.plugin.CAPABILITY_PROVIDER`. The `<queries>` element lives on the HOST side.
 */
abstract class PluginCapabilityService : Service() {

    /** The plugin API level this SDK ships. Bump with a recorded ADR on both sides. */
    private val apiLevel: Int = PLUGIN_API_LEVEL

    private val declarations = JSONArray()

    /** One declared method inside a capability. [permission] is the grant id tasks must carry. */
    data class PluginMethod(val name: String, val permission: String, val title: String? = null)

    /** Registers one capability declaration (call from `init`; re-calls with the same id replace). */
    protected fun declare(capability: String, title: String? = null, tier: String = "T4", methods: List<PluginMethod>) {
        val entry = JSONObject()
            .put("capability", capability)
            .put("tier", tier)
        if (title != null) entry.put("title", title)
        val methodArray = JSONArray()
        for (method in methods) {
            val methodJson = JSONObject().put("name", method.name).put("permission", method.permission)
            if (method.title != null) methodJson.put("title", method.title)
            methodArray.put(methodJson)
        }
        entry.put("methods", methodArray)
        for (i in 0 until declarations.length()) {
            if (declarations.getJSONObject(i).optString("capability") == capability) {
                declarations.put(i, entry)
                return
            }
        }
        declarations.put(entry)
    }

    /** The full service implementation — binds the AIDL stub over the subclass hooks. */
    private val binder = object : ICapabilityProvider.Stub() {
        override fun apiLevel(): Int = apiLevel
        override fun describe(): String = declarations.toString()
        override fun availability(envJson: String): String = this@PluginCapabilityService.availability(envJson)
        override fun invoke(callJson: String): String = this@PluginCapabilityService.invoke(callJson)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ---- subclass-facing helpers -----------------------------------------------------------

    /** Availability hook: return STATE_AVAILABLE / STATE_UNSUPPORTED / STATE_NEEDS_SETUP. */
    protected open fun onAvailable(env: JSONObject): String = STATE_AVAILABLE

    private fun availability(envJson: String): String = try {
        val env = if (envJson.isBlank()) JSONObject() else JSONObject(envJson)
        JSONObject().put("state", onAvailable(env))
    } catch (e: Exception) {
        JSONObject().put("state", STATE_UNSUPPORTED).put("reason", "PLUGIN_AVAILABILITY_CRASHED")
    }.toString()

    /** Wrap a result the host will accept verbatim. */
    protected fun envelopeOk(result: JSONObject): JSONObject =
        JSONObject().put("ok", true).put("result", result)

    /** Wrap a typed failure — codes mirror schemas/errors.json. */
    protected fun envelopeErr(code: String, message: String, reason: String? = null): JSONObject {
        val error = JSONObject().put("code", code).put("message", message)
        if (reason != null) error.put("reason", reason)
        return JSONObject().put("ok", false).put("error", error)
    }

    protected fun availabilityNeedsSetup(reason: String, fixAction: String): String =
        JSONObject().put("state", "needs_setup").put("reason", reason).put("fix_action", fixAction).toString()

    protected fun availabilityUnsupported(reason: String): String =
        JSONObject().put("state", "unsupported").put("reason", reason).toString()

    /** The dispatch hook — called on the binder thread; keep it fast or move work to your own executor. */
    protected abstract fun onInvoke(method: String, args: JSONObject, runId: String): JSONObject

    private fun invoke(callJson: String): String = try {
        val call = JSONObject(callJson)
        onInvoke(call.optString("method"), call.optJSONObject("args") ?: JSONObject(), call.optString("runId"))
            .toString()
    } catch (e: Exception) {
        envelopeErr("INTERNAL", "plugin threw: ${e.message}").toString()
    }

    companion object {
        const val PLUGIN_API_LEVEL = 1
        const val DISCOVERY_ACTION = "app.autohost.plugin.CAPABILITY_PROVIDER"
        const val STATE_AVAILABLE = "available"
        const val STATE_UNSUPPORTED = "unsupported"
        const val STATE_NEEDS_SETUP = "needs_setup"
    }
}
