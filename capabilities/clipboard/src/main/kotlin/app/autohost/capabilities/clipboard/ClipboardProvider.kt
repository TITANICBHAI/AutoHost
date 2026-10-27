package app.autohost.capabilities.clipboard

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-05: the `device.clipboard` capability — `get` (focus-gated, honest about Android 10+'s
 * background-read rule) and `set` (always allowed). The framework calls sit behind [System]
 * so the provider is JVM-testable; the IME bridge remains the reliable background read path
 * (`device.ime.clipboard_get`).
 */
class ClipboardProvider(private val system: System = AndroidClipboard) : CapabilityProvider {

    /** Whether our process currently owns the window focus; injected for tests. */
    fun interface FocusSource {
        fun isFocused(): Boolean
    }

    interface System {
        fun read(): String?
        fun write(text: String): Boolean
    }

    override val capabilityId: String = "device.clipboard"
    override val providerId: String = "clipboard.system"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: kotlinx.serialization.json.JsonObject): String =
        "device.clipboard"

    override suspend fun invoke(ctx: CallContext, method: String, args: kotlinx.serialization.json.JsonObject): Envelope {
        return when (method) {
            "get" -> {
                val focus = focusSource?.isFocused() ?: system.isFocusedByDefault()
                when (ClipboardPolicy.readVerdict(focus)) {
                    ClipboardPolicy.ReadVerdict.ReadDirect -> {
                        val text = system.read()
                        if (text != null) {
                            Envelope.ok(buildJsonObject { put("text", text); put("focused", true) })
                        } else {
                            Envelope.ok(buildJsonObject { put("text", ""); put("empty", true); put("focused", true) })
                        }
                    }
                    ClipboardPolicy.ReadVerdict.RefuseBackground -> Envelope.err(
                        ErrorCode.CAPABILITY_UNAVAILABLE,
                        ClipboardPolicy.BACKGROUND_HINT,
                        reason = "CLIPBOARD_BACKGROUND_READ",
                        fixAction = "SELECT_IME",
                    )
                }
            }
            "set" -> {
                val text = (args["text"] as? JsonPrimitive)?.content
                    ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"text\" is required")
                if (text.length > MAX_SET_LEN) {
                    return Envelope.err(ErrorCode.INVALID_ARGS, "text exceeds the $MAX_SET_LEN-character cap")
                }
                if (system.write(text)) {
                    Envelope.ok(buildJsonObject { put("set", true) })
                } else {
                    Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "the system clipboard refused the write")
                }
            }
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.clipboard method '$method'")
        }
    }

    /** Settable in tests; defaults to the process-wide tracker the app wires at startup. */
    @Volatile
    var focusSource: FocusSource? = null

    private fun System.isFocusedByDefault(): Boolean = AppFocusTracker.focused

    companion object {
        const val MAX_SET_LEN = 1_000_000
    }
}

/**
 * Process-wide focus flag, maintained from the app layer via activity lifecycle callbacks.
 * Defaults to false (honest refusals before any wiring).
 */
object AppFocusTracker {
    @Volatile
    var focused: Boolean = false
}
