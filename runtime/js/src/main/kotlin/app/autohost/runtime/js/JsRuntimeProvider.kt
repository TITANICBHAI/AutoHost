package app.autohost.runtime.js

import app.autohost.core.common.AhJson
import app.autohost.core.common.AppDispatchers
import app.autohost.core.common.Clock
import app.autohost.core.model.RunSpec
import app.autohost.runtime.api.RunHandle
import app.autohost.runtime.api.RuntimeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * P11-01 (ADR-076): the JavaScript (QuickJS) runtime — the third runtime over the same broker
 * (plan §17 Phase 11, Gate 11 F2/F5). Runs **in the main process** like flows: no `:w*` worker,
 * one engine instance per run on a dedicated daemon thread, one heartbeat coroutine, and the
 * same `finished` event contract through the same broker binder. The engine itself sits behind
 * [JsEngineFactory] (QuickJS in :app, scripted fake in JVM tests); the orchestrator treats this
 * like any other runtime, so cancel/pause/resume, heartbeats and death detection behave
 * identically across runtimes.
 *
 * Honest limitations (recorded in ADR-076): an in-process runtime cannot be force-killed like a
 * worker process — kill() drives the engine interrupt handler, so a script unwinds at its next
 * interrupt point; a run wedged inside one broker call unwinds when that call returns. The
 * caps from [JsRunPolicy] bound the blast radius.
 */
class JsRuntimeProvider(
    private val engineFactory: JsEngineFactory,
    private val caller: JsBrokerCaller,
    private val dispatchers: AppDispatchers,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val secretOf: (String) -> String?,
    private val redact: (String) -> String,
    private val lowRam: Boolean = false,
    private val heartbeatIntervalMs: Long = 2_000L,
    private val sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) },
) : RuntimeProvider {

    override val type: String = "javascript"

    @Volatile
    private var deathListener: ((String) -> Unit)? = null

    override fun setDeathListener(listener: ((String) -> Unit)?) {
        deathListener = listener
    }

    override suspend fun start(spec: RunSpec): RunHandle {
        val taskPath = File(spec.taskPath)
        val entryFile = JsSources.resolveEntryFile(taskPath, spec.entry)
            ?: throw IllegalStateException(
                "no JS entry found in task folder (entry '${spec.entry}'; expected " +
                    "${JsSources.DEFAULT_ENTRY} beside task.json)",
            )
        // Read failures are typed; a throw from start() maps to FAILED:START_FAILED (like flows).
        val entrySource = JsSources.readSource(entryFile)

        val controls = JsRunControls()
        val startedMonotonicMs = clock.monotonicMs()
        val caps = JsRunPolicy.caps(lowRam = lowRam, timeoutMinutes = spec.limits.timeoutMinutes)
        val bridge = JsBridge(
            caller = caller,
            clock = clock,
            controls = controls,
            runInfo = JsRunInfo(
                runToken = spec.runToken,
                runId = spec.runId,
                attempt = spec.attempt,
                triggerType = spec.trigger.type,
                triggerPayload = spec.trigger.payload,
                startedAtIso = IsoFormat.ofMs(clock.nowMs()),
                config = spec.config,
                profile = spec.profile,
                checkpoint = spec.checkpoint ?: JsonObject(emptyMap()),
            ),
            secretOf = secretOf,
            redact = redact,
            sleeper = sleeper,
        )

        val engine = engineFactory.create()
        engine.installBridge { op, payload -> bridge.handle(op, payload) }
        engine.setLimits(caps.maxMemoryBytes, caps.maxStackBytes)
        engine.setInterruptHandler {
            JsRunPolicy.shouldInterrupt(
                cancelled = controls.cancelled.get(),
                startedMonotonicMs = startedMonotonicMs,
                nowMonotonicMs = clock.monotonicMs(),
                deadlineMs = caps.deadlineMs,
            )
        }

        val handle = JsInProcessRunHandle(spec.runId, controls, engine) { deathListener?.invoke(spec.runId) }
        val outcomeDeferred = CompletableDeferred<String?>()

        // The engine runs on a dedicated daemon thread: script evaluation blocks, and blocking a
        // dispatcher pool thread for a whole run is not acceptable. The coroutine only awaits.
        val engineThread = Thread({
            val outcome = runCatching {
                engine.runEntry(
                    preludeSource = JsPrelude.source(),
                    entrySource = entrySource,
                    entryName = entryFile.name,
                    moduleLoader = JsSources.moduleLoaderFor(taskPath),
                )
            }
            outcomeDeferred.complete(outcome.getOrNull())
        }, "autohost-js-${spec.runId}")
        engineThread.isDaemon = true

        val runJob = scope.launch(dispatchers.default) {
            val heartbeats = launch { heartbeatLoop(spec.runToken) }
            try {
                engineThread.start()
                val rawOutcome = outcomeDeferred.await() // null = engine threw = worker death
                val outcome = if (rawOutcome == null) {
                    null
                } else {
                    withContext(dispatchers.default) { classify(rawOutcome, bridge, controls) }
                }
                if (outcome == null) {
                    // Engine-internal crash: worker death, not a FAILED outcome (mirror of flows).
                    deathListener?.invoke(spec.runId)
                } else {
                    emitEvent(spec.runToken, buildJsonObject {
                        put("type", "finished")
                        put("data", outcome)
                    })
                }
            } finally {
                heartbeats.cancel()
                controls.finish()
            }
        }
        handle.attach(runJob, engineThread)
        return handle
    }

    /**
     * Maps the engine's outcome JSON to the standard run outcome (the JS mirror of
     * `execute_with_runtime`'s classification; pinned by JsOutcomeTest and the sim suite):
     * ok → SUCCEEDED (a `__result__` return or a ctx.result win, else OK/{}), cancelled →
     * CANCELLED:USER_CANCELLED, needs_user → WAITING_USER(+resume_hint), autohost → FAILED with
     * reason=reason||code, entry → FAILED:ENTRY_NOT_FOUND, exception → FAILED:EXCEPTION+stack.
     */
    internal fun classify(rawOutcome: String, bridge: JsBridge, controls: JsRunControls): JsonObject {
        val root = runCatching { AhJson.json.parseToJsonElement(rawOutcome) }.getOrNull() as? JsonObject
            ?: return failed("EXCEPTION", "engine returned an unparsable outcome", null)
        val error = root["error"] as? JsonObject
        return when {
            root["status"]?.let { (it as? JsonPrimitive)?.content } == "ok" -> {
                if (controls.cancelled.get()) {
                    buildJsonObject {
                        put("status", "CANCELLED"); put("reason", "USER_CANCELLED")
                        put("message", "run was cancelled")
                    }
                } else {
                    val value = root["value"] as? JsonObject
                    val marked = value?.get("__result__") as? JsonObject
                    val result = marked
                        ?: bridge.pendingResult.get()
                        ?: buildJsonObject { put("code", "OK"); put("data", JsonObject(emptyMap())) }
                    buildJsonObject {
                        put("status", "SUCCEEDED")
                        put("result", result)
                    }
                }
            }
            error != null -> when ((error["kind"] as? JsonPrimitive)?.content) {
                "cancelled" -> buildJsonObject {
                    put("status", "CANCELLED"); put("reason", "USER_CANCELLED")
                    put("message", (error["message"] as? JsonPrimitive)?.content ?: "run was cancelled")
                }
                "needs_user" -> buildJsonObject {
                    put("status", "WAITING_USER")
                    put("reason", (error["reason"] as? JsonPrimitive)?.content ?: "NEEDS_USER")
                    put("message", (error["message"] as? JsonPrimitive)?.content ?: "")
                    (error["resume_hint"] as? JsonPrimitive)?.content?.let { put("resume_hint", it) }
                }
                "entry" -> failed(
                    "ENTRY_NOT_FOUND",
                    (error["message"] as? JsonPrimitive)?.content ?: "no main() found in the JS entry",
                )
                "autohost" -> {
                    val code = (error["code"] as? JsonPrimitive)?.content ?: "INTERNAL"
                    val reason = (error["reason"] as? JsonPrimitive)?.content ?: code
                    buildJsonObject {
                        put("status", "FAILED")
                        put("reason", reason)
                        put("message", (error["message"] as? JsonPrimitive)?.content ?: code)
                        (error["details"])?.let { put("details", it) }
                    }
                }
                else -> failed(
                    "EXCEPTION",
                    (error["message"] as? JsonPrimitive)?.content ?: "the script threw an error",
                    error["stack"]?.takeIf { (it as? JsonPrimitive)?.content?.isNotBlank() == true },
                )
            }
            else -> failed("EXCEPTION", "engine outcome had neither status nor error", null)
        }
    }

    private fun failed(reason: String, message: String, stack: JsonElement? = null): JsonObject =
        buildJsonObject {
            put("status", "FAILED")
            put("reason", reason)
            put("message", message)
            stack?.let { put("traceback", it) }
        }

    private suspend fun heartbeatLoop(runToken: String) {
        while (true) {
            emitEvent(runToken, buildJsonObject { put("type", "heartbeat") })
            delay(heartbeatIntervalMs)
        }
    }

    private fun emitEvent(runToken: String, event: JsonObject) {
        // emit must never throw back into the runtime (same rule as the flow runtime).
        runCatching {
            caller.emit(runToken, AhJson.json.encodeToString(JsonObject.serializer(), event))
        }
    }
}

