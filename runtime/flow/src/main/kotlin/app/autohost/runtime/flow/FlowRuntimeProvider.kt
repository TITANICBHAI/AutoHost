package app.autohost.runtime.flow

import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.common.AppDispatchers
import app.autohost.core.model.Envelope
import app.autohost.core.model.RunSpec
import app.autohost.runtime.api.RunHandle
import app.autohost.runtime.api.RuntimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * P6-06: the declarative-flow runtime (plan §7.9) — runs flows **in the main process** with no
 * `:w*` worker (Gate 6-a). The orchestrator starts it like any other runtime; the provider parses
 * the flow document beside `task.json` ([FlowSources]), then executes it as a coroutine through
 * the same broker binder Python workers use, so grants, scopes, logging, evidence, pause/cancel
 * and recovery behave identically. A separate ticker coroutine emits heartbeats (mirrors the
 * worker heartbeat thread: STARTING → RUNNING, heartbeat-loss detection still applies); the
 * interpreter's own liveness is guarded by the idle watchdog via broker-call audit activity.
 */
class FlowRuntimeProvider(
    private val binder: FlowBinderTransport,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val apiLevel: Int = 29,
    private val viewportWidth: Int = 1080,
    private val viewportHeight: Int = 2400,
    private val heartbeatIntervalMs: Long = 2_000L,
) : RuntimeProvider {

    override val type: String = "flow"

    @Volatile
    private var deathListener: ((String) -> Unit)? = null

    override fun setDeathListener(listener: ((String) -> Unit)?) {
        deathListener = listener
    }

    override suspend fun start(spec: RunSpec): RunHandle {
        val taskPath = File(spec.taskPath)
        val flowFile = FlowSources.resolveFlowFile(taskPath, spec.entry)
            ?: throw IllegalStateException(
                "no flow document found in task folder (entry '${spec.entry}'; expected " +
                    "${FlowSources.DEFAULT_JSON} or ${FlowSources.DEFAULT_YAML} beside task.json)",
            )
        // FlowSourceException (bad YAML/JSON, parse errors) propagates: the orchestrator maps a
        // start() throw to FAILED:START_FAILED — fail closed, like the reference's parse errors.
        val doc = FlowSources.loadFlowDocument(flowFile)
        val subflows = FlowSources.loadSubflows(taskPath, flowFile)

        val controls = RunControls()
        val handle = InProcessRunHandle(spec.runId, controls) { deathListener?.invoke(spec.runId) }

        // The interpreter coroutine: one per run; kill() cancels it, normal completion follows
        // the `finished` event. Heartbeats run as a child so they stop with the run.
        val runJob = scope.launch(dispatchers.default) {
            val heartbeats = launch { heartbeatLoop(spec.runToken) }
            try {
                val ex = FlowExec(
                    broker = BinderFlowBroker(spec.runToken, binder, dispatchers.io),
                    emit = { event -> emitEvent(spec.runToken, event) },
                    checkCancel = { controls.checkpoint() },
                    sleep = { ms -> controls.sleep(ms) },
                    apiLevel = apiLevel,
                    subflows = subflows,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                )
                val outcome = FlowInterpreter.runFlow(
                    doc, ex,
                    config = spec.config,
                    profile = spec.profile,
                    env = JsonObject(emptyMap()),
                    // Secrets are host-injected only when the SecretsProvider ships (it is still a
                    // typed NOT_FOUND stub); ${secret.NAME} then fails NOT_FOUND — fail closed.
                    secrets = emptyMap(),
                    run = buildJsonObject { put("attempt", spec.attempt) },
                )
                emitFinished(spec.runToken, outcome)
            } catch (e: Throwable) {
                // A crashing interpreter is worker death, not a flow failure: report INTERRUPTED.
                AhLog.e(TAG, "flow interpreter crashed", buildJsonObject {
                    put("run_id", spec.runId); put("error", e.message ?: e.javaClass.simpleName)
                })
                deathListener?.invoke(spec.runId)
            } finally {
                heartbeats.cancel()
                controls.finish()
            }
        }
        handle.attach(runJob)
        AhLog.i(TAG, "flow run started in-process (no worker)", buildJsonObject {
            put("run_id", spec.runId); put("flow", flowFile.name)
        })
        return handle
    }

    /** Heartbeats from a separate coroutine — the process is alive even if a broker call wedges. */
    private suspend fun heartbeatLoop(runToken: String) {
        while (true) {
            emitEvent(runToken, buildJsonObject { put("type", "heartbeat") })
            delay(heartbeatIntervalMs)
        }
    }

    private fun emitEvent(runToken: String, event: JsonObject) {
        // No ts here: the orchestrator stamps arrival time with the shared Clock (events with
        // ts == 0 are stamped in RunOrchestrator.onEvent — one time source, HANDOFF §3).
        runCatching {
            binder.emit(runToken, AhJson.json.encodeToString(JsonObject.serializer(), event))
        } // emit must never throw back into the interpreter (same rule as BrokerBinder)
    }

    private fun emitFinished(runToken: String, outcome: FlowOutcome) {
        emitEvent(runToken, buildJsonObject {
            put("type", "finished")
            put("data", outcome.toJson())
        })
    }

    companion object {
        private const val TAG = "flow-runtime"
    }
}

