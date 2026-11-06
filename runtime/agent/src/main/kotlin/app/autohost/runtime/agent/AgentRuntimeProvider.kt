package app.autohost.runtime.agent

import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.common.AppDispatchers
import app.autohost.core.model.RunSpec
import app.autohost.runtime.api.RunHandle
import app.autohost.runtime.api.RuntimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * P13-12 (AGENT_RUNTIME §3/§4/§11): the agent runtime — in-process like the flow runtime, with
 * two deliberate differences from `FlowRuntimeProvider` (ADR-091 (9)):
 *
 *  1. `requestCancel` cancels the run JOB IMMEDIATELY — a cancelled coroutine is interrupted at
 *     its in-flight suspension point (the HTTP call's `disconnect()` and the in-flight broker
 *     call), not at the next cooperative checkpoint. The loop rethrows `CancellationException`
 *     and this provider rethrows it too — it is NEVER swallowed, never converted into
 *     worker death.
 *  2. An unexpected exception inside the loop is `FAILED:AGENT_INTERNAL_ERROR` (class name only
 *     in the message — no screen/exception content) — NOT worker death and NOT INTERRUPTED
 *     (§7.4). Do NOT copy FlowRuntimeProvider's catch-Throwable → deathListener.
 *
 * A separate 2 s heartbeat coroutine proves the process is alive even when a call wedges; the
 * orchestrator flips the run STARTING → RUNNING on the first heartbeat and its idle watchdog
 * counts heartbeats as NOT activity (the loop's per-turn events feed the watchdog instead).
 */
class AgentRuntimeProvider(
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val loopFactory: LoopFactory,
    private val emit: (runToken: String, eventJson: String) -> Unit,
    private val heartbeatIntervalMs: Long = 2_000L,
) : RuntimeProvider {

    override val type: String = "agent"

    /**
     * Builds the run's [AgentLoop] from the RunSpec. The production factory (:app, fully wired
     * in P13-13) closes over `spec.runToken` — the [BrokerTransport] is PER-RUN, token-bound
     * (the recorded lift-or-duplicate decision, see the repo tracker: DUPLICATE, not lift).
     */
    fun interface LoopFactory {
        fun create(spec: RunSpec, sink: AgentLoop.Sink, pauseGate: AgentLoop.PauseGate): AgentLoop
    }

    @Volatile
    private var deathListener: ((String) -> Unit)? = null

    override fun setDeathListener(listener: ((String) -> Unit)?) {
        deathListener = listener
    }

    override suspend fun start(spec: RunSpec): RunHandle {
        val agentConfig = AgentConfig.from(spec.config)
        if (agentConfig.goal.isBlank()) {
            throw IllegalStateException("config.agent.goal is required (AGENT_RUNTIME §4.2)")
        }
        if (agentConfig.packages.isEmpty()) {
            throw IllegalStateException("config.agent.packages must list at least one target app (§4)")
        }
        val controls = AgentControls()
        val handle = InProcessAgentHandle(spec.runId, controls) { deathListener?.invoke(spec.runId) }

        val paused = controls.paused
        val runJob = scope.launch(dispatchers.default) {
            val heartbeats = launch { heartbeatLoop(spec.runToken) }
            try {
                // P13-15: the §11 event log IS the loop's sink — stages, progress turn/max, the
                // agent.* kinds, the 80 %-crossings and the visible drop counters all map here.
                val eventLog = AgentEventLog(
                    goal = agentConfig.goal,
                    packages = agentConfig.packages,
                    providerProfileId = spec.profileId,
                    budgets = agentConfig.budgets.toLimits(),
                    emit = { event -> emitEvent(spec.runToken, event) },
                    // redact: :app's factory wires the shared Redactor (§13); the default is the
                    // documented identity — no secret ever passes through the agent's events
                )
                val loop = loopFactory.create(
                    spec,
                    sink = eventLog,
                    pauseGate = {
                        // cooperative pause: no new turn starts while paused; the wait is
                        // cancellable so requestCancel still interrupts it immediately
                        while (paused.get()) {
                            kotlinx.coroutines.coroutineContext.ensureActive()
                            delay(50)
                        }
                    },
                )
                val outcome = loop.run(agentConfig.goal)
                val (status, reason, message) = when (outcome) {
                    is AgentLoop.Outcome.Succeeded -> Triple(
                        "SUCCEEDED", null as String?,
                        outcome.claimedSummary ?: "the agent finished (host-verified)",
                    )
                    is AgentLoop.Outcome.Failed -> Triple("FAILED", outcome.reason, outcome.message)
                }
                eventLog.stop = AgentEventLog.StopInfo(status, reason, message)
                emitEvent(spec.runToken, finishedEvent(status, reason, message))
            } catch (e: CancellationException) {
                // ADR-091 (9): cancellation is the orchestrator's own CANCELLED path — rethrow,
                // never swallowed, never reported as worker death.
                throw e
            } catch (e: Throwable) {
                // §7.4: unexpected exception → FAILED:AGENT_INTERNAL_ERROR (class name only) —
                // NOT worker death and NOT INTERRUPTED.
                AhLog.e(TAG, "agent loop failed with an internal error", buildJsonObject {
                    put("run_id", spec.runId); put("error_class", e.javaClass.simpleName)
                })
                emitEvent(spec.runToken, finishedEvent("FAILED", "AGENT_INTERNAL_ERROR", e.javaClass.simpleName))
            } finally {
                heartbeats.cancel()
                controls.finish()
            }
        }
        handle.attach(runJob)
        AhLog.i(TAG, "agent run started in-process", buildJsonObject {
            put("run_id", spec.runId); put("packages", agentConfig.packages.size)
        })
        return handle
    }

    private suspend fun heartbeatLoop(runToken: String) {
        while (true) {
            emitEvent(runToken, buildJsonObject { put("type", "heartbeat") })
            delay(heartbeatIntervalMs)
        }
    }

    private fun emitEvent(runToken: String, event: JsonObject) {
        // No ts here: the orchestrator stamps arrival time (one time source, like the flow runtime).
        runCatching {
            emit(runToken, AhJson.json.encodeToString(JsonObject.serializer(), event))
        } // emit must never throw back into the loop
    }

    private fun finishedEvent(status: String, reason: String?, message: String?): JsonObject =
        buildJsonObject {
            put("type", "finished")
            put("data", buildJsonObject {
                put("status", status)
                reason?.let { put("reason", it) }
                message?.let { put("message", it) }
            })
        }

    companion object {
        private const val TAG = "agent-runtime"
    }
}

/** Shared pause/cancel/finish flags between the handle (any thread) and the run coroutine. */
class AgentControls {
    val paused = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    fun requestPause() {
        paused.set(true)
    }

    fun requestResume() {
        paused.set(false)
    }

    fun finish() {
        finished.set(true)
        paused.set(false)
    }
}

/**
 * RunHandle for an in-process agent run. Cancel = immediate job cancellation (ADR-091 (9)):
 * the coroutine is interrupted at its in-flight suspension point — the model call and the
 * broker call unwind now, not at the next checkpoint. `kill` is the same force (an in-process
 * coroutine has nothing stronger); the orchestrator's 5 s kill path stays as the backstop.
 */
class InProcessAgentHandle(
    override val runId: String,
    private val controls: AgentControls,
    private val onCrash: () -> Unit,
) : RunHandle {

    private val runJob = AtomicReference<Job?>(null)

    fun attach(job: Job) {
        runJob.set(job)
        job.invokeOnCompletion { cause ->
            // A crash here means the provider's own catch/emit failed — worker death, honestly.
            if (cause != null && cause !is java.util.concurrent.CancellationException) onCrash()
        }
    }

    override fun requestCancel() {
        runJob.get()?.cancel()
    }

    override fun requestPause() {
        controls.requestPause()
    }

    override fun requestResume() {
        controls.requestResume()
    }

    override fun kill() {
        runJob.get()?.cancel()
    }

    override fun release() {
        runJob.getAndSet(null)
    }
}
