package app.autohost.capabilities.screen

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
 * P8-10: the `ui.overlay` capability (plan §6.1 W2: "Floating panels, bubbles, highlight boxes").
 * Overlays ride the accessibility service's window (no extra permission, OEM quirks documented).
 *
 * Visible and stoppable (§4 rule 7): every overlay carries the run id; the controller clears a
 * run's overlays on the run's terminal transition and the global kill switch removes everything.
 * Framework drawing sits behind [Sink] so the provider is JVM-testable.
 */
class UiOverlayProvider(private val sink: Sink) : CapabilityProvider {

    /** The app-side overlay controller (a11y window). One view per kind per run. */
    interface Sink {
        fun connected(): Boolean
        fun highlight(runId: String, x: Int, y: Int, w: Int, h: Int, label: String?, durationMs: Long): Boolean
        fun panel(runId: String, text: String, corner: String): Boolean
        fun clear(runId: String): Int
    }

    override val capabilityId: String = "ui.overlay"
    override val providerId: String = "overlay.a11y"
    override val tier: String = "T1"

    override fun availability(env: DeviceEnv): Availability =
        if (sink.connected()) Availability.Available else Availability.NeedsSetup(
            "overlays ride the accessibility service; enable AutoHost in Settings → Accessibility",
            fixAction = "OPEN_ACCESSIBILITY_SETTINGS",
        )

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in METHODS) "ui.overlay" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (!sink.connected()) {
            return Envelope.err(
                ErrorCode.CAPABILITY_UNAVAILABLE,
                "overlays ride the accessibility service; enable AutoHost in Settings → Accessibility",
                reason = "A11Y_SERVICE_OFF",
                fixAction = "OPEN_ACCESSIBILITY_SETTINGS",
            )
        }
        return when (method) {
            "highlight" -> {
                val box = OverlayPolicy.parseHighlight(args)
                    ?: return Envelope.err(ErrorCode.INVALID_ARGS, "x/y/w/h must be non-negative ints (w/h > 0)")
                if (box.w > OverlayPolicy.MAX_DIMENSION || box.h > OverlayPolicy.MAX_DIMENSION) {
                    return Envelope.err(ErrorCode.INVALID_ARGS, "w/h exceed the ${OverlayPolicy.MAX_DIMENSION}px cap")
                }
                val shown = sink.highlight(
                    ctx.runId, box.x, box.y, box.w, box.h, box.label,
                    box.durationMs ?: OverlayPolicy.DEFAULT_DURATION_MS,
                )
                Envelope.ok(buildJsonObject { put("shown", shown) })
            }
            "panel" -> {
                val text = (args["text"] as? JsonPrimitive)?.content
                    ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"text\" is required")
                if (text.isBlank()) return Envelope.err(ErrorCode.INVALID_ARGS, "text must not be blank")
                if (text.length > OverlayPolicy.MAX_TEXT) {
                    return Envelope.err(ErrorCode.INVALID_ARGS, "text exceeds the ${OverlayPolicy.MAX_TEXT}-char cap")
                }
                val corner = (args["corner"] as? JsonPrimitive)?.content ?: "top"
                if (corner !in setOf("top", "bottom")) {
                    return Envelope.err(ErrorCode.INVALID_ARGS, "corner must be top|bottom")
                }
                Envelope.ok(buildJsonObject { put("shown", sink.panel(ctx.runId, text, corner)) })
            }
            "clear" -> Envelope.ok(buildJsonObject { put("cleared", true); put("removed", sink.clear(ctx.runId)) })
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown ui.overlay method '$method'")
        }
    }

    companion object {
        val METHODS = setOf("highlight", "panel", "clear")
    }
}

/** Pure parsing/validation for the overlay methods (unit-tested without any view). */
object OverlayPolicy {
    const val MAX_DIMENSION = 8_000
    const val MAX_TEXT = 500
    const val DEFAULT_DURATION_MS = 2_000L

    data class Highlight(val x: Int, val y: Int, val w: Int, val h: Int, val label: String?, val durationMs: Long?)

    fun parseHighlight(args: JsonObject): Highlight? {
        fun int(name: String): Int? =
            (args[name] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
        val x = int("x") ?: return null
        val y = int("y") ?: return null
        val w = int("w")?.takeIf { it > 0 } ?: return null
        val h = int("h")?.takeIf { it > 0 } ?: return null
        val label = (args["label"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.take(100)
        val ms = (args["ms"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it in 0..60_000 }
        return Highlight(x, y, w, h, label, ms)
    }
}
