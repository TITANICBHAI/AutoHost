package app.autohost.core.engine.orchestrator

import app.autohost.core.broker.EventSink
import app.autohost.core.broker.RunGrants
import app.autohost.core.broker.RunTokenStore
import app.autohost.core.common.AppDispatchers
import app.autohost.core.common.AhLog
import app.autohost.core.common.AhResult
import app.autohost.core.common.Clock
import app.autohost.core.common.Ids
import app.autohost.core.engine.tasks.GrantBuilder
import app.autohost.runtime.api.RunHandle
import app.autohost.runtime.api.RuntimeRegistry
import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.RunSpec
import app.autohost.core.model.RunStatus
import app.autohost.core.model.TaskManifest
import app.autohost.core.model.RunLimits
import app.autohost.core.model.RunPaths
import app.autohost.core.model.TriggerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** Everything the orchestrator needs to launch one run. */
class StartRequest(
    val taskId: String,
    val taskName: String,
    val manifest: TaskManifest,
    val config: JsonObject,
    val profile: JsonObject,
    val profileId: String?,
    val taskPath: String,
    val runDir: String,
    val triggerType: String = "manual",
    val triggerPayload: JsonObject = JsonObject(emptyMap()),
    val attempt: Int = 1,
    val checkpoint: JsonObject? = null,
)

/**
 * Owns the run lifecycle: QUEUED → STARTING → RUNNING (on first heartbeat) → terminal, heartbeat-loss and
 * worker-death detection, cooperative cancel with a 5 s kill deadline, and token revocation at finish
 * (HANDOFF §3). Implements the broker's [EventSink] so every worker event lands here first.
 */
