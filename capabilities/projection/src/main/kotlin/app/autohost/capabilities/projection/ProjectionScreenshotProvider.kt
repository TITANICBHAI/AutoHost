package app.autohost.capabilities.projection

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.common.Clock
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The `device.screenshot` capability (wave 1): one `capture` method over the provider chain
 * a11y (API 30+) → MediaProjection (plan Phase 3; the T3 `screencap` link arrives with the shell tier).
 *
 * The accessibility link is injected ([A11yShot] hook) so this module stays independent of `:capabilities:a11y`
 * (dependency direction, docs/CONVENTIONS.md); the app wires the lambda to the a11y screenshot API.
 *
 * Typed failures, never silent fallbacks: secure screens raise `SCREENSHOT_BLOCKED`, missing consent raises
 * `PROJECTION_NOT_GRANTED`, throttling raises `RATE_LIMITED` with a retry hint.
 */
class ProjectionScreenshotProvider(
    private val clock: Clock,
    private val evidenceDir: File,
    private val throttle: ScreenshotThrottlePolicy = ScreenshotThrottlePolicy(),
    private val a11yActive: () -> Boolean = { false },
    private val a11yCapture: () -> A11yShot = { A11yShot.Unavailable },
) : CapabilityProvider {

    /** Result of the injected accessibility screenshot attempt. */
    sealed class A11yShot {
        object Unavailable : A11yShot() {
            override fun toString(): String = "Unavailable"
        }

        data class Ok(val bitmap: android.graphics.Bitmap) : A11yShot()

        /** [errorCode] is the framework constant name (secure screen, no access, internal, …). */
        data class Blocked(val errorCode: String, val detail: String) : A11yShot()
    }

    override val capabilityId: String = "device.screenshot"
    override val providerId: String = "screen.chain"
    override val tier: String = "T1"

    override fun availability(env: DeviceEnv): Availability = when {
        a11yActive() -> Availability.Available
        ProjectionHolder.active() -> Availability.Available
        else -> Availability.NeedsSetup(
            reason = "PROJECTION_NOT_GRANTED",
            fixAction = "OPEN_PROJECTION_CONSENT",
        )
    }

    override fun permissionFor(method: String, args: JsonObject): String? = "device.screenshot"

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "capture" -> capture(ctx, args)
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.screenshot method '$method'")
    }

    private suspend fun capture(ctx: CallContext, args: JsonObject): Envelope {
        val region = args["region"]?.let { r ->
            (r as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        }
        val scale = (args["scale"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 1.0
        // Throttle first: a wedged capture must not become a capture storm (plan Phase 3).
        when (val decision = throttle.evaluate(clock.nowMs())) {
            is ScreenshotThrottlePolicy.Decision.Limit ->
                return Envelope.err(
                    ErrorCode.RATE_LIMITED,
                    "screenshots are throttled; retry in ${decision.retryAfterMs}ms",
                )
            ScreenshotThrottlePolicy.Decision.Allow -> Unit
        }
        // 1) accessibility screenshot (no consent dialog, no overlay) when the app reports it available
        if (a11yActive()) {
            val viaA11y: Envelope? = when (val outcome = a11yCapture()) {
                is A11yShot.Ok -> save(ctx, outcome.bitmap, region, scale, provider = "a11y")
                is A11yShot.Blocked ->
                    if (outcome.errorCode.endsWith("SECURE_SCREEN") || outcome.errorCode.endsWith("NO_ACCESSIBILITY_ACCESS")) {
                        Envelope.err(
                            ErrorCode.SCREENSHOT_BLOCKED,
                            "the screen refuses capture (${outcome.errorCode})",
                            reason = "SECURE_SCREEN",
                        )
                    } else {
                        Envelope.err(
                            ErrorCode.SCREENSHOT_BLOCKED,
                            "accessibility screenshot failed: ${outcome.detail}",
                            reason = outcome.errorCode,
                        )
                    }
                A11yShot.Unavailable -> null // fall through to projection
            }
            if (viaA11y != null) return viaA11y
        }
        // 2) MediaProjection when consented
        if (ProjectionHolder.active()) {
            val target = File(evidenceDir, "shot-${clock.nowMs()}.png")
            return try {
                val captured = Screenshotter.capture(target, region, scale)
                Envelope.ok(
                    buildJsonObject {
                        put("path", captured.path)
                        put("width", captured.width)
                        put("height", captured.height)
                        put("provider", captured.provider)
                    },
                )
            } catch (e: Screenshotter.Failed) {
                when (e.failure) {
                    Screenshotter.Failure.NotGranted -> Envelope.err(
                        ErrorCode.PROJECTION_NOT_GRANTED,
                        e.failure.message,
                        reason = "PROJECTION_NOT_GRANTED",
                    )
                    is Screenshotter.Failure.Unknown ->
                        Envelope.err(ErrorCode.SCREENSHOT_BLOCKED, e.failure.message)
                }
            }
        }
        // 3) nothing available: honest typed failure
        return Envelope.err(
            ErrorCode.PROJECTION_NOT_GRANTED,
            "no screenshot provider is available (accessibility screenshot needs API 30; projection needs consent)",
            reason = "PROJECTION_NOT_GRANTED",
            fixAction = "OPEN_PROJECTION_CONSENT",
        )
    }

    private fun save(
        ctx: CallContext,
        bitmap: android.graphics.Bitmap,
        region: List<Int>?,
        scale: Double,
        provider: String,
    ): Envelope {
        val target = File(evidenceDir, "shot-${clock.nowMs()}.png")
        return try {
            val cropped = Screenshotter.crop(bitmap, region)
            val scaled = Screenshotter.scale(cropped, scale)
            target.parentFile?.mkdirs()
            java.io.FileOutputStream(target).use { out -> scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
            Envelope.ok(
                buildJsonObject {
                    put("path", target.absolutePath)
                    put("width", scaled.width)
                    put("height", scaled.height)
                    put("provider", provider)
                },
            )
        } catch (e: RuntimeException) {
            Envelope.err(ErrorCode.SCREENSHOT_BLOCKED, "screenshot could not be written: ${e.message}")
        }
    }
}
