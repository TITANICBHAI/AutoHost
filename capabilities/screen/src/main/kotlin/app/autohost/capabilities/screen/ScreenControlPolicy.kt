package app.autohost.capabilities.screen

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode

/**
 * P8-10: pure policy for the `device.screen` control methods (plan §6.1 W2: "is_on, is_unlocked,
 * wake (best-effort), request keyguard dismiss (non-secure), lock, brightness. Never bypasses a
 * secure lock").
 *
 * Method → permission: reads keep `device.screen.read`; control methods need the
 * `device.screen.control` grant. Brightness writes additionally need the write-settings
 * special access (checked lazily → NEEDS_SETUP with the OPEN_WRITE_SETTINGS fix, P8-14).
 */
object ScreenControlPolicy {

    const val PERMISSION_READ = "device.screen.read"
    const val PERMISSION_CONTROL = "device.screen.control"

    val CONTROL_METHODS = setOf("wake", "dismiss_keyguard", "lock", "brightness")

    fun permissionFor(method: String): String =
        if (method in CONTROL_METHODS) PERMISSION_CONTROL else PERMISSION_READ

    /** Brightness is always readable; writes need 0-100 and the write-settings access. */
    sealed class BrightnessVerdict {
        data class Read(val canWrite: Boolean) : BrightnessVerdict()
        data class Write(val level: Int) : BrightnessVerdict()
        data object Invalid : BrightnessVerdict()
    }

    fun brightnessVerdict(args: kotlinx.serialization.json.JsonObject, canWriteSettings: Boolean): BrightnessVerdict {
        val level = (args["level"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            ?: return BrightnessVerdict.Read(canWriteSettings)
        if (level !in 0..100) return BrightnessVerdict.Invalid
        if (!canWriteSettings) return BrightnessVerdict.Invalid
        return BrightnessVerdict.Write(level)
    }

    fun needsWriteSettings(args: kotlinx.serialization.json.JsonObject): Boolean =
        (args["level"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() != null

    /** Keyguard dismiss verdict: secure keyguards are never bypassed (honest refusal). */
    sealed class KeyguardVerdict {
        data object DismissRequest : KeyguardVerdict()
        data class RefusedSecure(val secure: Boolean) : KeyguardVerdict()
    }

    fun keyguardVerdict(secure: Boolean): KeyguardVerdict =
        if (secure) KeyguardVerdict.RefusedSecure(secure = true) else KeyguardVerdict.DismissRequest

    /** Lock/wake ride the accessibility global actions; honest refusal when the service is off. */
    fun a11yActionVerdict(connected: Boolean): Envelope? =
        if (connected) null else Envelope.err(
            ErrorCode.CAPABILITY_UNAVAILABLE,
            "this action rides the accessibility service; enable AutoHost in Settings → Accessibility",
            reason = "A11Y_SERVICE_OFF",
            fixAction = "OPEN_ACCESSIBILITY_SETTINGS",
        )
}