class RunOrchestrator(
    private val store: RunStore,
    private val tokens: RunTokenStore,
    private val runtimes: RuntimeRegistry,
    private val grants: GrantBuilder,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    scope: CoroutineScope,
) : EventSink {

    private val handles = ConcurrentHashMap<String, RunHandle>()
    private val tokenByRun = ConcurrentHashMap<String, String>()
    private val runByToken = ConcurrentHashMap<String, String>()
    private val lastHeartbeat = ConcurrentHashMap<String, Long>()
    private val cancelJobs = ConcurrentHashMap<String, Job>()
    private val requests = ConcurrentHashMap<String, StartRequest>()
    private val lastActivity = ConcurrentHashMap<String, Long>()
    private val idleWarned = ConcurrentHashMap.newKeySet<String>()

    private val monitorScope = CoroutineScope(scope.coroutineContext + Job())

    init {
        // Heartbeat-loss watchdog: only RUNNING runs are judged; STARTING gets generous start time.
        monitorScope.launch(dispatchers.default) {
            while (true) {
                delay(HeartbeatMonitor.EXPECTED_INTERVAL_MS)
                val now = clock.monotonicMs()
                for ((runId, last) in lastHeartbeat) {
                    val record = store.get(runId) ?: continue
                    if (record.status == RunStatus.RUNNING && HeartbeatMonitor.isStale(last, now)) {
                        interrupt(runId, "HEARTBEAT_LOST", "no heartbeat for over ${HeartbeatMonitor.STALE_THRESHOLD_MS / 1000}s")
                    }
                }
            }
        }
        // P5-07: the idle watchdog (plan §12.5) — warn at 80 % of execution.idle_timeout_seconds,
        // cancel with FAILED:IDLE_TIMEOUT at 100 %. Activity = any broker call or worker event.
        monitorScope.launch(dispatchers.default) {
            while (true) {
                delay(IDLE_POLL_MS)
                val now = clock.monotonicMs()
                for ((runId, last) in lastActivity) {
                    val record = store.get(runId) ?: continue
                    if (!record.status.active) continue
                    val limitMs = idleLimitMsFor(runId) ?: continue
                    when (val verdict = IdleWatchdogPolicy.verdict(last, now, limitMs)) {
                        is IdleWatchdogPolicy.Verdict.Warn ->
                            if (idleWarned.add(runId)) {
                                store.appendEvent(
                                    runId,
                                    BrokerEvent(
                                        type = "log", level = "warn", stage = record.stage,
                                        message = "run idle ${verdict.consumedPercent}% of its idle budget",
                                    ),
                                )
                            }
                        is IdleWatchdogPolicy.Verdict.Expire -> {
                            AhLog.w(TAG, "idle timeout, cancelling run", buildJsonObject { put("run_id", runId) })
                            runCatching { handles[runId]?.kill() }
                            finishRun(runId, RunStatus.FAILED, "IDLE_TIMEOUT", "no activity within the idle timeout", null)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * P4-11: `ctx.next_run(after_minutes=…, at=…)` lands here (plan §11.7). The scheduler controller
     * clamps the proposal to the task's declared bounds and stores it as a script-proposed fire.
     */
    var onNextRun: ((runId: String, payload: JsonObject) -> Unit)? = null

    /** P5-07: per-run idle budget in ms, injected by :app (reads the manifest); null disables. */
    var idleLimitMs: ((runId: String) -> Long?)? = null

    /** Creates the run record and starts the runtime in the background. Never throws. */
    fun start(request: StartRequest): AhResult<RunRecord> {
        val runId = Ids.ulid(clock.nowMs())
        val grantMap = grants.build(request.manifest, request.config, request.profile)
        val token = tokens.issue(RunGrants(runId, request.taskId, request.profileId, grantMap, clock.nowMs()))
        tokenByRun[runId] = token
        runByToken[token] = runId
        val record = RunRecord(
            runId = runId,
            taskId = request.taskId,
            taskName = request.taskName,
            profileId = request.profileId,
            status = RunStatus.QUEUED,
            attempt = request.attempt,
            triggerType = request.triggerType,
            startedAtMs = clock.nowMs(),
        )
        store.create(record)
        requests[runId] = request
        AhLog.i(TAG, "run queued", buildJsonObject { put("run_id", runId); put("task_id", request.taskId) })

        monitorScope.launch(dispatchers.default) {
            val started = store.update(runId) { it.copy(status = RunStatus.STARTING) }
            if (started == null) {
                failImmediately(runId, "STATE_ERROR", "could not move run into STARTING")
                return@launch
            }
            val provider = runtimes.get(request.manifest.runtime.type)
            if (provider == null) {
                failImmediately(runId, "NO_RUNTIME", "no runtime provider of type '${request.manifest.runtime.type}' is registered")
                return@launch
            }
            try {
                val spec = buildSpec(request, runId, token, grantMap)
                val handle = provider.start(spec)
                handles[runId] = handle
            } catch (e: Throwable) {
                AhLog.e(TAG, "runtime start failed", buildJsonObject { put("run_id", runId); put("error", e.message ?: "") })
                failImmediately(runId, "START_FAILED", e.message ?: "runtime start failed")
            }
        }
        return AhResult.ok(record)
    }

    private fun buildSpec(request: StartRequest, runId: String, token: String, grantMap: Map<String, JsonObject>): RunSpec =
        RunSpec(
            runId = runId,
            taskId = request.taskId,
            taskVersion = request.manifest.version,
            runtime = request.manifest.runtime.type,
            entry = request.manifest.runtime.entry,
            taskPath = request.taskPath,
            runToken = token,
            attempt = request.attempt,
            profileId = request.profileId,
            config = request.config,
            profile = request.profile,
            checkpoint = request.checkpoint,
            trigger = TriggerInfo(request.triggerType, request.triggerPayload),
            grants = grantMap.keys.sorted(),
            limits = RunLimits(
                timeoutMinutes = request.manifest.execution.timeoutMinutes,
                idleTimeoutSeconds = request.manifest.execution.idleTimeoutSeconds,
            ),
            paths = RunPaths(
                runDir = request.runDir,
                evidenceDir = request.runDir.trimEnd('/') + "/evidence",
                tmpDir = request.runDir.trimEnd('/') + "/tmp",
            ),
        )

    private fun failImmediately(runId: String, reason: String, message: String) {
        finishRun(runId, RunStatus.FAILED, reason, message, null)
    }

    /** Broker events from any worker land here; the token identifies the run. */
    override fun onEvent(runToken: String, event: BrokerEvent) {
        val runId = runByToken[runToken]
        if (runId == null) {
            AhLog.w(TAG, "event for unknown token dropped", buildJsonObject { put("type", event.type) })
            return
        }
        val stamped = if (event.ts == 0L) event.copy(ts = clock.nowMs()) else event
        store.appendEvent(runId, stamped)
        // Idle watchdog (P5-07): worker heartbeats prove the process is alive (they come from a
        // separate thread even when the script is wedged), so they are NOT activity. Broker calls
        // (via the audit sink) and every other worker event are.
        if (stamped.type != EVENT_HEARTBEAT) onActivity(runId)
        when (stamped.type) {
            EVENT_HEARTBEAT -> onHeartbeat(runId)
            EVENT_PROGRESS -> {
                val progress = stamped.data?.get("progress")?.jsonPrimitive?.floatOrNull
                if (progress != null) store.update(runId) { it.copy(progress = progress) }
            }
            EVENT_STAGE_BEGIN -> store.update(runId) {
                it.copy(stage = stamped.stage ?: stamped.data?.get("stage")?.jsonPrimitive?.content ?: it.stage)
            }
            EVENT_STAGE_END -> store.update(runId) { it.copy(stage = null) }
            EVENT_RESULT -> store.update(runId) { it.copy(result = stamped.data) }
            EVENT_NEXT_RUN -> {
                val payload = stamped.data ?: JsonObject(emptyMap())
                onNextRun?.invoke(runId, payload)
            }
            EVENT_FINISHED -> onFinished(runId, stamped)
        }
    }

    private fun onHeartbeat(runId: String) {
        lastHeartbeat[runId] = clock.monotonicMs()
        store.update(runId) { record ->
            if (record.status == RunStatus.STARTING && RunStateMachine.canTransition(record.status, RunStatus.RUNNING)) {
                record.copy(status = RunStatus.RUNNING)
            } else {
                record
            }
        }
    }

    /** P5-07: broker calls feed the idle watchdog through the audit sink wiring in :app. */
    fun onActivity(runId: String) {
        lastActivity[runId] = clock.monotonicMs()
    }

    private fun idleLimitMsFor(runId: String): Long? = try {
        idleLimitMs?.invoke(runId)
    } catch (e: Exception) {
        null
    }

    private fun onFinished(runId: String, event: BrokerEvent) {
        val data = event.data ?: JsonObject(emptyMap())
        val statusText = data["status"]?.jsonPrimitive?.content ?: "FAILED"
        val reason = data["reason"]?.jsonPrimitive?.content
        val message = data["message"]?.jsonPrimitive?.content
        val result = data["result"] as? JsonObject
        val status = when (statusText) {
            "SUCCEEDED" -> RunStatus.SUCCEEDED
            "CANCELLED" -> RunStatus.CANCELLED
            "WAITING_USER" -> RunStatus.WAITING_USER
            else -> RunStatus.FAILED
        }
        finishRun(runId, status, reason, message, result)
    }

    private fun finishRun(
        runId: String,
        status: RunStatus,
        reason: String?,
        message: String?,
        result: JsonObject?,
    ) {
        val record = store.get(runId) ?: return
        val transition = RunStateMachine.transition(record.status, status)
        if (!transition.isOk) {
            AhLog.w(
                TAG, "ignoring illegal finish transition",
                buildJsonObject { put("run_id", runId); put("from", record.status.name); put("to", status.name) },
            )
            return
        }
        val to = transition.getOrNull() ?: record.status
        val updated = store.update(runId) {
            it.copy(
                status = to,
                endedAtMs = if (to.terminal) clock.nowMs() else it.endedAtMs,
                reason = reason ?: it.reason,
                message = message ?: it.message,
                result = result ?: it.result,
            )
        }
        if (updated != null && to.terminal) cleanup(runId)
    }

    /** Cooperative cancel: ask, give the worker 5 s, then kill (HANDOFF §3). */
    /**
     * Pause/resume are cooperative: forwarded to the live runtime handle, and the record flips
     * RUNNING ⇄ PAUSED (plan §12.3) so History and the Dev API show the true state.
     */
    fun requestPause(runId: String): Boolean {
        val handle = handles[runId] ?: return false
        handle.requestPause()
        store.update(runId) { record ->
            if (RunStateMachine.canTransition(record.status, RunStatus.PAUSED)) {
                record.copy(status = RunStatus.PAUSED)
            } else {
                record
            }
        }
        return true
    }

    fun requestResume(runId: String): Boolean {
        val handle = handles[runId] ?: return false
        handle.requestResume()
        store.update(runId) { record ->
            if (RunStateMachine.canTransition(record.status, RunStatus.RUNNING)) {
                record.copy(status = RunStatus.RUNNING)
            } else {
                record
            }
        }
        return true
    }

    /**
     * P5-05/P5-06: resume a WAITING_USER or INTERRUPTED run as a new attempt of the same run id,
     * loading the last checkpoint and stage records (plan §12.3). The [checkpointSource] supplies
     * the restored state; the runtime re-reads stage completion from the checkpoint itself.
     */
    fun resume(runId: String, checkpoint: JsonObject?): AhResult<RunRecord> {
        val record = store.get(runId) ?: return AhResult.err(
            app.autohost.core.model.ErrorCode.NOT_FOUND, "no run $runId", reason = "NO_SUCH_RUN",
        )
        val resumable = record.status == RunStatus.WAITING_USER || record.status == RunStatus.INTERRUPTED
        if (!resumable) return AhResult.err(
            app.autohost.core.model.ErrorCode.INTERNAL,
            "run ${record.status.name} cannot be resumed (only WAITING_USER/INTERRUPTED)",
            reason = "NOT_RESUMABLE",
        )
        val request = requests[runId]
        if (request == null) return AhResult.err(
            app.autohost.core.model.ErrorCode.INTERNAL,
            "the original start request is gone (process restarted); start the task again",
            reason = "NO_START_REQUEST",
        )
        // A fresh token: the old one was revoked at the terminal/interrupted finish.
        val attempt = record.attempt + 1
        return restartAttempt(request.copy(attempt = attempt, checkpoint = checkpoint), runId, attempt, record)
    }

    /** P5-05: auto-resume path used by the app's CrashReconciler — same run id, attempt+1. */
    fun resumeAfterInterrupt(request: StartRequest, runId: String): AhResult<RunRecord> {
        val record = store.get(runId)
        val attempt = (record?.attempt ?: request.attempt - 1) + 1
        return restartAttempt(request.copy(attempt = attempt), runId, attempt, record)
    }

    private fun restartAttempt(
        request: StartRequest,
        runId: String,
        attempt: Int,
        previous: RunRecord?,
    ): AhResult<RunRecord> {
        val transitioned = store.update(runId) { record ->
            if (RunStateMachine.canTransition(record.status, RunStatus.STARTING)) {
                record.copy(status = RunStatus.STARTING, attempt = attempt, endedAtMs = null,
                            reason = null, message = null)
            } else {
                record
            }
        }
        if (transitioned == null || transitioned.status != RunStatus.STARTING) {
            return AhResult.err(
                app.autohost.core.model.ErrorCode.INTERNAL,
                "could not move run ${previous?.status?.name ?: "?"} back to STARTING",
                reason = "RESUME_REFUSED",
            )
        }
        requests[runId] = request
        val grantMap = grants.build(request.manifest, request.config, request.profile)
        val token = tokens.issue(
            RunGrants(runId, request.taskId, request.profileId, grantMap, clock.nowMs()),
        )
        tokenByRun[runId] = token
        runByToken[token] = runId
        lastActivity[runId] = clock.monotonicMs()
        idleWarned.remove(runId)
        monitorScope.launch(dispatchers.default) {
            try {
                val spec = buildSpec(request, runId, token, grantMap)
                val handle = runtimes.get(request.manifest.runtime.type)?.start(spec)
                if (handle != null) handles[runId] = handle
                else failImmediately(runId, "NO_RUNTIME", "no runtime provider of type '${request.manifest.runtime.type}'")
            } catch (e: Throwable) {
                AhLog.e(TAG, "resume start failed", buildJsonObject { put("run_id", runId); put("error", e.message ?: "") })
                failImmediately(runId, "START_FAILED", e.message ?: "runtime start failed")
            }
        }
        AhLog.i(TAG, "run resumed as a new attempt", buildJsonObject { put("run_id", runId); put("attempt", attempt) })
        return AhResult.ok(transitioned)
    }

    fun cancel(runId: String): Boolean {
        val record = store.get(runId) ?: return false
        if (record.status.terminal) return false
        val handle = handles[runId]
        if (handle == null) {
            // Still QUEUED/STARTING without a handle: cancel outright.
            finishRun(runId, RunStatus.CANCELLED, "USER_CANCELLED", "cancelled before the worker started", null)
            return true
        }
        runCatching { handle.requestCancel() }
        cancelJobs.getOrPut(runId) {
            monitorScope.launch(dispatchers.default) {
                delay(CANCEL_KILL_DELAY_MS)
                val current = store.get(runId)
                if (current != null && !current.status.terminal) {
                    AhLog.w(TAG, "cancel deadline passed, killing worker", buildJsonObject { put("run_id", runId) })
                    runCatching { handle.kill() }
                    finishRun(runId, RunStatus.CANCELLED, "CANCEL_KILLED", "worker did not stop in time; killed", null)
                }
            }
        }
        return true
    }

    fun cancelAll() {
        for (record in store.runs.value) {
            if (record.status.active) cancel(record.runId)
        }
    }

    /** Worker process died. INTERRUPTED unless the run already reached a terminal state (HANDOFF §3). */
    fun onWorkerDeath(runId: String) {
        val record = store.get(runId) ?: return
        if (record.status.terminal) return
        interrupt(runId, "WORKER_DIED", "the worker process disappeared while the run was active")
    }

    private fun interrupt(runId: String, reason: String, message: String) {
        val handle = handles[runId]
        if (handle != null) runCatching { handle.kill() }
        finishRun(runId, RunStatus.INTERRUPTED, reason, message, null)
    }

    /** Terminal bookkeeping: revoke the run token, forget the handle, stop the cancel watchdog. */
    private fun cleanup(runId: String) {
        cancelJobs.remove(runId)?.cancel()
        val token = tokenByRun.remove(runId)
        if (token != null) {
            tokens.revoke(token)
            runByToken.remove(token)
        }
        lastHeartbeat.remove(runId)
        lastActivity.remove(runId)
        idleWarned.remove(runId)
        val handle = handles.remove(runId)
        if (handle != null) runCatching { handle.release() }
    }

    companion object {
        private const val TAG = "orchestrator"
        const val CANCEL_KILL_DELAY_MS = 5_000L
        const val IDLE_POLL_MS = 2_000L
        const val EVENT_HEARTBEAT = "heartbeat"
        const val EVENT_PROGRESS = "progress"
        const val EVENT_STAGE_BEGIN = "stage_begin"
        const val EVENT_STAGE_END = "stage_end"
        const val EVENT_RESULT = "result"
        const val EVENT_NEXT_RUN = "next_run"
        const val EVENT_FINISHED = "finished"
    }
}
