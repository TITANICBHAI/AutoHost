package app.autohost.schedule

import android.app.Application
import app.autohost.AutoHostApp
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.common.AhResult
import app.autohost.core.common.Clock
import app.autohost.core.data.db.ScheduleStateEntity
import app.autohost.core.engine.schedule.ReconcileDecision
import app.autohost.core.engine.schedule.ReconcileInput
import app.autohost.core.engine.schedule.ReconcilePolicy
import app.autohost.core.engine.schedule.ScheduleEngine
import app.autohost.core.engine.schedule.ScheduleState
import app.autohost.core.engine.schedule.SchedulerBackend
import app.autohost.core.model.ScheduleRule
import app.autohost.core.model.isHhMm
import app.autohost.core.model.parseIsoToMs
import app.autohost.core.model.toMinutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * P4-04..P4-07/P4-11: the scheduler controller — the one object that owns armed schedules
 * (plan §11.3). It binds the persisted schedule states to a [SchedulerBackend], fires runs
 * through the app's start path, applies the misfire policy via the reconcile pass, and stores
 * clamped `ctx.next_run` proposals (§11.7, labelled script-proposed).
 *
 * Android pieces (receivers/worker/job service) stay thin: they resolve the graph and call in.
 */
class ScheduleController(
    private val app: Application,
    private val clock: Clock,
    private val engine: ScheduleEngine,
    private val backends: Map<String, SchedulerBackend>,
    private val scope: CoroutineScope,
    private val fireAction: suspend (taskId: String, profileId: String?, dueAtMs: Long, triggerType: String) -> AhResult<String?>,
    private val preferredBackend: () -> String = { "auto" },
) {

    private val data get() = (app as AutoHostApp).graph.data
    private val runStore get() = (app as AutoHostApp).graph.runStore

    /** Explicit per-schedule backend wins, else the user preference, else the alarm backend. */
    private fun backendFor(name: String?): SchedulerBackend =
        backends[name]
            ?: backends[preferredBackend()]
            ?: backends[BACKEND_ALARM]
            ?: backends.values.first()

    // ------------------------------------------------------------------ arm / disable

    /** Upsert (or replace) a schedule rule and arm its next fire (§11.3: one alarm, re-armed). */
    fun upsertAndArm(rule: ScheduleRule) {
        val problems = rule.validate()
        if (problems.isNotEmpty()) {
            AhLog.w(
                TAG, "schedule rejected",
                AhJson.meta { put("id", rule.id); put("problems", problems.joinToString(";")) },
            )
            return
        }
        scope.launch {
            val now = clock.nowMs()
            val existing = data.schedules.byScheduleId(rule.id)
            data.schedules.upsert(
                ScheduleStateEntity(
                    rowId = existing?.rowId ?: 0,
                    scheduleId = rule.id,
                    taskId = rule.task,
                    ruleJson = stringifyRule(rule),
                    enabled = rule.enabled,
                    runsTotal = existing?.runsTotal ?: 0,
                    runsToday = existing?.runsToday ?: 0,
                    runsDayKey = existing?.runsDayKey,
                    consecutiveFailures = existing?.consecutiveFailures ?: 0,
                    lastFireAt = existing?.lastFireAt,
                    lastResult = existing?.lastResult,
                    proposedNextFireAt = existing?.proposedNextFireAt,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            data.schedules.recordEvent(rule.id, EVENT_ARMED)
            armNext(rule.id)
        }
    }

    fun disable(scheduleId: String) {
        scope.launch {
            for (backend in backends.values) backend.cancel(fireId(scheduleId))
            data.schedules.byScheduleId(scheduleId)?.let {
                data.schedules.upsert(
                    it.copy(enabled = false, nextFireAt = null, armedFireId = null, updatedAt = clock.nowMs()),
                )
            }
            data.schedules.recordEvent(scheduleId, EVENT_DISABLED)
        }
    }

    // ------------------------------------------------------------------ fire path

    /**
     * One fire delivered by a backend. Re-arms FIRST (a slow run must never push the next fire
     * later than due, §11.3), then launches the fan-out targets through the run path.
     */
    fun onFire(fireId: String) {
        val scheduleId = scheduleIdOf(fireId)
        scope.launch {
            val state = data.schedules.byScheduleId(scheduleId) ?: return@launch
            val rule = parseRule(state.ruleJson) ?: return@launch
            armNext(scheduleId)
            for (target in engine.fanOut(rule, clock.nowMs())) {
                fireTarget(rule, state, target.profileId, target.dueAtMs)
            }
        }
    }

    private suspend fun fireTarget(rule: ScheduleRule, state: ScheduleStateEntity, profileId: String?, dueAtMs: Long) {
        val result = fireAction(rule.task, profileId, dueAtMs, TRIGGER_SCHEDULE)
        val runId = (result as? AhResult.Ok)?.value
        val outcome = if (runId != null) "STARTED" else "REFUSED:${(result as? AhResult.Err)?.error?.reason ?: "unknown"}"
        val dayKey = engine.dayKeyFor(rule)
        val runsToday = if (state.runsDayKey == dayKey) state.runsToday + 1 else 1
        data.schedules.recordFire(
            scheduleId = state.scheduleId,
            runsToday = runsToday,
            dayKey = dayKey,
            result = outcome,
            consecutiveFailures = state.consecutiveFailures, // run outcome feedback lands via onRunFinished
        )
        data.schedules.recordEvent(
            state.scheduleId, EVENT_FIRED,
            AhJson.meta { put("profile", profileId ?: ""); put("outcome", outcome); put("run_id", runId ?: "") },
        )
    }

    /** Run-terminal feedback: failures advance stop_after_consecutive_failures (P4-02). */
    fun onRunFinished(taskId: String, statusName: String) {
        scope.launch {
            val state = data.schedules.ofTask(taskId).firstOrNull { it.enabled } ?: return@launch
            val rule = parseRule(state.ruleJson) ?: return@launch
            val failed = statusName == "FAILED" || statusName == "INTERRUPTED"
            val next = if (failed) state.consecutiveFailures + 1 else 0
            data.schedules.upsert(state.copy(consecutiveFailures = next, updatedAt = clock.nowMs()))
            val cap = rule.limits.stopAfterConsecutiveFailures
            if (failed && cap != null && next >= cap) {
                data.schedules.recordEvent(state.scheduleId, EVENT_LIMIT_HIT, AhJson.meta { put("consecutive_failures", next) })
            }
        }
    }

    // ------------------------------------------------------------------ reconcile (§11.3)

    /** One reconcile pass over every enabled schedule; records RECONCILED events. */
    fun reconcile(why: String) {
        scope.launch {
            val now = clock.nowMs()
            for (state in data.schedules.enabledSchedules()) {
                val rule = parseRule(state.ruleJson) ?: continue
                val backend = backendFor(state.backend)
                val decision = ReconcilePolicy.decide(
                    rule, engine, state.toEngineState(),
                    ReconcileInput(
                        nowMs = now,
                        armedFireId = state.armedFireId,
                        armedAtMs = state.nextFireAt,
                        nextFireAtMs = state.nextFireAt,
                        exactAlarmsAllowed = backend.canDeliverExact(),
                        backendKind = state.backend ?: backend.kind,
                    ),
                )
                when (decision) {
                    ReconcileDecision.Healthy -> Unit
                    ReconcileDecision.Rearm -> armNext(state.scheduleId)
                    ReconcileDecision.Finished -> data.schedules.recordEvent(state.scheduleId, EVENT_FINISHED)
                    is ReconcileDecision.Missed -> {
                        data.schedules.recordEvent(
                            state.scheduleId, EVENT_RECONCILED,
                            AhJson.meta {
                                put("why", why)
                                put("policy", decision.policyName)
                                put("missed", decision.fires.size)
                            },
                        )
                        for (fire in decision.fires) {
                            for (target in engine.fanOut(rule, clock.nowMs())) {
                                fireTarget(rule, state, target.profileId, fire)
                            }
                        }
                        armNext(state.scheduleId)
                    }
                }
            }
            AhLog.i(TAG, "reconcile pass done", AhJson.meta { put("why", why) })
        }
    }

    // ------------------------------------------------------------------ arming

    /** Compute the next fire (script proposal wins when earlier, §11.7) and arm exactly one fire. */
    private suspend fun armNext(scheduleId: String) {
        val state = data.schedules.byScheduleId(scheduleId) ?: return
        val rule = parseRule(state.ruleJson) ?: return
        if (!rule.enabled || !state.enabled) return
        val now = clock.nowMs()
        val regular = engine.nextFire(rule, now, state.toEngineState())
        val proposal = state.proposedNextFireAt?.takeIf { it > now }
        val next = listOfNotNull(regular, proposal).minOrNull()
        if (next == null) {
            data.schedules.updateArming(scheduleId, null, null, state.backend, lateTiming = false)
            return
        }
        val arm = armWithFallback(state.backend, fireId(scheduleId), next, now)
        data.schedules.updateArming(
            scheduleId, next,
            if (arm.first is SchedulerBackend.ArmResult.Armed) fireId(scheduleId) else null,
            arm.second,
            lateTiming = arm.second != BACKEND_ALARM,
        )
        data.schedules.recordEvent(
            scheduleId, EVENT_ARMED,
            buildJsonObject {
                put("backend", arm.second)
                put("next", next)
                put("proposed", proposal != null && proposal == next)
                val refused = arm.first as? SchedulerBackend.ArmResult.Refused
                if (refused != null) put("refused", refused.reason)
            },
        )
    }

    /** `auto` degrades Alarm → Work when exact alarms are refused (§11.3 + G4-e badge). */
    private fun armWithFallback(
        preferred: String?,
        fireId: String,
        next: Long,
        now: Long,
    ): Pair<SchedulerBackend.ArmResult, String> {
        val primary = backendFor(preferred)
        val result = primary.arm(fireId, next, allowWhileIdle = primary.canDeliverExact())
        if (result is SchedulerBackend.ArmResult.Armed) return result to primary.kind
        if (primary.kind != BACKEND_ALARM) return result to primary.kind
        val work = backends[BACKEND_WORK] ?: return result to primary.kind
        val fallback = work.arm(fireId, next, allowWhileIdle = false)
        return fallback to if (fallback is SchedulerBackend.ArmResult.Armed) work.kind else primary.kind
    }

    // ------------------------------------------------------------------ ctx.next_run (P4-11)

    /**
     * A script proposed its next run (plan §11.7). Clamp: at least [MIN_PROPOSAL_MS] ahead and,
     * when the rule declares a conditions window, inside it. Stored as `proposedNextFireAt`,
     * labelled "script-proposed" in the schedule event and UI.
     */
    fun proposeNextRun(runId: String, payload: JsonObject) {
        scope.launch {
            val record = runStore.get(runId) ?: return@launch
            val state = data.schedules.ofTask(record.taskId).firstOrNull { it.enabled } ?: return@launch
            val rule = parseRule(state.ruleJson) ?: return@launch
            val minIntervalMs = (data.tasks.latestVersion(record.taskId)?.let { version ->
                runCatching {
                    AhJson.json.decodeFromString(
                        app.autohost.core.model.TaskManifest.serializer(),
                        version.manifestJson,
                    )
                }.getOrNull()?.execution?.minIntervalMinutes
            })?.let { it * 60_000L } ?: MIN_PROPOSAL_MS
            val now = clock.nowMs()
            val afterMinutes = payload["after_minutes"]?.jsonPrimitive?.longOrNull
            val atIso = payload["at"]?.jsonPrimitive?.content
            val proposed = when {
                afterMinutes != null -> now + afterMinutes * 60_000
                atIso != null -> runCatching { parseIsoToMs(atIso) }.getOrNull()
                else -> null
            } ?: return@launch
            val clamped = clampProposal(proposed, now, rule, minIntervalMs)
            data.schedules.setProposedNextFire(state.scheduleId, clamped)
            data.schedules.recordEvent(
                state.scheduleId, EVENT_PROPOSED,
                buildJsonObject {
                    put("run_id", runId)
                    put("proposed", clamped)
                    put("clamped", clamped != proposed)
                },
            )
            armNext(state.scheduleId)
        }
    }

    private fun clampProposal(proposedMs: Long, nowMs: Long, rule: ScheduleRule, minIntervalMs: Long): Long {
        var candidate = proposedMs.coerceAtLeast(nowMs + minIntervalMs)
        val window = rule.conditions.window
        if (window != null && isHhMm(window.start) && isHhMm(window.end)) {
            val zone = ScheduleEngine.zoneOf(window.timezone)
            val local = java.time.Instant.ofEpochMilli(candidate).atZone(zone)
            val minuteOfDay = local.hour * 60 + local.minute
            val startMinute = toMinutes(window.start)
            val endMinute = toMinutes(window.end)
            if (minuteOfDay < startMinute || minuteOfDay >= endMinute) {
                // push to the next window start (§11.7 clamps to the declared window)
                candidate = local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() +
                    startMinute * 60_000L +
                    if (minuteOfDay >= endMinute) 86_400_000L else 0L
            }
        }
        return candidate
    }

    // ------------------------------------------------------------------ helpers

    private fun parseRule(json: String): ScheduleRule? = ScheduleRule.fromJson(json)

    private fun ScheduleStateEntity.toEngineState() = ScheduleState(
        runsTotal = runsTotal,
        runsToday = runsToday,
        dayKey = runsDayKey,
        consecutiveFailures = consecutiveFailures,
        lastRunEndMs = lastFireAt,
    )

    private fun fireId(scheduleId: String): String = FIRE_PREFIX + scheduleId

    companion object {
        private const val TAG = "scheduler"
        const val BACKEND_ALARM = "alarm"
        const val BACKEND_WORK = "work"
        const val BACKEND_JOB = "job"
        const val FIRE_PREFIX = "fire-"
        const val TRIGGER_SCHEDULE = "schedule"
        const val MIN_PROPOSAL_MS = 60_000L

        const val EVENT_ARMED = "ARMED"
        const val EVENT_FIRED = "FIRED"
        const val EVENT_RECONCILED = "RECONCILED"
        const val EVENT_PROPOSED = "PROPOSED"
        const val EVENT_DISABLED = "DISABLED"
        const val EVENT_FINISHED = "FINISHED"

        fun scheduleIdOf(fireId: String): String = fireId.removePrefix(FIRE_PREFIX)
    }
}

/** Renders a rule through the shared AhJson configuration (single JSON dialect repo-wide). */
fun stringifyRule(rule: ScheduleRule): String =
    AhJson.json.encodeToString(ScheduleRule.serializer(), rule)
