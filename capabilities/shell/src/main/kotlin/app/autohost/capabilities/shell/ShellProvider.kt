package app.autohost.capabilities.shell

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
 * P9-04: the `device.shell` capability (plan §6.1 — T3, DANGEROUS). Methods:
 *
 *  - `run` {command, timeout_ms?} → allowlisted single command execution (the allowlist verdict is
 *    enforced here **and** the `shell` insecure-feature switch is enforced upstream by
 *    InsecureFeatureInterceptor; both gates must pass — plan §14.5).
 *  - `transports` {} → the live status of every configured transport (Setup & Health feed).
 *
 * The allowlist only gates *task-submitted* commands. The providers' internal commands (uiautomator
 * dump, screencap, input) run through the same transports under the `device.ui`/`device.screenshot`
 * grants and are part of those capabilities' contracts.
 */
class ShellProvider(private val transports: ShellTransports) : CapabilityProvider {

    override val capabilityId: String = "device.shell"
    override val providerId: String = "shell.transports"
    override val tier: String = "T3"

    override fun availability(env: DeviceEnv): Availability = when (val live = transports.firstLive()) {
        null -> {
            val statuses = transports.statuses()
            val awaiting = statuses.any { it.second is TransportStatus.AwaitingAuthorization }
            val draft = statuses.any { (_, status) ->
                status is TransportStatus.Unsupported && status.reason.contains("DRAFT")
            }
            when {
                awaiting -> Availability.NeedsSetup(
                    reason = "SHELL_AWAITING_AUTHORIZATION",
                    fixAction = "OPEN_SHELL_SETUP",
                )
                draft -> Availability.NeedsSetup(
                    reason = "SHELL_TIER_NOT_PAIRED",
                    fixAction = "OPEN_SHELL_SETUP",
                )
                else -> Availability.NeedsSetup(
                    reason = "SHELL_TIER_NOT_PAIRED",
                    fixAction = "OPEN_SHELL_SETUP",
                )
            }
        }
        else -> Availability.Available
    }

    override fun permissionFor(method: String, args: JsonObject): String? = when (method) {
        "run", "transports" -> "device.shell"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "run" -> run(ctx, args)
        "transports" -> transportsStatus(ctx)
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.shell method '$method'")
    }

    private fun run(ctx: CallContext, args: JsonObject): Envelope {
        val command = (args["command"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"command\" is required")
        val timeout = (args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
        if (timeout < 1 || timeout > MAX_TIMEOUT_MS) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "timeout_ms must be 1..$MAX_TIMEOUT_MS")
        }
        val grant = ctx.grants["device.shell"]
        if (grant == null) {
            return Envelope.err(
                ErrorCode.PERMISSION_DENIED,
                "the run has no device.shell grant",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        val verdict = CommandAllowlist.verdict(grant, command)
        if (verdict !is CommandAllowlist.Verdict.Allowed) {
            val detail = (verdict as CommandAllowlist.Verdict.Denied).detail
            return Envelope.err(
                ErrorCode.SCOPE_VIOLATION,
                detail,
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        val transport = transports.firstLive()
            ?: return Envelope.err(
                ErrorCode.NEEDS_SETUP,
                "no shell transport is connected",
                reason = "SHELL_TIER_NOT_PAIRED",
                fixAction = "OPEN_SHELL_SETUP",
            )
        return try {
            val started = System.nanoTime()
            val result = transport.exec(command, timeout)
            Envelope.ok(
                buildJsonObject {
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    if (result.exitCode != null) put("exit_code", result.exitCode)
                    put("mode", result.mode)
                    put("transport", transport.transportId)
                    put("duration_ms", (System.nanoTime() - started) / 1_000_000)
                },
            )
        } catch (e: ShellTransportException) {
            when (e.reason) {
                ShellTransportException.Reason.TIMEOUT -> Envelope.err(ErrorCode.TIMEOUT, e.message ?: "shell timed out")
                ShellTransportException.Reason.NOT_CONNECTED -> Envelope.err(
                    ErrorCode.NEEDS_SETUP,
                    e.message ?: "shell transport not connected",
                    reason = "SHELL_TIER_NOT_PAIRED",
                    fixAction = "OPEN_SHELL_SETUP",
                )
                else -> Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "shell transport failed")
            }
        }
    }

    private fun transportsStatus(ctx: CallContext): Envelope {
        if (ctx.grants["device.shell"] == null) {
            return Envelope.err(
                ErrorCode.PERMISSION_DENIED,
                "the run has no device.shell grant",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        val entries = transports.statuses().map { (transport, status) ->
            buildJsonObject {
                put("id", transport.transportId)
                put("describe", transport.describe())
                when (status) {
                    is TransportStatus.Live -> put("state", "live")
                    is TransportStatus.AwaitingAuthorization -> {
                        put("state", "awaiting_authorization")
                        put("reason", "SHELL_AWAITING_AUTHORIZATION")
                    }
                    is TransportStatus.NeedsSetup -> {
                        put("state", "needs_setup")
                        put("reason", status.reason)
                    }
                    is TransportStatus.Unsupported -> {
                        put("state", "unsupported")
                        put("reason", status.reason)
                    }
                }
            }
        }
        return Envelope.ok(
            buildJsonObject {
                put("transports", kotlinx.serialization.json.JsonArray(entries))
            },
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 15_000
        const val MAX_TIMEOUT_MS: Long = 120_000
    }
}
