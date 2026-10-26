package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P6-02: the declarative flow interpreter (plan §7.9). Executes a parsed [app.autohost.core.model.FlowDocument]
 * in-process as a coroutine through the same broker surface Python tasks use, so grants, scopes,
 * logging, evidence, pause/cancel and recovery behave exactly as for Python tasks — with no
 * worker process.
 *
 * This is the Kotlin mirror of the Python reference `sdk-python/autohost_ref/flows.py`; both are
 * held to the shared vectors in `testing/golden/flow_vectors.json` (outcome, exact broker call
 * log, event sequence — see FlowInterpreterGoldenTest).
 */
interface FlowBroker {
    /** One broker call; raises [BrokerCallException] on a typed broker error. */
    suspend fun call(capability: String, method: String, args: JsonObject): JsonObject
}

/** Host callback surface for one flow run (the Kotlin Exec of the Python reference). */
class FlowExec(
    val broker: FlowBroker,
    val emit: (JsonObject) -> Unit = {},
    val checkCancel: () -> Unit = {},
    val sleep: suspend (Long) -> Unit = {},
    val apiLevel: Int = 29,
    val subflows: Map<String, FlowDocument> = emptyMap(),
    val viewportWidth: Int = 1080,
    val viewportHeight: Int = 2400,
    /**
     * P6-08 guarded replay (recorded flows only; null for ordinary runs — golden vectors never
     * set it). Runs after the step's `when` gate passed and before the step executes: the guard
     * compares the live screen with the recorded pre-state and applies `on_mismatch`
     * (stop → FlowFailure, ask_user → FlowWaitUserException, continue → return).
     */
    val stepGuard: (suspend (step: app.autohost.core.model.FlowStep, scope: FlowScope) -> Unit)? = null,
)

/** Raised by `ask` when nobody answers: the run outcome becomes WAITING_USER. */
class FlowWaitUserException(message: String) : Exception(message)

/** Raised by the host when the run was cancelled (Exec.checkCancel). */
class FlowCancelledException : Exception("run was cancelled")

/** Interpreter-visible run state shared across hooks, subflows and control flow. */
class RunState internal constructor() {
    internal var result: Pair<String, JsonObject> = "OK" to JsonObject(emptyMap())
    internal var nextRun: JsonObject? = null
    internal var stepNo: Int = 0
    internal var depth: Int = 0
}

