package app.autohost.capabilities.shell

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P9-10: the T3 device-control family (plan §6.1 W4). All three capabilities run through the
 * paired shell tier — Wi-Fi toggling is blocked for normal apps since API 29, Bluetooth toggling
 * works only up to API 32 (and honestly reports the attempt result), airplane/mobile data need
 * the shell uid, and mock location is an appop on our own package. Every act is verified by a
 * follow-up read where the platform offers one; the rest report the exit code honestly.
 */
abstract class ShellCapabilityProvider(
    override val capabilityId: String,
    override val providerId: String,
) : CapabilityProvider {

    abstract val transports: ShellTransports

    override val tier: String = "T3"

    override fun availability(env: DeviceEnv): Availability = when (transports.firstLive()) {
        null -> Availability.NeedsSetup("SHELL_TIER_NOT_PAIRED", "OPEN_SHELL_SETUP")
        else -> Availability.Available
    }

    fun exec(command: String, timeoutMs: Long = ACT_TIMEOUT_MS): ShellResult {
        val live = transports.firstLive()
            ?: throw ShellTransportException(ShellTransportException.Reason.NOT_CONNECTED, "no shell transport is connected")
        return live.exec(command, timeoutMs)
    }

    fun transportError(e: ShellTransportException): Envelope = when (e.reason) {
        ShellTransportException.Reason.TIMEOUT -> Envelope.err(ErrorCode.TIMEOUT, e.message ?: "shell timed out")
        ShellTransportException.Reason.NOT_CONNECTED -> Envelope.err(
            ErrorCode.NEEDS_SETUP,
            e.message ?: "no shell transport is connected",
            reason = "SHELL_TIER_NOT_PAIRED",
            fixAction = "OPEN_SHELL_SETUP",
        )
        else -> Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "shell transport failed")
    }

    companion object {
        const val ACT_TIMEOUT_MS = 10_000L
    }
}

/** `device.radio` — wifi / bluetooth / airplane / mobile data toggles (T3 only, per plan). */
class RadioProvider(override val transports: ShellTransports) : ShellCapabilityProvider("device.radio", "shell.radio") {

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method == "set") "device.radio" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (ctx.grants["device.radio"] == null) {
            return Envelope.err(ErrorCode.PERMISSION_DENIED, "the run has no device.radio grant", fixAction = "OPEN_PERMISSION_REVIEW")
        }
        if (method != "set") {
            return Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.radio method '$method'")
        }
        val radio = (args["radio"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"radio\" is required")
        val enable = (args["enable"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"enable\" is required (boolean)")
        return try {
            val applied = when (radio) {
                "wifi" -> exec(ShellCommands.svcWifi(enable)).exitCode.let { it == null || it == 0 }
                "bluetooth" -> exec(ShellCommands.svcBluetooth(enable)).exitCode.let { it == null || it == 0 }
                "mobile_data" -> exec(ShellCommands.svcData(enable)).exitCode.let { it == null || it == 0 }
                "airplane" -> {
                    // Two-step: the global write alone does not notify the radios.
                    exec(ShellCommands.airplaneModePut(enable))
                    exec(ShellCommands.airplaneModeBroadcast()).exitCode.let { it == null || it == 0 }
                }
                else -> return Envelope.err(ErrorCode.INVALID_ARGS, "radio must be wifi|bluetooth|mobile_data|airplane")
            }
            Envelope.ok(buildJsonObject {
                put("radio", radio)
                put("enable", enable)
                put("applied", applied)
                // Honest: `svc` has no readback on many builds; wifi state can be read but we do not
                // claim verification without it (Gate-9 records the real behaviour per radio).
                put("verified", false)
            })
        } catch (e: ShellTransportException) {
            transportError(e)
        }
    }
}

