package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import app.autohost.core.llm.ToolCall
import app.autohost.core.model.AhError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    /** INT-E1: the saved tasks the person allowed for this run (ids) and the names they know them by. */
    private val tasks: Set<String> = emptySet(),
    private val taskNames: Map<String, String> = emptyMap(),
    /** INT-E4: the value names each allowed task says it takes (task id to names); the model may hand over only these. */
    private val taskInputs: Map<String, Set<String>> = emptyMap(),
    /** INT-A7: how a `wait` call really waits (a seam, so tests do not sleep). */
    private val pauser: Pauser = Pauser { ms -> delay(ms) },
    /** INT-E3c: how the pipeline waits for a saved task this run started; null = the app gave none (`task_status` then says so). */
    private val taskRuns: TaskRuns? = null,
) : AgentLoop.ToolExecutor {

    data class Config(
        val resultCharCap: Int = 12_000,
        val approvalTimeoutMs: Long = 60_000,
        val approvalChoices: List<String> = listOf("Allow once", "Allow for this run", "Deny"),
        /** INT-A9: how long the person has to answer the model's own question (under the run's 180 s idle budget). */
        val askTimeoutMs: Long = 120_000,
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
        val ref: String? = null, // INT-A4: the phone service's own handle for that screen item (null = none known)
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

    /** INT-A7: how the `wait` tool really waits. */
    fun interface Pauser {
        suspend fun pause(ms: Long)
    }

    /** INT-E3c: how `task_status` waits for a saved task this run started (the app maps it onto its run records). */
    fun interface TaskRuns {
        suspend fun await(runId: String, timeoutMs: Long): Outcome

        sealed interface Outcome {
            /** The task is over: its status name and, when it left one, its result text. */
            data class Ended(val status: String, val result: String?) : Outcome

            /** The wait ran out and the task is still going. */
            data object StillRunning : Outcome

            /** The app has no record of that run any more (cleaned up, or the app was restarted). */
            data object Gone : Outcome
        }
    }

    /** Transient per-run session allow-list ("Allow for this run") — package-scoped. */
    private val sessionAllowed = mutableSetOf<String>()

    /**
     * INT-E1: saved tasks whose last start came back still RUNNING (the wait ran out). A task that is still going
     * is never started a second time in the same run — a second upload would be a second upload. The value is the run
     * id `run_task` gave back (INT-E3c), so the refusal can name it for `task_status`.
     */
    private val stillRunning = mutableMapOf<String, String?>()

    /** INT-E3c: every run id `run_task` handed back in this run, with its task. `task_status` answers for these and no others. */
    private val startedRuns = mutableMapOf<String, String>()

    override suspend fun execute(call: ToolCall, ctx: AgentLoop.TurnContext): JsonObject {
        val tool = catalog.find(call.name)
            ?: return errResult("INVALID_ARGS", null, "unknown tool '${call.name}'")
        val args = when (val validated = ArgValidator.validate(tool, call.arguments, packages, tasks)) {
            is AhResult.Ok<*> -> validated.value as JsonObject
            is AhResult.Err -> return errOf(validated.error)
        }
        // resolve the target BEFORE policy (the ViewIndex carries ref → pkg/label/flags)
        val target = when (val resolved = resolveTarget(tool, args, ctx)) {
            is AhResult.Ok<*> -> resolved.value as TargetInfo
            is AhResult.Err -> return errOf(resolved.error)
        }
        // INT-E1: refuse a second start BEFORE anything asks the person (no point asking for what cannot happen)
        if (tool.name == ToolPolicy.RUN_TASK) {
            val taskId = args.str("task")
            // INT-E4: the model may hand a task only the values that task says it takes; checked before anything asks the person
            val given = (args["inputs"] as? JsonObject)?.keys.orEmpty()
            if (taskId != null && given.isNotEmpty()) {
                val declared = taskInputs[taskId].orEmpty()
                val unknown = given.filter { it !in declared }
                if (unknown.isNotEmpty()) {
                    val label = target.label ?: taskId
                    return errResult(
                        "INVALID_ARGS", null,
                        if (declared.isEmpty()) {
                            "task '$label' takes no values: start it without inputs"
                        } else {
                            "task '$label' takes only these values: ${declared.joinToString(", ")} (not ${unknown.joinToString(", ")})"
                        },
                    )
                }
            }
            if (taskId != null && stillRunning.containsKey(taskId)) {
                val runId = stillRunning[taskId]
                // INT-E3c: when task_status is there, the model is told how to wait for it (by the run id it was given)
                val advice = if (taskRuns != null && runId != null) {
                    "use task_status with run_id '$runId' to wait for it to end"
                } else {
                    "wait, then look at the screen"
                }
                return errResult(
                    "CAPABILITY_UNAVAILABLE", null,
                    "task '${target.label ?: taskId}' was already started in this run and may still be going — " +
                        "do not start it again; $advice",
                )
            }
        }
        val foreground = try {
            foregroundSource.current()
        } catch (e: CancellationException) {
            throw e
        }
        val decision = ToolPolicy.check(
            ToolPolicy.Input(tool, args, target.pkg, foreground, target.label, target.isPassword, allowedForThisCall(tool, args)),
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
                when (val approval = askUser(tool, target, args)) {
                    Approval.Denied ->
                        return errResult("PERMISSION_DENIED", "APPROVAL_DENIED", "the user denied this action")
                    Approval.Timeout ->
                        return errResult(
                            "PERMISSION_DENIED", "APPROVAL_TIMEOUT",
                            "no answer in time for the approval ask — the run stops (deny by default)",
                        )
                    Approval.AllowOnce -> {}
                    Approval.AllowForRun -> {
                        // a saved task is its own subject: "Allow for this run" on a task must NOT add the app that
                        // happens to be on screen (that would silently loosen the click asks for that app)
                        val subject: String? = if (tool.name == ToolPolicy.RUN_TASK) {
                            args.str("task")?.let { ToolPolicy.taskSubject(it) }
                        } else {
                            target.pkg ?: foreground
                        }
                        subject?.let { sessionAllowed.add(it) }
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
                    // a saved task is not tied to the target apps: it may be started from anywhere
                    reFg != null && reFg !in packages && tool.name != ToolPolicy.RUN_TASK ->
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
            // INT-E3c: task_status waits for a saved task this run started; it is answered here, never by the broker
            if (tool.name == ToolPolicy.TASK_STATUS) return taskStatus(args)
            // INT-A7: `wait` really waits. It used to answer at once, so an agent waiting for a slow screen looked again at
            // an unchanged one and spent its turns doing it
            val waited = if (tool.name == WAIT_TOOL) waitSeconds(args) else null
            if (waited != null) pauser.pause(waited * 1_000L)
            return buildJsonObject {
                put("ok", true)
                put("host", true)
                if (waited != null) put("waited_seconds", waited) else put("note", "host tool — handled by the host, not the broker")
            }
        }
        // the ONE action path — dispatch on the CAPABILITY id (the broker resolves
        // capability.method; the permission id is for policy/grants, not dispatch)
        // INT-E1: a saved task waits for the task at most 90 s unless the model asked for less/more (cap 120 s in the
        // catalog) — the broker's own default is 10 minutes, far past the agent's 180 s idle budget
        // INT-A4: the model names a screen item by its [n] number; the phone service knows it by the handle (ref) its
        // snapshot gave it, and answers "needs 'ref' or 'selector'" to a bare id, so the number is swapped for the handle here
        val callArgs = if (tool.name == ToolPolicy.RUN_TASK) {
            taskCallArgs(args)
        } else {
            // INT-A5: and the words the model gave for `selector` / `expect` become the structured selector the service takes
            val forService = SelectorText.forPhoneService(target.ref?.let { handle -> withRef(args, handle) } ?: args)
            // INT-A9: the prompt waits 5 minutes unless told otherwise, and the run's idle watchdog gives up after 3: a
            // question left unanswered would end the run as "stopped making progress" instead of reaching the model as
            // "no answer". The model cannot pass a time (the catalog has none), so the window is set here.
            if (tool.name == ASK_USER_TOOL) withAskWindow(forService, config.askTimeoutMs) else forService
        }
        val envelope = try {
            transport.call(tool.capability, tool.method, callArgs)
        } catch (e: CancellationException) {
            throw e
        }
        if (tool.name == ToolPolicy.RUN_TASK) {
            val taskId = args.str("task")
            val runId = envelope.str("run_id")
            // INT-E3c: remember the run id, so task_status can answer for it (and for no other)
            if (taskId != null && runId != null) startedRuns[runId] = taskId
            if (taskId != null && envelope.str("status") == "RUNNING") stillRunning[taskId] = runId
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

    private suspend fun askUser(tool: ToolCatalog.Tool, target: TargetInfo, args: JsonObject): Approval {
        val subject = target.label ?: target.pkg ?: tool.name
        val withValues = tool.name == ToolPolicy.RUN_TASK && args.containsKey("inputs")
        val message = if (tool.name == ToolPolicy.RUN_TASK) {
            "Start the saved task \"${subject.take(40)}\"?" + valuesText(args)
        } else {
            "Allow ${tool.name} on \"${subject.take(40)}\"?"
        }
        // INT-E4: with values there is no "Allow for this run": each set of values is asked about on its own
        val offered = if (withValues) config.approvalChoices.filter { it != ALLOW_FOR_RUN } else config.approvalChoices
        val answer = try {
            transport.call(
                "ui.prompt", "ask",
                buildJsonObject {
                    put("kind", "choice")
                    put("message", message)
                    put("choices", JsonArray(offered.map { JsonPrimitive(it) }))
                    put("timeout_ms", config.approvalTimeoutMs)
                },
            )
        } catch (e: CancellationException) {
            throw e
        }
        val answered = answer.str("answered")?.toBooleanStrictOrNull() == true
        if (!answered) return Approval.Timeout // timeout, dismissed, cancelled — all deny and stop
        // INT-A6: a choice prompt answers {answered, choice} (PromptActivity); only the separate "dialog" kind says `button`.
        // Reading `button` here read every answer as nothing, so every approval came back as a deny.
        return when (answer.str("choice")) {
            "Allow once" -> Approval.AllowOnce
            "Allow for this run" -> Approval.AllowForRun
            "Deny" -> Approval.Denied
            else -> Approval.Denied // an unknown answer is a deny — fail closed
        }
    }

    /**
     * INT-E4: "Allow for this run" covers a task started WITHOUT values. A call that carries values is asked about every
     * time: the person approved a task, not whatever text the model (or a page it read) wants to hand it later.
     */
    private fun allowedForThisCall(tool: ToolCatalog.Tool, args: JsonObject): Set<String> =
        if (tool.name == ToolPolicy.RUN_TASK && args.containsKey("inputs")) {
            sessionAllowed - ToolPolicy.taskSubject(args.str("task").orEmpty())
        } else {
            sessionAllowed
        }

    /** INT-E4: what the broker's `tasks.run` is given: the model's `inputs` as the broker's own `args`, and the wait. */
    private fun taskCallArgs(args: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in args) put(if (key == "inputs") "args" else key, value)
        if (!args.containsKey("timeout_ms")) put("timeout_ms", DEFAULT_TASK_WAIT_MS)
    }

    /**
     * INT-E3c: waits for a saved task this run started and says how it stands, in the fields `run_task` uses. It answers
     * only for a `run_id` that this run's `run_task` handed back (a guessed or foreign id is refused the same way), never
     * touches the broker and never asks the person. A task that ended (or whose record is gone) may be started again.
     */
    private suspend fun taskStatus(args: JsonObject): JsonObject {
        val runId = args.str("run_id").orEmpty().trim()
        val taskId = startedRuns[runId]
            ?: return errResult(
                "INVALID_ARGS", null,
                "no saved task was started by this run with that run_id: pass the run_id that run_task gave back",
            )
        val runs = taskRuns
            ?: return errResult("CAPABILITY_UNAVAILABLE", null, "checking on a saved task is not available in this run")
        val waitMs = (args.str("timeout_ms")?.toLongOrNull() ?: DEFAULT_STATUS_WAIT_MS)
            .coerceIn(MIN_STATUS_WAIT_MS, MAX_STATUS_WAIT_MS)
        val result = when (val outcome = runs.await(runId, waitMs)) {
            is TaskRuns.Outcome.Ended -> {
                // only this run's own lock: an older run_id must never free a newer start of the same task
                if (stillRunning[taskId] == runId) stillRunning.remove(taskId)
                buildJsonObject {
                    put("ok", true)
                    put("run_id", runId)
                    put("status", outcome.status)
                    outcome.result?.let { put("result", it) }
                }
            }
            TaskRuns.Outcome.StillRunning -> buildJsonObject {
                put("ok", true)
                put("run_id", runId)
                put("status", "RUNNING")
                put("await", "timeout")
            }
            TaskRuns.Outcome.Gone -> {
                if (stillRunning[taskId] == runId) stillRunning.remove(taskId)
                errResult("NOT_FOUND", null, "that run is gone: the app no longer has a record of it")
            }
        }
        log.on("result", buildJsonObject { put("tool", ToolPolicy.TASK_STATUS); put("ok", (result["ok"] as? JsonPrimitive)?.content == "true") })
        return cap(result, config.resultCharCap)
    }

    /** INT-A9: [args] with the prompt's answer window set (any `timeout_ms` already there is replaced). */
    private fun withAskWindow(args: JsonObject, ms: Long): JsonObject = buildJsonObject {
        for ((key, value) in args) if (key != "timeout_ms") put(key, value)
        put("timeout_ms", ms)
    }

    /** INT-A4: [args] with the model's `id` taken out and the phone service's own handle for that screen item put in. */
    private fun withRef(args: JsonObject, ref: String): JsonObject = buildJsonObject {
        for ((key, value) in args) if (key != "id") put(key, value)
        put("ref", ref)
    }

    /** INT-A7: how long a `wait` call asks for: 1 to 10 seconds (the catalog already refuses more than 10). */
    private fun waitSeconds(args: JsonObject): Int = (args.str("seconds")?.toIntOrNull() ?: 1).coerceIn(1, MAX_WAIT_SECONDS)

    /** INT-E4: the values the model wants to hand over, one short line each, so the person sees what the task is given. */
    private fun valuesText(args: JsonObject): String {
        val given = args["inputs"] as? JsonObject ?: return ""
        if (given.isEmpty()) return ""
        val shown = given.entries.take(MAX_SHOWN_VALUES).joinToString("\n") { (name, value) ->
            "$name: ${shortLine((value as? JsonPrimitive)?.content.orEmpty(), MAX_SHOWN_VALUE_CHARS)}"
        }
        val more = given.size - MAX_SHOWN_VALUES
        return "\nWith these values:\n" + shown + if (more > 0) "\n(and $more more)" else ""
    }

    private fun shortLine(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1) + "..."
    }

    private fun resolveTarget(tool: ToolCatalog.Tool, args: JsonObject, ctx: AgentLoop.TurnContext): AhResult<TargetInfo> {
        // INT-E1: a saved task has no screen target; its label is the name the person gave it
        if (tool.name == ToolPolicy.RUN_TASK) {
            val taskId = args.str("task")
                ?: return AhResult.err(
                    app.autohost.core.model.AhError.of(
                        app.autohost.core.model.ErrorCode.INVALID_ARGS,
                        "run_task needs the id of a saved task",
                    ),
                )
            return AhResult.ok(
                TargetInfo(pkg = null, label = taskNames[taskId] ?: taskId, isPassword = false, viaId = false),
            )
        }
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
                        ref = entry.value.ref.ifEmpty { null },
                    ),
                )
                is AhResult.Err -> AhResult.err(entry.error)
            }
        }
        args.str("selector")?.let { selector ->
            if (selector.isBlank()) {
                return AhResult.err(
                    app.autohost.core.model.AhError.of(
                        app.autohost.core.model.ErrorCode.INVALID_ARGS,
                        "the selector is empty: give the words the element shows",
                    ),
                )
            }
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
        /** INT-E1: how long run_task waits when the model gave no timeout (under the agent's 180 s idle budget). */
        const val DEFAULT_TASK_WAIT_MS = 90_000L

        /** INT-E3c: how long task_status waits when the model gave no time, and its bounds (the catalog caps it at 120 s too). */
        const val DEFAULT_STATUS_WAIT_MS = 60_000L
        private const val MIN_STATUS_WAIT_MS = 1_000L
        private const val MAX_STATUS_WAIT_MS = 120_000L

        /** INT-A7: the host tool that pauses the run for a moment, and its cap in seconds (the same cap as the catalog's). */
        private const val WAIT_TOOL = "wait"

        /** INT-A9: the model's own question to the person. */
        private const val ASK_USER_TOOL = "ask_user"
        private const val MAX_WAIT_SECONDS = 10

        /** INT-E4: how many values the approval question lists (the rest are counted) and how much of each it shows. */
        private const val MAX_SHOWN_VALUES = 3
        private const val MAX_SHOWN_VALUE_CHARS = 60
        private const val ALLOW_FOR_RUN = "Allow for this run"

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
