package app.autohost.capabilities.shell

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
 * P9-10: the `device.logcat` capability (plan §6.1 W4 — "tail/capture logcat for evidence";
 * dev-grade, off by default). Methods:
 *
 *  - `snapshot` {tag?, level?, contains?, package?, max_lines?, since_ms?} → a bounded dump of the
 *    main buffer. The device-side filter takes tags; level/pid/text filters run on the parse.
 *  - `clear` {} → `logcat -c`.
 *
 * The `logcat` insecure-feature switch is enforced upstream by the P8-13 interceptor (both gates
 * must pass, plan §14.5). A `package` filter resolves the app's pid via `pidof` first — logcat
 * lines carry pids, not packages, and pretending otherwise would be dishonest.
 */
class LogcatProvider(private val transports: ShellTransports) : CapabilityProvider {

    override val capabilityId: String = "device.logcat"
    override val providerId: String = "shell.logcat"
    override val tier: String = "T3"

    override fun availability(env: DeviceEnv): Availability = when (transports.firstLive()) {
        null -> Availability.NeedsSetup("SHELL_TIER_NOT_PAIRED", "OPEN_SHELL_SETUP")
        else -> Availability.Available
    }

    override fun permissionFor(method: String, args: JsonObject): String? = when (method) {
        "snapshot", "clear" -> "device.logcat"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (ctx.grants["device.logcat"] == null) {
            return Envelope.err(
                ErrorCode.PERMISSION_DENIED,
                "the run has no device.logcat grant",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        return try {
            when (method) {
                "snapshot" -> snapshot(args)
                "clear" -> clear()
                else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.logcat method '$method'")
            }
        } catch (e: ShellTransportException) {
            transportError(e)
        }
    }

    private fun snapshot(args: JsonObject): Envelope {
        val maxLines = ((args["max_lines"] as? JsonPrimitive)?.content?.toIntOrNull() ?: DEFAULT_MAX_LINES)
            .coerceIn(1, ShellCommands.MAX_LOG_LINES)
        val sinceMs = (args["since_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (sinceMs != null && sinceMs < 0) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "since_ms must be >= 0")
        }
        val contains = (args["contains"] as? JsonPrimitive)?.content
        val minLevel = (args["level"] as? JsonPrimitive)?.content
        if (minLevel != null && minLevel.uppercase() !in LEVELS) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "level must be one of ${LEVELS.joinToString()}")
        }
        val pkg = (args["package"] as? JsonPrimitive)?.content
        var pid: Int? = null
        if (pkg != null) {
            val resolved = transports.firstLive()?.exec(ShellCommands.pidOf(pkg), PIDOF_TIMEOUT_MS)
            pid = resolved?.stdout?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
            // An unknown pid is honest: the filter just drops (the app may not be running).
            if (pid == null) {
                return Envelope.ok(buildJsonObject {
                    put("lines", JsonArray(emptyList()))
                    put("count", 0)
                    put("package_pid", false)
                })
            }
        }
        val tags = listOfNotNull((args["tag"] as? JsonPrimitive)?.content)
        val command = ShellCommands.logcatDump(maxLines, tags)
        val live = transports.firstLive()
            ?: return Envelope.err(ErrorCode.NEEDS_SETUP, "no shell transport is connected", reason = "SHELL_TIER_NOT_PAIRED")
        val result = live.exec(command, SNAPSHOT_TIMEOUT_MS)
        var entries = LogcatOutput.parse(result.stdout)
        val beforeCount = entries.size
        entries = LogcatOutput.filter(entries, minLevel = minLevel, pid = pid, contains = contains)
        sinceMs?.let { since -> entries = entries.filter { (it.tsMs ?: Long.MAX_VALUE) >= since } }
        if (entries.size > maxLines) entries = entries.takeLast(maxLines)
        return Envelope.ok(buildJsonObject {
            put("lines", JsonArray(entries.map { entry ->
                buildJsonObject {
                    entry.tsMs?.let { put("ts", it) }
                    put("level", entry.level)
                    put("tag", entry.tag)
                    entry.pid?.let { put("pid", it) }
                    put("message", entry.message)
                }
            }))
            put("count", entries.size)
            put("parsed", beforeCount)
            pkg?.let { put("package", it) }
        })
    }

    private fun clear(): Envelope {
        val live = transports.firstLive()
            ?: return Envelope.err(ErrorCode.NEEDS_SETUP, "no shell transport is connected", reason = "SHELL_TIER_NOT_PAIRED")
        val result = live.exec(ShellCommands.logcatClear(), CLEAR_TIMEOUT_MS)
        return Envelope.ok(buildJsonObject { put("cleared", result.exitCode == null || result.exitCode == 0) })
    }

    private fun transportError(e: ShellTransportException): Envelope = when (e.reason) {
        ShellTransportException.Reason.TIMEOUT -> Envelope.err(ErrorCode.TIMEOUT, e.message ?: "logcat timed out")
        ShellTransportException.Reason.NOT_CONNECTED -> Envelope.err(
            ErrorCode.NEEDS_SETUP,
            e.message ?: "no shell transport is connected",
            reason = "SHELL_TIER_NOT_PAIRED",
            fixAction = "OPEN_SHELL_SETUP",
        )
        else -> Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "logcat failed")
    }

    companion object {
        val LEVELS = setOf("V", "D", "I", "W", "E", "F")
        const val DEFAULT_MAX_LINES = 500
        const val SNAPSHOT_TIMEOUT_MS = 20_000L
        const val CLEAR_TIMEOUT_MS = 5_000L
        const val PIDOF_TIMEOUT_MS = 5_000L
    }
}