/** RunHandle for an in-process JS run: cooperative controls + the engine interrupt, kill = cancel. */
class JsInProcessRunHandle(
    override val runId: String,
    private val controls: JsRunControls,
    private val engine: JsEngine,
    private val onCrash: () -> Unit,
) : RunHandle {

    private val runJob = AtomicReference<Job?>(null)
    private val engineThread = AtomicReference<Thread?>(null)

    fun attach(job: Job, thread: Thread) {
        runJob.set(job)
        engineThread.set(thread)
        job.invokeOnCompletion { cause ->
            if (cause != null && cause !is java.util.concurrent.CancellationException) onCrash()
        }
    }

    override fun requestCancel() {
        controls.requestCancel()
    }

    override fun requestPause() = controls.requestPause()

    override fun requestResume() = controls.requestResume()

    override fun kill() {
        // Forceful: drive the engine interrupt (script unwinds at the next interrupt point) and
        // cancel the await. A run wedged inside a broker call unwinds when the call returns — the
        // honest in-process limitation (ADR-076).
        controls.requestCancel()
        runJob.get()?.cancel()
    }

    override fun release() {
        runJob.getAndSet(null)
        val thread = engineThread.getAndSet(null)
        if (thread != null && thread.isAlive) {
            thread.join(2_000) // best-effort drain; the interrupt handler does the real work
        }
        runCatching { engine.close() }
    }
}
