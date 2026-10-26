package app.autohost.capabilities.ime

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `device.ime` capability (schemas/capability-specs.w1.json): `commit_text`, `clipboard_get`,
 * `clipboard_set`. Everything routes through [ImeBridge] to the [AutoHostKeyboardService]; when the IME is
 * not the selected keyboard the capability reports NEEDS_SETUP with a fix action instead of pretending.
 *
 * Secret rule: committed secret text is never logged and never echoed in results (AGENTS.md §3.6).
 */
class ImeProvider : CapabilityProvider {

    override val capabilityId: String = "device.ime"
    override val providerId: String = "ime.autohost"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability =
        if (ImeBridge.connected && ImeBridge.isSelected()) {
            Availability.Available
        } else {
            Availability.NeedsSetup(reason = ImeSelector.SETUP_REASON, fixAction = ImeSelector.FIX_ACTION)
        }

    override fun permissionFor(method: String, args: JsonObject): String? = "device.ime"

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "commit_text" -> commitText(args)
        "clipboard_get" -> clipboardGet()
        "clipboard_set" -> clipboardSet(args)
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.ime method '$method'")
    }

    private fun commitText(args: JsonObject): Envelope {
        val text = args["text"]?.let { it as? JsonPrimitive }?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "commit_text needs a 'text' argument")
        val clearFirst = (args["clear_first"] as? JsonPrimitive)?.content?.toBooleanStrict() ?: false
        val secret = (args["secret"] as? JsonPrimitive)?.content?.toBooleanStrict() ?: false
        val result = ImeBridge.commitText(text, clearFirst, secret)
        return when (result.outcome) {
            TextInputResult.VERIFIED -> Envelope.ok(
                buildJsonObject {
                    put("outcome", result.outcome)
                    put("strategy", result.strategy)
                },
            )
            TextInputResult.REJECTED -> Envelope.err(
                ErrorCode.TEXT_INPUT_REJECTED,
                result.detail ?: "the field refused the text",
                reason = "FIELD_REJECTED",
            )
            TextInputResult.INPUT_SESSION_CHANGED -> Envelope.err(
                ErrorCode.TEXT_INPUT_REJECTED,
                result.detail ?: "the focused editor changed during the commit",
                reason = "INPUT_SESSION_CHANGED",
            )
            TextInputResult.ACCEPTED_UNVERIFIED -> Envelope.err(
                ErrorCode.TEXT_INPUT_UNVERIFIED,
                result.detail ?: "the commit was applied but the field will not confirm it (password field?)",
                reason = "UNVERIFIED_INPUT",
            )
            else -> Envelope.err(
                ErrorCode.CAPABILITY_UNAVAILABLE,
                result.detail ?: "the AutoHost IME is not the selected keyboard",
                reason = ImeSelector.SETUP_REASON,
                fixAction = ImeSelector.FIX_ACTION,
            )
        }
    }

    private fun clipboardGet(): Envelope {
        if (!ImeBridge.connected) return needsSetup()
        val text = ImeBridge.clipboardGet()
        return Envelope.ok(buildJsonObject { put("text", text) })
    }

    private fun clipboardSet(args: JsonObject): Envelope {
        val text = args["text"]?.let { it as? JsonPrimitive }?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "clipboard_set needs a 'text' argument")
        if (!ImeBridge.connected) return needsSetup()
        return Envelope.ok(buildJsonObject { put("ok", ImeBridge.clipboardSet(text)) })
    }

    private fun needsSetup(): Envelope = Envelope.err(
        ErrorCode.NEEDS_SETUP,
        "the AutoHost keyboard is not connected",
        reason = ImeSelector.SETUP_REASON,
        fixAction = ImeSelector.FIX_ACTION,
    )
}
