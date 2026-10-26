package app.autohost.core.engine.plugins

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import app.autohost.core.common.AhJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P11-02 (ADR-079): the plugin host — how an external APK (or an in-app module) contributes
 * capability providers without any host code change (plan §18 P11, Gate F4).
 *
 * Contract (`plugin-sdk/src/main/aidl/**/ICapabilityProvider.aidl`, discovered via the
 * `app.autohost.plugin.CAPABILITY_PROVIDER` intent action):
 *  - `apiLevel()` — the plugin API the plugin was built against; the host accepts
 *    [PLUGIN_API_MIN]..[PLUGIN_API_MAX] and rejects anything else (fail closed).
 *  - `describe()` — a JSON **array** of plugin capability declarations:
 *    `{"capability": "plugin.<vendor>.<name>", "tier": "T4", "title": "...",
 *      "methods": [{"name": "...", "permission": "<permission-id>"}, …]}`
 *    Every field is validated; any malformed entry fails the WHOLE plugin (fail closed —
 *    a half-registered plugin would look available and then fail at call time).
 *  - `availability(envJson)` / `invoke(callJson)` — JSON passthroughs (parsed here into
 *    [Availability] / [Envelope]; a parse failure becomes a typed error, never a crash).
 *
 * Pure: everything device-touching stays behind [PluginBinder] (the app-side controller owns the
 * ServiceConnection). One [PluginProvider] per declared capability is produced by [attach]; the
 * caller registers them into the [app.autohost.core.broker.CapabilityRegistry] and merges
 * [Attached.permissionFragment] into the [app.autohost.core.broker.PermissionIndex] so
 * GrantBuilder can map a task's manifest needs onto the plugin's permissions (ADR-079).
 */
object PluginHost {

    /** The plugin API band this host build understands. Bump with a recorded ADR. */
    const val PLUGIN_API_MAX = 1
    const val PLUGIN_API_MIN = 1

    /** The intent action plugins declare (mirror of the AIDL header comment — keep in sync). */
    const val DISCOVERY_ACTION = "app.autohost.plugin.CAPABILITY_PROVIDER"