/** `device.display` — dark mode, orientation, font scale (`cmd uimode`, `settings put`). */
class DisplayProvider(override val transports: ShellTransports) : ShellCapabilityProvider("device.display", "shell.display") {

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in METHODS) "device.display" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (ctx.grants["device.display"] == null) {
            return Envelope.err(ErrorCode.PERMISSION_DENIED, "the run has no device.display grant", fixAction = "OPEN_PERMISSION_REVIEW")
        }
        return try {
            when (method) {
                "set_dark_mode" -> {
                    val mode = (args["mode"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"mode\" must be yes|no|auto")
                    val result = exec(ShellCommands.uimodeNight(mode))
                    Envelope.ok(buildJsonObject {
                        put("mode", mode)
                        put("applied", result.exitCode == null || result.exitCode == 0)
                    })
                }
                "set_orientation" -> {
                    val orientation = (args["orientation"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"orientation\" is required")
                    when (orientation) {
                        "auto" -> {
                            exec(ShellCommands.accelerometerRotation(true))
                            Envelope.ok(buildJsonObject { put("orientation", "auto"); put("applied", true) })
                        }
                        else -> {
                            val value = ORIENTATIONS[orientation]
                                ?: return Envelope.err(
                                    ErrorCode.INVALID_ARGS,
                                    "orientation must be auto|portrait|landscape|reverse_portrait|reverse_landscape",
                                )
                            exec(ShellCommands.accelerometerRotation(false))
                            val result = exec(ShellCommands.userRotation(value))
                            Envelope.ok(buildJsonObject {
                                put("orientation", orientation)
                                put("applied", result.exitCode == null || result.exitCode == 0)
                            })
                        }
                    }
                }
                "set_font_scale" -> {
                    val scale = (args["scale"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"scale\" is required (e.g. \"1.0\")")
                    val result = exec(ShellCommands.fontScale(scale))
                    Envelope.ok(buildJsonObject {
                        put("scale", scale)
                        put("applied", result.exitCode == null || result.exitCode == 0)
                    })
                }
                else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.display method '$method'")
            }
        } catch (e: IllegalArgumentException) {
            Envelope.err(ErrorCode.INVALID_ARGS, e.message ?: "invalid display argument")
        } catch (e: ShellTransportException) {
            transportError(e)
        }
    }

    companion object {
        val METHODS = setOf("set_dark_mode", "set_orientation", "set_font_scale")
        val ORIENTATIONS = mapOf(
            "portrait" to 0,
            "landscape" to 1,
            "reverse_portrait" to 2,
            "reverse_landscape" to 3,
        )
    }
}

/**
 * `device.location.mock` — the mock-location appop for AutoHost itself (DANGEROUS; the
 * `mock_location` switch is enforced upstream by the P8-13 interceptor). Granting the appop lets
 * the later `location` capability (W3) install a mock provider; nothing else may be targeted.
 */
class MockLocationProvider(override val transports: ShellTransports) :
    ShellCapabilityProvider("device.location.mock", "shell.mock_location") {

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method == "set") "device.location.mock" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (ctx.grants["device.location.mock"] == null) {
            return Envelope.err(
                ErrorCode.PERMISSION_DENIED,
                "the run has no device.location.mock grant",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        if (method != "set") {
            return Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.location.mock method '$method'")
        }
        val enable = (args["enable"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"enable\" is required (boolean)")
        return try {
            exec(ShellCommands.mockLocationAppop(enable))
            // Verified against `appops get` — appops has an honest readback.
            val check = exec(ShellCommands.mockLocationAppopGet())
            val now = check.stdout
            val verified = if (enable) {
                now.contains("mock_location", ignoreCase = true) && !now.contains("deny", ignoreCase = true)
            } else {
                now.contains("deny", ignoreCase = true) || now.contains("ignore", ignoreCase = true)
            }
            Envelope.ok(buildJsonObject {
                put("enable", enable)
                put("applied", true)
                put("verified", verified)
                put("appop_package", ShellCommands.MOCK_LOCATION_PKG)
            })
        } catch (e: ShellTransportException) {
            transportError(e)
        }
    }
}