/** Final outcome; the host emits `{"type": "finished", "data": <this>}`. */
data class FlowOutcome(
    val status: String,
    val reason: String? = null,
    val message: String? = null,
    val result: Pair<String, JsonObject>? = null,
    val nextRun: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("status", status)
        reason?.let { put("reason", it) }
        message?.let { put("message", it) }
        result?.let { (code, data) ->
            put("result", buildJsonObject {
                put("code", code)
                put("data", data)
            })
        }
        nextRun?.let { put("next_run", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): FlowOutcome = FlowOutcome(
            status = obj.getValue("status").let { JsonValues.str(it) },
            reason = (obj["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            result = (obj["result"] as? JsonObject)?.let {
                JsonValues.str(it.getValue("code")) to (it["data"] as? JsonObject ?: JsonObject(emptyMap()))
            },
            nextRun = obj["next_run"] as? JsonObject,
        )
    }
}

object FlowInterpreter {
    const val DEFAULT_TIMEOUT_MS = 10_000L
    const val MAX_WHILE_ITERATIONS = 10_000
    const val MAX_FLOW_DEPTH = 8
    const val RETRY_BACKOFF_MS_DEFAULT = 500L

    /** Parses and runs a flow document (JSON text) — the one-liner hosts use. */
    suspend fun run(
        flowText: String,
        ex: FlowExec,
        config: JsonObject = JsonObject(emptyMap()),
        profile: JsonObject = JsonObject(emptyMap()),
        env: JsonObject = JsonObject(emptyMap()),
        secrets: Map<String, String> = emptyMap(),
        run: JsonObject = buildJsonObject { put("attempt", 1) },
    ): FlowOutcome = runFlow(FlowParser.parse(flowText), ex, config, profile, env, secrets, run)

    fun runFlow(
        doc: app.autohost.core.model.FlowDocument,
        ex: FlowExec,
        config: JsonObject = JsonObject(emptyMap()),
        profile: JsonObject = JsonObject(emptyMap()),
        env: JsonObject = JsonObject(emptyMap()),
        secrets: Map<String, String> = emptyMap(),
        run: JsonObject = buildJsonObject { put("attempt", 1) },
    ): FlowOutcome {
        val baseEnv: MutableMap<String, JsonElement> = LinkedHashMap()
        doc.env.forEach { (k, v) -> baseEnv[k] = v }
        env.forEach { (k, v) -> baseEnv[k] = v }
        val scope = FlowScope(config, profile, baseEnv, run, secrets)
        val state = RunState()
        var outcome: FlowOutcome
        try {
            ex.checkCancel()
            runSteps(doc.onFlowStart, scope, ex, state)
            runSteps(doc.steps, scope, ex, state)
            runSteps(doc.onFlowComplete, scope, ex, state)
            outcome = FlowOutcome(status = "SUCCEEDED", result = state.result)
        } catch (wait: FlowWaitUserException) {
            outcome = FlowOutcome(status = "WAITING_USER", reason = "NEEDS_INPUT", message = wait.message)
        } catch (cancelled: FlowCancelledException) {
            outcome = FlowOutcome(status = "CANCELLED", reason = "USER_CANCELLED")
        } catch (failure: FlowFailure) {
            outcome = FlowOutcome(status = "FAILED", reason = failure.code, message = failure.message)
        }
        return if (state.nextRun != null) outcome.copy(nextRun = state.nextRun) else outcome
    }

    internal suspend fun runSteps(
        steps: List<app.autohost.core.model.FlowStep>,
        scope: FlowScope,
        ex: FlowExec,
        state: RunState,
    ) {
        for (step in steps) {
            ex.checkCancel()
            runStep(step, scope, ex, state)
        }
    }

    internal suspend fun runStep(
        step: app.autohost.core.model.FlowStep,
        scope: FlowScope,
        ex: FlowExec,
        state: RunState,
    ) {
        val labelHint = step.reportLabel
        try {
            val whenCondition = step.whenCondition
            if (whenCondition != null && !whenHolds(whenCondition, scope, ex, labelHint)) return
            // P6-08: guarded replay hook — after the when gate (skipped steps are not guarded),
            // before the step counter (a guard stop is the step's own failure, not a skip).
            ex.stepGuard?.invoke(step, scope)
            state.stepNo += 1
            val label = step.label ?: "${step.command}#${state.stepNo}"
            CommandExecutors.execute(step, scope, ex, state, label)
        } catch (failure: FlowFailure) {
            emitEvidence(ex, scope, labelHint, step.command, failure)
            if (step.optional) {
                ex.emit(buildJsonObject {
                    put("type", "log")
                    put("level", "warn")
                    put("message", scope.redact(
                        "step '$labelHint' failed and was skipped: ${failure.code} (${failure.message})"))
                })
                return
            }
            throw failure
        }
    }

    private fun whenHolds(
        whenCondition: app.autohost.core.model.FlowWhen,
        scope: FlowScope,
        ex: FlowExec,
        label: String,
    ): Boolean {
        try {
            whenCondition.visible?.let {
                if (find(ex, SelectorResolver.resolveLabels(it, scope, label), 1, label).isEmpty()) return false
            }
            whenCondition.notVisible?.let {
                if (find(ex, SelectorResolver.resolveLabels(it, scope, label), 1, label).isNotEmpty()) return false
            }
            whenCondition.trueExpr?.let { expr ->
                val value = try {
                    ExprEvaluator.eval(expr, scope)
                } catch (error: ExprError) {
                    throw FlowFailure("INVALID_ARGS", "when expression failed: ${error.message}", label = label)
                }
                when (JsonValues.bool(value)) {
                    false -> return false
                    null -> throw FlowFailure(
                        "INVALID_ARGS",
                        "when.true must evaluate to true/false, got ${ExprEvaluator.render(value)}",
                        label = label,
                    )
                    else -> Unit
                }
            }
            whenCondition.minApi?.let { if (ex.apiLevel < it) return false }
        } catch (error: ExprError) {
            throw FlowFailure("INVALID_ARGS", "when expression failed: ${error.message}", label = label)
        }
        return true
    }

    private fun emitEvidence(ex: FlowExec, scope: FlowScope, label: String, command: String, failure: FlowFailure) {
        ex.emit(buildJsonObject {
            put("type", "evidence")
            put("data", buildJsonObject {
                put("step", label)
                put("command", command)
                put("code", failure.code)
                put("retryable", failure.retryable)
                put("message", scope.redact(failure.message))
                put("selector", failure.selector ?: JsonNull)
            })
        })
    }

    internal suspend fun call(
        ex: FlowExec,
        capability: String,
        method: String,
        args: JsonObject,
        label: String,
        selector: JsonObject? = null,
    ): JsonObject = try {
        ex.broker.call(capability, method, args)
    } catch (error: BrokerCallException) {
        throw FlowFailure(error.code, error.message ?: error.code, error.retryable, selector, label)
    }

    internal suspend fun find(
        ex: FlowExec,
        wire: JsonObject,
        limit: Int,
        label: String = "when",
    ): JsonArray {
        val out = call(ex, "device.ui", "find", buildJsonObject {
            put("selector", wire)
            put("limit", limit)
        }, label, wire)
        return (out["nodes"] as? JsonArray) ?: buildJsonArray { }
    }

    internal fun timeoutMs(step: app.autohost.core.model.FlowStep): Long =
        step.timeoutMs ?: DEFAULT_TIMEOUT_MS
}