    private val DOTTED_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9_-]*)+$")
    private val METHOD_NAME = Regex("^[a-z][a-z0-9_]*$")
    private val METHOD_KEYS = setOf("name", "permission", "title")
    private val ENTRY_KEYS = setOf("capability", "tier", "title", "methods")

    /** The transport seam an app-side controller implements over the bound service. */
    interface PluginBinder {
        fun apiLevel(): Int
        fun describe(): String
        fun availability(envJson: String): String
        fun invoke(callJson: String): String
    }

    data class DeclaredMethod(val name: String, val permission: String, val title: String?)

    data class DeclaredCapability(
        val capabilityId: String,
        val tier: String,
        val title: String?,
        val methods: List<DeclaredMethod>,
    )

    sealed class AttachIssue {
        data class PluginApi(val level: Int) : AttachIssue()
        data class Malformed(val detail: String) : AttachIssue()
        data class Collision(val capabilityId: String) : AttachIssue()
    }

    data class Attached(
        val pluginId: String,
        val capabilities: List<DeclaredCapability>,
        val providers: List<CapabilityProvider>,
        /** Merged into the host PermissionIndex on attach so grants resolve (ADR-079). */
        val permissionFragment: Map<String, List<String>>,
        val binder: PluginBinder,
    )

    data class AttachResult(val attached: Attached?, val issues: List<AttachIssue>) {
        val ok: Boolean get() = attached != null
    }

    /** Parses + validates the plugin against the host contract, then produces its providers. */
    fun attach(pluginId: String, binder: PluginBinder, takenCapabilityIds: Set<String>): AttachResult {
        val level = binder.apiLevel()
        if (level < PLUGIN_API_MIN || level > PLUGIN_API_MAX) {
            return AttachResult(null, listOf(AttachIssue.PluginApi(level)))
        }
        val declared = parseDescribe(binder.describe())
            ?: return AttachResult(
                null,
                listOf(AttachIssue.Malformed("describe() is not a valid plugin capability array")),
            )
        if (declared.isEmpty()) {
            return AttachResult(null, listOf(AttachIssue.Malformed("describe() declares no capabilities")))
        }
        val issues = ArrayList<AttachIssue>()
        for (capability in declared) {
            if (capability.capabilityId in takenCapabilityIds) {
                issues += AttachIssue.Collision(capability.capabilityId)
            }
        }
        if (issues.isNotEmpty()) return AttachResult(null, issues)
        val providers = declared.map { PluginProvider(pluginId, it, binder) }
        val fragment = LinkedHashMap<String, List<String>>()
        for (capability in declared) {
            fragment[capability.capabilityId] = capability.methods.map { it.permission }
        }
        return AttachResult(Attached(pluginId, declared, providers, fragment, binder), emptyList())
    }

    /** Fail-closed describe() parse: null on ANY structural problem; entries are fully validated. */
    fun parseDescribe(text: String): List<DeclaredCapability>? {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }
        val array = root as? JsonArray ?: return null
        val out = ArrayList<DeclaredCapability>(array.size)
        for (element in array) {
            val obj = element as? JsonObject ?: return null
            for (key in obj.keys) if (key !in ENTRY_KEYS) return null
            val capabilityId = (obj["capability"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!DOTTED_ID.matches(capabilityId)) return null
            if (!capabilityId.startsWith("plugin.")) return null // namespaced; host ids stay host-owned
            val tier = (obj["tier"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "T4"
            val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val methodsJson = obj["methods"] as? JsonArray ?: return null
            if (methodsJson.isEmpty()) return null
            val methods = ArrayList<DeclaredMethod>(methodsJson.size)
            val seen = HashSet<String>()
            for (methodElement in methodsJson) {
                val method = methodElement as? JsonObject ?: return null
                for (key in method.keys) if (key !in METHOD_KEYS) return null
                val name = (method["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                if (!METHOD_NAME.matches(name) || !seen.add(name)) return null
                val permission = (method["permission"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return null
                if (!DOTTED_ID.matches(permission)) return null
                val methodTitle = (method["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                methods += DeclaredMethod(name, permission, methodTitle)
            }
            out += DeclaredCapability(capabilityId, tier, title, methods)
        }
        return out
    }
}

/**
 * The broker-facing provider over one declared plugin capability. Availability and invoke are
 * passthroughs to the binder with JSON parse failures mapped to typed envelopes — the plugin's
 * own failures are its to report; the host never guesses.
 */
class PluginProvider(
    pluginId: String,
    private val declared: PluginHost.DeclaredCapability,
    private val binder: PluginHost.PluginBinder,
) : CapabilityProvider {

    override val capabilityId: String = declared.capabilityId
    override val providerId: String = "plugin:$pluginId:${declared.capabilityId}"
    override val tier: String = declared.tier

    override fun availability(env: DeviceEnv): Availability {
        val text = try {
            binder.availability(envJson(env))
        } catch (e: Exception) {
            return Availability.Unsupported("PLUGIN_AVAILABILITY_FAILED")
        }
        return parseAvailability(text)
    }

    override fun permissionFor(method: String, args: JsonObject): String? =
        declared.methods.firstOrNull { it.name == method }?.permission

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (declared.methods.none { it.name == method }) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "plugin capability '$capabilityId' has no method '$method'")
        }
        val call = buildJsonObject {
            put("capability", capabilityId)
            put("method", method)
            put("args", args)
            put("runId", ctx.runId)
        }
        val text = try {
            binder.invoke(call.toString())
        } catch (e: Exception) {
            return Envelope.err(
                ErrorCode.CAPABILITY_UNAVAILABLE,
                "plugin call failed: ${e.message}",
                reason = "PLUGIN_CALL_FAILED",
            )
        }
        return parseEnvelope(text)
            ?: Envelope.err(ErrorCode.INTERNAL, "plugin returned a malformed envelope", reason = "PLUGIN_ENVELOPE_MALFORMED")
    }

    private fun envJson(env: DeviceEnv): String = buildJsonObject {
        put("api", env.sdkInt)
        put("low_ram", env.isLowRam)
        put("manufacturer", env.manufacturer)
    }.toString()

    private fun parseAvailability(text: String): Availability {
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Availability.Unsupported("PLUGIN_AVAILABILITY_MALFORMED")
        return when ((obj["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "available" -> Availability.Available
            "needs_setup" -> Availability.NeedsSetup(
                (obj["reason"] as? JsonPrimitive)?.content ?: "PLUGIN_NEEDS_SETUP",
                (obj["fix_action"] as? JsonPrimitive)?.content ?: "OPEN_PLUGIN_SETTINGS",
            )
            else -> Availability.Unsupported(
                (obj["reason"] as? JsonPrimitive)?.content ?: "PLUGIN_UNSUPPORTED",
            )
        }
    }

    private fun parseEnvelope(text: String): Envelope? = try {
        AhJson.json.decodeFromString(Envelope.serializer(), text)
    } catch (e: Exception) {
        null
    }
}
