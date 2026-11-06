package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import app.autohost.core.llm.ToolCall
import app.autohost.core.model.AhError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P13-11 (AGENT_RUNTIME §10): `ToolPipeline.execute(call)` is the ONLY way a model call reaches
 * the device:
 *
 * ```
 * validate args against the generated schema            (ArgValidator)
 *   → policy(risk, sensitivity, action kind, label hints) (ToolPolicy)
 *   → approval (if ask)                                   ui.prompt.ask `choice` via the SAME transport
 *   → TOCTOU re-validation (approved calls only):         foreground unchanged, target still
 *     resolves, not BLOCKED
 *   → budget reserve (the action budget lives HERE — §10's order)
 *   → BrokerTransport.call                                the one action path
 *   → cap what the model sees (12,000 chars)              the full result may go to evidence (P13-15)
 *   → log/audit
 * ```
 *
 * Approvals: choices `["Allow once", "Allow for this run", "Deny"]`, per-call timeout (default
 * 60 s; `ask` honours it). Timeout or dismissal → deny AND stop the run (`APPROVAL_TIMEOUT` —
 * the loop maps that reason to a run failure); Deny → the model gets `APPROVAL_DENIED` and may
 * choose another path. No "Always" in Stage A. The session allow-list ("Allow for this run") is
 * package-scoped, TRANSIENT (never persisted — the upstream rule) and skips only the sensitivity
 * ask.
 */