/** One broker call over the AIDL binder; envelope errors become [BrokerCallException]. */
class BinderFlowBroker(
    private val runToken: String,
    private val binder: FlowBinderTransport,
    private val ioDispatcher: CoroutineDispatcher,
) : FlowBroker {

    override suspend fun call(capability: String, method: String, args: JsonObject): JsonObject {
        val argsJson = AhJson.json.encodeToString(JsonObject.serializer(), args)
        // Binder calls block; keep them off the default dispatcher pool (dispatchers are injected).
        val raw = withContext(ioDispatcher) {
            binder.call(runToken, capability, method, argsJson)
        }
        val envelope = decodeEnvelope(raw)
            ?: throw BrokerCallException(
                "INTERNAL", "unparsable broker envelope for $capability.$method", retryable = false,
            )
        if (!envelope.ok) {
            val error = envelope.error
            throw BrokerCallException(
                code = error?.code ?: "INTERNAL",
                message = error?.message ?: "",
                retryable = error?.retryable ?: false,
            )
        }
        return when (val result = envelope.result) {
            is JsonObject -> result
            null -> JsonObject(emptyMap())
            else -> buildJsonObject { put("value", result) } // scalar/array results, like the sim adapter
        }
    }

    private fun decodeEnvelope(text: String?): Envelope? {
        if (text == null) return null
        return try {
            AhJson.json.decodeFromString(Envelope.serializer(), text)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Control flags shared by the handle (caller thread) and the interpreter coroutine. Pause gates
 * inside [sleep] and between steps ([checkpoint]); cancel raises [FlowCancelledException] from
 * both. `finished` latches so late controls become no-ops (RunHandle contract).
 */
class RunControls {
    val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val pauseLock = Object()

    fun requestCancel() {
        cancelled.set(true)
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    fun requestPause() {
        paused.set(true)
    }

    fun requestResume() {
        paused.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    fun finish() {
        finished.set(true)
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    /** Called between steps; throws [FlowCancelledException] when cancelled, parks while paused. */
    fun checkpoint() {
        if (cancelled.get()) throw FlowCancelledException()
        waitWhilePaused()
        if (cancelled.get()) throw FlowCancelledException()
    }

    /** Cancellable, pause-aware sleep (retry backoff, repeat cadence). Monotonic deadline. */
    suspend fun sleep(ms: Long) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (!cancelled.get()) {
            waitWhilePaused()
            if (cancelled.get()) throw FlowCancelledException()
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) break
            delay(minOf(remainingMs, 50))
        }
        if (cancelled.get()) throw FlowCancelledException()
    }

    private fun waitWhilePaused() {
        synchronized(pauseLock) {
            while (paused.get() && !cancelled.get() && !finished.get()) {
                try {
                    pauseLock.wait(200)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }
}

/** RunHandle for an in-process flow: cooperative controls now, kill = job cancellation. */
class InProcessRunHandle(
    override val runId: String,
    private val controls: RunControls,
    private val onCrash: () -> Unit,
) : RunHandle {

    private val runJob = AtomicReference<Job?>(null)

    fun attach(job: Job) {
        runJob.set(job)
        job.invokeOnCompletion { cause ->
            if (cause != null && cause !is java.util.concurrent.CancellationException) onCrash()
        }
    }

    override fun requestCancel() {
        controls.requestCancel()
    }

    override fun requestPause() {
        controls.requestPause()
    }

    override fun requestResume() {
        controls.requestResume()
    }

    override fun kill() {
        // Forceful: nothing else can stop an in-process coroutine. The cancelled flag unwinds the
        // interpreter at the next checkpoint/sleep; job cancellation also cuts a wedged broker call.
        controls.requestCancel()
        runJob.get()?.cancel()
    }

    override fun release() {
        runJob.getAndSet(null) // detach; the job completes on its own after the finished event
    }
}
