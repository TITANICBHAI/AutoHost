package app.autohost.core.engine.host

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
 * P8-11: the `tasks.run` capability (plan §6.1 W2: "Start another task/profile and await its
 * result (chaining). Depth/cycle limits; counts toward locks").
 *
 * The provider is pure policy + limits; starting/awaiting happens through [ChainRunner] (the app
 * wires it onto the orchestrator + run store). Depth: the runner embeds the current depth in the
 * child's trigger payload; the provider refuses a call that would exceed [MAX_CHAIN_DEPTH] —
 * which also terminates every cycle (a→b→a depth grows each hop). Cycles therefore cannot spin.
 *
 * Scope: `tasks.run` grant `{ "tasks": ["id", …] }` — the grant must exist (fail closed for a
 * DANGEROUS-ish capability) and name the target task; a missing/empty list denies everything.
 */
class TasksRunProvider(private val runner: ChainRunner) : CapabilityProvider {

    /** The app-side run chain: start + await terminal status. */
    interface ChainRunner {
        /** Starts the child run; null = task unknown or refused. `depth` rides the payload. */
        fun start(taskId: String, profileId: String?, args: JsonObject?, depth: Int): String?

        /** Blocks until the run reaches a terminal status or the deadline. */
        fun await(runId: String, timeoutMs: Long): Outcome

        sealed class Outcome {
            data class Terminal(val status: String, val result: String?) : Outcome()
            data object Timeout : Outcome()
            data object UnknownRun : Outcome()
        }
    }

    override val capabilityId: String = "tasks.run"
    override val providerId: String = "host.tasks.run"
    override val tier: String = "host"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method == "run") "tasks.run" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (method != "run") {
            return Envelope.err(ErrorCode.INVALID_ARGS, "unknown tasks.run method '$method'")
        }
        val grant = ctx.grants[PERMISSION]
            ?: return Envelope.err(
                ErrorCode.PERMISSION_DENIED,
                "the run has no tasks.run grant (chain targets are declared in Permission Review)",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        val task = (args["task"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"task\" is required")
        val allowed = (grant["tasks"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        if (task !in allowed) {
            return Envelope.err(
                ErrorCode.SCOPE_VIOLATION,
                "task \"$task\" is not in this run's tasks.run scope",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        val depth = ctx.runChainDepth()
        if (depth >= MAX_CHAIN_DEPTH) {
            return Envelope.err(
                ErrorCode.INTERNAL,
                "task chain would exceed the depth limit of $MAX_CHAIN_DEPTH (cycle or runaway chain)",
                reason = "CHAIN_DEPTH",
            )
        }
        val profile = (args["profile"] as? JsonPrimitive)?.content
        val callArgs = args["args"] as? JsonObject
        val runId = runner.start(task, profile, callArgs, depth + 1)
            ?: return Envelope.err(ErrorCode.NOT_FOUND, "task \"$task\" is not installed or refused")
        val timeout = ((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 600_000L)
            .coerceIn(1_000L, MAX_AWAIT_MS)
        return when (val outcome = runner.await(runId, timeout)) {
            is ChainRunner.Outcome.Terminal -> Envelope.ok(buildJsonObject {
                put("run_id", runId)
                put("status", outcome.status)
                outcome.result?.let { put("result", it) }
            })
            is ChainRunner.Outcome.Timeout -> Envelope.ok(buildJsonObject {
                put("run_id", runId)
                put("status", "RUNNING")
                put("await", "timeout")
            })
            is ChainRunner.Outcome.UnknownRun -> Envelope.err(ErrorCode.NOT_FOUND, "run $runId vanished (crash or cleanup)")
        }
    }

    /** Chain depth of the current run, embedded by the runner at start time. */
    private fun ctx.runChainDepth(): Int = grants[PERMISSION]?.get("__chain_depth")?.let {
        (it as? JsonPrimitive)?.content?.toIntOrNull()
    } ?: 0

    companion object {
        const val PERMISSION = "tasks.run"
        const val MAX_CHAIN_DEPTH = 3
        const val MAX_AWAIT_MS = 10L * 60 * 1000
    }
}