class ToolPipeline(
    private val catalog: ToolCatalog,
    private val transport: BrokerTransport,
    private val budget: RunBudget,
    private val packages: Set<String>,
    private val sensitivity: AppSensitivity,
    private val foregroundSource: ForegroundSource,
    private val revalidator: Revalidator,
    private val config: Config = Config(),
    private val log: PipelineLog = PipelineLog { _, _ -> },
) : AgentLoop.ToolExecutor {

    data class Config(
        val resultCharCap: Int = 12_000,
        val approvalTimeoutMs: Long = 60_000,
        val approvalChoices: List<String> = listOf("Allow once", "Allow for this run", "Deny"),
    )

    /** The current foreground package — the host-supplied observation (ADR-098 (4) rules apply). */
    fun interface ForegroundSource {
        suspend fun current(): String?
    }

    data class TargetInfo(
        val pkg: String?,       // null = unknown until the broker resolves (selector-only targets)
        val label: String?,     // the label the user sees (approval text; never "node 17")
        val isPassword: Boolean,
        val viaId: Boolean,     // resolved through the ViewIndex → re-validatable
        val node: Int? = null,  // the [n] id when viaId
    )

    /** The post-approval TOCTOU re-check (§10): production re-resolves through the live screen. */
    fun interface Revalidator {
        data class Revalidation(val foregroundPkg: String?, val targetPkg: String?, val stillResolvable: Boolean)

        suspend fun revalidate(tool: ToolCatalog.Tool, target: TargetInfo?): Revalidation
    }

    /** The pipeline's own log/audit channel (P13-15 wires it into the event log). */
    fun interface PipelineLog {
        fun on(event: String, data: JsonObject)
    }

    /** Transient per-run session allow-list ("Allow for this run") — package-scoped. */
    private val sessionAllowed = mutableSetOf<String>()

    override suspend fun execute(call: ToolCall, ctx: AgentLoop.TurnContext): JsonObject {
        val tool = catalog.find(call.name)
            ?: return errResult("INVALID_ARGS", null, "unknown tool '${call.name}'")
        val args = when (val validated = ArgValidator.validate(tool, call.arguments, packages)) {
            is AhResult.Ok -> validated.value
            is AhResult.Err -> return errOf(validated.error)
        }
        // resolve the target BEFORE policy (the ViewIndex carries ref → pkg/label/flags)
        val target = when (val resolved = resolveTarget(tool, args, ctx)) {
            is AhResult.Ok -> resolved.value
            is AhResult.Err -> return errOf(resolved.error)
        }
        val foreground = try {
            foregroundSource.current()
        } catch (e: CancellationException) {
            throw e
        }
        val decision = ToolPolicy.check(
            ToolPolicy.Input(tool, args, target.pkg, foreground, target.label, target.isPassword, sessionAllowed),
            ToolPolicy.Context(packages, sensitivity),
        )
        log.on("policy", buildJsonObject {
            put("tool", tool.name)
            put(
                "decision",
                when (decision) {
                    is ToolPolicy.Decision.Allow -> "allow"
                    is ToolPolicy.Decision.Ask -> "ask"
                    is ToolPolicy.Decision.Deny -> "deny"
                },
            )
        })
        when (decision) {
            is ToolPolicy.Decision.Deny -> return errResult(decision.code, decision.reason, decision.message)
            is ToolPolicy.Decision.Allow -> {}
            is ToolPolicy.Decision.Ask -> {
                when (val approval = askUser(tool, target)) {
                    Approval.Denied ->
                        return errResult("PERMISSION_DENIED", "APPROVAL_DENIED", "the user denied this action")
                    Approval.Timeout ->
                        return errResult(
                            "PERMISSION_DENIED", "APPROVAL_TIMEOUT",
                            "no answer in time for the approval ask — the run stops (deny by default)",
                        )
                    Approval.AllowOnce -> {}
                    Approval.AllowForRun -> {
                        (target.pkg ?: foreground)?.let { sessionAllowed.add(it) }
                    }
                }
                // TOCTOU: the screen may have moved while the ask was up — re-validate before acting
                val re = try {
                    revalidator.revalidate(tool, target)
                } catch (e: CancellationException) {
                    throw e
                }
                val reFg = re.foregroundPkg
                val reTarget = re.targetPkg
                when {
                    (reFg != null && sensitivity.isBlocked(reFg)) ||
                        (reTarget != null && sensitivity.isBlocked(reTarget)) ->
                        return errResult("PERMISSION_DENIED", "OBSERVATION_BLOCKED", "the screen changed to a blocked app")
                    target.viaId && !re.stillResolvable ->
                        return errResult("STALE_NODE", null, "the approved target is gone — re-observe first")
                    reFg != null && reFg !in packages ->
                        return errResult(
                            "PERMISSION_DENIED", "CAPABILITY_NOT_GRANTED",
                            "the foreground left this run's packages",
                        )
                }
            }
        }
        // the action budget reserve lives in the pipeline (§10's order); the loop maps the
        // BUDGET_* reason in the error result to a run failure
        if (tool.capability != null) {
            when (val verdict = budget.beforeAction()) {
                is RunBudget.Verdict.Exhausted ->
                    return errResult("CAPABILITY_UNAVAILABLE", verdict.reason, verdict.message)
                RunBudget.Verdict.Ok -> {}
            }
        }
        // host tools (finish/todo_write/wait) have no broker method — the loop handles finish and
        // the host handles the rest; they never touch the transport and never cost an action
        if (tool.capability == null || tool.permission == null || tool.method == null) {
            return buildJsonObject {
                put("ok", true)
                put("host", true)
                put("note", "host tool — handled by the host, not the broker")
            }
        }
        // the ONE action path — dispatch on the CAPABILITY id (the broker resolves
        // capability.method; the permission id is for policy/grants, not dispatch)
        val envelope = try {
            transport.call(tool.capability, tool.method, args)
        } catch (e: CancellationException) {
            throw e
        }
        log.on("result", buildJsonObject { put("tool", tool.name); put("ok", envelope["ok"]?.let { (it as? JsonPrimitive)?.content } == "true") })
        return cap(envelope, config.resultCharCap)
    }

    // -- internals -------------------------------------------------------------------------------

    private sealed interface Approval {
        data object AllowOnce : Approval
        data object AllowForRun : Approval
        data object Denied : Approval
        data object Timeout : Approval
    }

    private suspend fun askUser(tool: ToolCatalog.Tool, target: TargetInfo): Approval {
        val subject = target.label ?: target.pkg ?: tool.name
        val message = "Allow ${tool.name} on \"${subject.take(40)}\"?"
        val answer = try {
            transport.call(
                "ui.prompt", "ask",
                buildJsonObject {
                    put("kind", "choice")
                    put("message", message)
                    put("choices", JsonArray(config.approvalChoices.map { JsonPrimitive(it) }))
                    put("timeout_ms", config.approvalTimeoutMs)
                },
            )
        } catch (e: CancellationException) {
            throw e
        }
        val answered = answer.str("answered")?.toBooleanStrictOrNull() == true
        if (!answered) return Approval.Timeout // timeout, dismissed, cancelled — all deny and stop
        return when (answer.str("button")) {
            "Allow once" -> Approval.AllowOnce
            "Allow for this run" -> Approval.AllowForRun
            "Deny" -> Approval.Denied
            else -> Approval.Denied // an unknown answer is a deny — fail closed
        }
    }

    private fun resolveTarget(tool: ToolCatalog.Tool, args: JsonObject, ctx: AgentLoop.TurnContext): AhResult<TargetInfo> {
        // app_* tools carry the destination explicitly
        args.str("package")?.let { pkg ->
            return AhResult.ok(TargetInfo(pkg = pkg, label = pkg, isPassword = false, viaId = false))
        }
        // the click family resolves its [n] id through the CURRENT ViewIndex
        val idArg = args.str("id")
        if (idArg != null) {
            val n = idArg.removePrefix("[").removeSuffix("]").toIntOrNull()
                ?: return AhResult.err(
                    app.autohost.core.model.AhError.of(
                        app.autohost.core.model.ErrorCode.INVALID_ARGS,
                        "the id must be an observation id like [3] (generation-local); got '$idArg'",
                    ),
                )
            return when (val entry = AgentView.resolveForCall(ctx.view, ctx.previousView, n)) {
                is AhResult.Ok -> AhResult.ok(
                    TargetInfo(
                        pkg = entry.value.pkg,
                        label = entry.value.label.ifEmpty { null },
                        isPassword = "password" in entry.value.flags,
                        viaId = true,
                        node = n,
                    ),
                )
                is AhResult.Err -> AhResult.err(entry.error)
            }
        }
        args.str("selector")?.let { selector ->
            return AhResult.ok(TargetInfo(pkg = null, label = selector, isPassword = false, viaId = false))
        }
        return AhResult.ok(TargetInfo(pkg = null, label = null, isPassword = false, viaId = false))
    }

    private fun cap(envelope: JsonObject, cap: Int): JsonObject {
        val text = envelope.toString()
        if (text.length <= cap) return envelope
        return buildJsonObject {
            put("ok", true)
            put("truncated", true)
            put("preview", text.take(cap))
        }
    }

    companion object {
        fun errResult(code: String, reason: String?, message: String): JsonObject = buildJsonObject {
            put("ok", false)
            put("error", buildJsonObject {
                put("code", code)
                if (reason != null) put("reason", reason)
                put("message", message)
            })
        }

        fun errOf(error: app.autohost.core.model.AhError): JsonObject =
            errResult(error.code, error.reason, error.message)

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
    }
}
