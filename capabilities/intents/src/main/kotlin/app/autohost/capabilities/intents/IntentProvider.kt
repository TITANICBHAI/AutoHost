package app.autohost.capabilities.intents

import android.content.Intent
import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-06: the `device.intent` capability (plan §6.1 wave 2) — open_url / open_settings /
 * start_activity / broadcast behind the [IntentPolicy] allowlist. Extras are flat string/number/
 * boolean/JSON primitives (a deliberate cap: complex Parcelables are out of scope and untestable).
 */
class IntentProvider(private val dispatch: Dispatch = AndroidDispatch) : CapabilityProvider {

    /** The framework seam (JVM tests stub this; the app wires AndroidIntents). */
    interface Dispatch {
        fun startActivity(builder: (Intent) -> Intent): Boolean
        fun broadcast(builder: (Intent) -> Intent): Boolean
    }

    object AndroidDispatch : Dispatch {
        override fun startActivity(builder: (Intent) -> Intent): Boolean = AndroidIntents.startActivity(builder)
        override fun broadcast(builder: (Intent) -> Intent): Boolean = AndroidIntents.broadcast(builder)
    }

    override val capabilityId: String = "device.intent"
    override val providerId: String = "intent.system"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String = "device.intent"

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        val grant = ctx.grants["device.intent"]
        val scope = IntentPolicy.parseScope(grant)
        return when (method) {
            "open_url" -> {
                val url = args.string("url") ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"url\" is required")
                when (val verdict = IntentPolicy.checkUrl(url)) {
                    is IntentPolicy.Verdict.Deny -> deny(verdict)
                    IntentPolicy.Verdict.Allow -> dispatchResult(
                        dispatch.startActivity { intent ->
                            intent.action = android.content.Intent.ACTION_VIEW
                            intent.data = android.net.Uri.parse(url)
                            intent
                        },
                        unverified = true,
                    )
                }
            }
            "open_settings" -> {
                val page = args.string("page") ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"page\" is required")
                when (val verdict = IntentPolicy.checkSettingsPage(page)) {
                    is IntentPolicy.Verdict.Deny -> deny(verdict)
                    IntentPolicy.Verdict.Allow -> dispatchResult(
                        dispatch.startActivity { intent ->
                            intent.action = IntentPolicy.SETTINGS_PAGES[page]
                            intent
                        },
                        unverified = true,
                    )
                }
            }
            "start_activity" -> {
                val action = args.string("action")
                val component = args.string("component")
                val componentPkg = component?.substringBefore('/')
                val data = args.string("data")
                when (val verdict = IntentPolicy.checkActivity(action, componentPkg, data, scope)) {
                    is IntentPolicy.Verdict.Deny -> deny(verdict)
                    IntentPolicy.Verdict.Allow -> dispatchResult(
                        dispatch.startActivity { intent ->
                            if (action != null) intent.action = action
                            if (component != null) {
                                val parts = component.split('/')
                                if (parts.size == 2) intent.setClassName(parts[0], parts[1]) else intent.setPackage(component)
                            }
                            if (data != null) intent.data = android.net.Uri.parse(data)
                            applyExtras(intent, args["extras"])
                            intent
                        },
                        unverified = true,
                    )
                }
            }
            "broadcast" -> {
                val action = args.string("action")
                when (val verdict = IntentPolicy.checkBroadcast(action, scope)) {
                    is IntentPolicy.Verdict.Deny -> deny(verdict)
                    IntentPolicy.Verdict.Allow -> dispatchResult(
                        dispatch.broadcast { intent ->
                            intent.action = action
                            applyExtras(intent, args["extras"])
                            intent
                        },
                        unverified = true,
                    )
                }
            }
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.intent method '$method'")
        }
    }

    private fun applyExtras(intent: android.content.Intent, extras: kotlinx.serialization.json.JsonElement?) {
        (extras as? JsonObject)?.forEach { (key, value) ->
            when (value) {
                is JsonPrimitive -> if (value.isString) intent.putExtra(key, value.content) else {
                    value.content.toLongOrNull()?.let { intent.putExtra(key, it) }
                        ?: value.content.toDoubleOrNull()?.let { intent.putExtra(key, it) }
                        ?: intent.putExtra(key, value.content)
                }
                else -> intent.putExtra(key, value.toString())
            }
        }
    }

    private fun deny(verdict: IntentPolicy.Verdict.Deny): Envelope = Envelope.err(
        ErrorCode.SCOPE_VIOLATION,
        "device.intent: ${verdict.reason}",
        reason = "INTENT_NOT_GRANTED",
        fixAction = "OPEN_PERMISSION_REVIEW",
    )

    private fun dispatchResult(dispatched: Boolean, unverified: Boolean): Envelope =
        if (dispatched) {
            Envelope.ok(
                buildJsonObject {
                    put("dispatched", true)
                    put("verified", false)
                    if (unverified) put("note", "the OS accepted the intent; foreground verification is device.app.launch's job")
                },
            )
        } else {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "the system refused the intent")
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
