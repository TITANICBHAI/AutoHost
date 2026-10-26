package app.autohost.schedule

import android.app.Application
import app.autohost.AutoHostApp
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import app.autohost.core.engine.events.EventHub
import app.autohost.core.engine.events.EventSourceCatalog
import app.autohost.core.engine.events.TriggerEvent
import app.autohost.core.engine.schedule.ScheduleEngine
import app.autohost.core.engine.schedule.SchedulerBackend
import app.autohost.core.engine.triggers.BusyGate
import app.autohost.core.engine.triggers.EventMatcher
import app.autohost.core.engine.triggers.TriggerDecisionEngine
import app.autohost.core.model.TriggerRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-03: the trigger-rules runtime (plan §11.9) — the object that makes trigger rules LIVE.
 * Phase 4 stored and validated them; this controller arms and fires them:
 *
 *  - **event sources**: subscribes the [EventHub]; a matching event passes the grant gate (the
 *    target task must hold the source's required capability grant, §11.8 privacy), then the guard
 *    chain `max_launch_count → cooldown_seconds → debounce_ms` ([TriggerDecisionEngine]), then
 *    the busy gate (`queue | drop | replace`, [BusyGate]) against the task's active runs.
 *  - **time sources**: armed through the shared scheduler backends exactly like schedules
 *    (fireId `trig-<ruleId>`, routed by AlarmReceiver / the Work/Job backends to [onTimeFire]).
 *  - **external**: [externalFire] by name — the token-guarded RUN_TASK intent (P7-10) and the
 *    Dev API `triggers.test` call in here; webhooks arrive with part 2.
 *  - **task.chain**: [onRunFinished] matches upstream results (succeeded/failed/any).
 *
 * State: the DB keeps lastFireAt/launchCount (durable, §11.9 status); this object keeps the
 * debounce timers and the bounded busy queues (in-memory — a process restart drops queued events,
 * which the Triggers UI says honestly). Fired runs carry the `event` trigger type with
 * `{rule_id, source, ...event payload}` so flows read `${trigger.*}` and scripts read
 * ctx.trigger.payload.
 */
class TriggerController(
    private val app: Application,
    private val clock: Clock,
    private val engine: ScheduleEngine,
    private val backends: Map<String, SchedulerBackend>,
    private val scope: CoroutineScope,
    private val hub: EventHub,
    private val fireAction: suspend (taskId: String, profileId: String?, triggerType: String, payload: JsonObject) -> String?,
) {

    private val data get() = (app as AutoHostApp).graph.data
    private val runStore get() = (app as AutoHostApp).graph.runStore
    private val eventHubController get() = (app as AutoHostApp).graph.eventHub

    private class Entry(
        val entity: app.autohost.core.data.db.TriggerRuleEntity,
        val rule: TriggerRule,
        val requiredGrant: String?,
    )

    /** In-memory guard state per rule; durable counters start from the DB row. */
    private class RuleState(
        var lastFireAtMs: Long? = null,
        var launchCount: Int = 0,
        var grants: Set<String> = emptySet(),
    ) {
        var debouncePayload: JsonObject? = null
        var debounceJob: Job? = null
        val queue = ArrayDeque<JsonObject>()
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val states = java.util.concurrent.ConcurrentHashMap<String, RuleState>()
    private var subscription: EventHub.Subscription? = null

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (subscription == null) {
            subscription = hub.subscribe { event -> onHubEvent(event) }
        }
        reconcile("start")
    }

    /** Reloads the enabled rules from the DB, re-resolves grant gates and the FGS need. */
    fun refresh() {
        scope.launch {
            val rules = data.triggers.enabledRules()
            val fresh = LinkedHashMap<String, Entry>()
            for (entity in rules) {
                val parsed = parseRuleOf(entity) ?: continue
                fresh[entity.ruleId] = Entry(entity, parsed, EventSourceCatalog.byKind(entity.source)?.requiredGrant)
            }
            synchronized(entries) {
                entries.clear()
                entries.putAll(fresh)
            }
            // Grant sets per target task (one query per distinct task; §11.8 delivery gate).
            for (taskId in fresh.values.map { it.entity.taskId }.distinct()) {
                val perms = data.triggers.grantedPermissionsOf(taskId)
                for ((ruleId, entry) in fresh) {
                    if (entry.entity.taskId == taskId) {
                        states.getOrPut(ruleId) { RuleState() }.grants = perms
                    }
                }
            }
            eventHubController.setReceiversNeeded(needsReceivers())
        }
    }

    /** Reconcile pass: re-arm time rules, refresh the cache, fix the FGS need (plan §11.3). */
    fun reconcile(why: String) {
        refresh()
        armTimeRules()
    }

    private fun parseRuleOf(entity: app.autohost.core.data.db.TriggerRuleEntity): TriggerRule? {
        val rule = TriggerRule.fromJson(
            buildJsonObject {
                put("id", entity.ruleId)
                put("task", entity.taskId)
                entity.profileId?.let { put("profile", it) }
                put("source", entity.source)
                put("params", AhJson.json.parseToJsonElement(entity.paramsJson.ifBlank { "{}" }))
                put("cooldown_seconds", entity.cooldownSeconds)
                put("max_launch_count", entity.maxLaunchCount)
                put("debounce_ms", entity.debounceMs)
                put("busy_policy", entity.busyPolicy)
                put("queue_max", entity.queueMax)
                put("enabled", entity.enabled)
            }.toString(),
        ) ?: return null
        return if (rule.validate().isEmpty()) rule else null
    }

    /** True while any enabled rule uses a receiver/network-callback source (§11.8). */
    private fun needsReceivers(): Boolean = synchronized(entries) {
        entries.values.any { entry -> entry.entity.source in EventSourceCatalog.FGS_KINDS }
    }

    // ------------------------------------------------------------------ event path

    private fun onHubEvent(event: TriggerEvent): Boolean {
        val candidates = synchronized(entries) {
            entries.values.filter { it.entity.enabled && it.entity.source == event.kind }
        }
        for (entry in candidates) {
            try {
                deliver(entry, event, synthetic = false)
            } catch (e: RuntimeException) {
                AhLog.e(TAG, "trigger delivery failed", AhJson.meta {
                    put("rule", entry.entity.ruleId); put("error", e.message ?: e.javaClass.simpleName)
                })
            }
        }
        return true
    }

    /**
     * The guard chain for one (rule, event). Debounce holds the latest payload and restarts the
     * window on every matching burst; at the deadline [launchAfterDebounce] re-checks the guards
     * and applies the busy gate.
     */
    private fun deliver(entry: Entry, event: TriggerEvent, synthetic: Boolean) {
        val rule = entry.rule
        val state = states.getOrPut(entry.entity.ruleId) {
            RuleState(lastFireAtMs = entry.entity.lastFireAt, launchCount = entry.entity.launchCount)
        }
        val now = clock.nowMs()

        // Grant gate (§11.8: delivery is scoped to what the grant names).
        if (entry.requiredGrant != null && entry.requiredGrant !in state.grants) {
            recordStatus(entry, "DROPPED_NO_GRANT", now)
            return
        }

        if (!synthetic && rule.debounceMs > 0) {
            when (
                val decision = TriggerDecisionEngine.evaluate(
                    rule, guardState(state), event.combinedPayload(entry), now, debounceElapsed = false,
                )
            ) {
                is TriggerDecisionEngine.Decision.Fire -> Unit // unreachable: debounce > 0 returns Debounce
                is TriggerDecisionEngine.Decision.Cooldown -> {
                    recordStatus(entry, "COOLDOWN", now)
                    return
                }
                TriggerDecisionEngine.Decision.LimitReached -> {
                    recordStatus(entry, "LIMIT", now)
                    return
                }
                is TriggerDecisionEngine.Decision.Debounce -> {
                    synchronized(state) {
                        state.debouncePayload = event.combinedPayload(entry)
                        state.debounceJob?.cancel()
                        state.debounceJob = scope.launch {
                            delay(rule.debounceMs)
                            val held = synchronized(state) {
                                val payload = state.debouncePayload
                                state.debouncePayload = null
                                state.debounceJob = null
                                payload
                            } ?: return@launch
                            val freshEntry = synchronized(entries) { entries[entry.entity.ruleId] } ?: return@launch
                            launchAfterDebounce(freshEntry, held)
                        }
                    }
                    recordStatus(entry, "DEBOUNCING", now)
                    return
                }
            }
        }

        launchAfterDebounce(entry, event.combinedPayload(entry))
    }

    private fun guardState(state: RuleState) = TriggerDecisionEngine.RuleState(
        lastFireAtMs = state.lastFireAtMs,
        launchCount = state.launchCount,
    )

    private fun launchAfterDebounce(entry: Entry, payload: JsonObject) {
        val rule = entry.rule
        val state = states.getOrPut(entry.entity.ruleId) {
            RuleState(lastFireAtMs = entry.entity.lastFireAt, launchCount = entry.entity.launchCount)
        }
        val now = clock.nowMs()

        // Guards, re-checked at the debounce deadline (cooldown may have started meanwhile).
        when (
            val decision = TriggerDecisionEngine.evaluate(rule, guardState(state), payload, now, debounceElapsed = true)
        ) {
            is TriggerDecisionEngine.Decision.Fire -> Unit
            is TriggerDecisionEngine.Decision.Cooldown -> {
                recordStatus(entry, "COOLDOWN", now)
                return
            }
            TriggerDecisionEngine.Decision.LimitReached -> {
                recordStatus(entry, "LIMIT", now)
                return
            }
            is TriggerDecisionEngine.Decision.Debounce -> return
        }

        // Busy gate — what happens when the target already has an active run (§11.9).
        val taskBusy = runStore.runs.value.any { it.taskId == entry.entity.taskId && it.active }
        when (val busy = BusyGate.decide(rule.busyPolicy, taskBusy, state.queue.size, rule.queueMax)) {
            BusyGate.Decision.Fire -> fire(entry, payload)
            BusyGate.Decision.Replace -> {
                val cancelled = (app as AutoHostApp).graph.cancelRunsOfTask(entry.entity.taskId)
                AhLog.i(TAG, "busy replace", AhJson.meta {
                    put("rule", entry.entity.ruleId); put("cancelled", cancelled)
                })
                fire(entry, payload)
            }
            BusyGate.Decision.Drop -> recordStatus(entry, "BUSY_DROPPED", now)
            is BusyGate.Decision.Enqueue -> {
                synchronized(state) {
                    if (busy.droppedOldest > 0 && state.queue.isNotEmpty()) state.queue.removeFirst()
                    state.queue.addLast(payload)
                }
                recordStatus(entry, "QUEUED", now)
            }
        }
    }

    private fun fire(entry: Entry, payload: JsonObject) {
        scope.launch {
            val state = states.getOrPut(entry.entity.ruleId) { RuleState() }
            // Re-check guards with the LIVE counters (a queue drain may race the cooldown window).
            when (
                val decision = TriggerDecisionEngine.evaluate(
                    entry.rule, guardState(state), payload, clock.nowMs(), debounceElapsed = true,
                )
            ) {
                is TriggerDecisionEngine.Decision.Fire -> Unit
                is TriggerDecisionEngine.Decision.Cooldown -> {
                    recordStatus(entry, "COOLDOWN", clock.nowMs())
                    return@launch
                }
                TriggerDecisionEngine.Decision.LimitReached -> {
                    recordStatus(entry, "LIMIT", clock.nowMs())
                    return@launch
                }
                is TriggerDecisionEngine.Decision.Debounce -> return@launch
            }
            val runId = fireAction(
                entry.entity.taskId,
                entry.entity.profileId,
                TRIGGER_EVENT,
                payload,
            )
            val outcome = if (runId != null) "FIRED" else "REFUSED"
            state.lastFireAtMs = clock.nowMs()
            state.launchCount += 1
            data.triggers.touchFired(entry.entity.ruleId, clock.nowMs())
            data.triggers.recordEvent(
                entry.entity.ruleId,
                outcome,
                AhJson.meta { put("run_id", runId ?: "") }.toString(),
            )
            recordStatus(entry, outcome, clock.nowMs())
        }
    }

    /** Drains the busy queue when the task's run finished (queue policy); fires task.chain rules. */
    fun onRunFinished(taskId: String, statusName: String) {
        // task.chain rules observe every terminal result of their upstream task (§11.9).
        val chainEvent = TriggerEvent(
            kind = "task.chain",
            payload = buildJsonObject { put("task", taskId); put("status", statusName) },
            atMs = clock.nowMs(),
        )
        val chainRules = synchronized(entries) {
            entries.values.filter { it.entity.enabled && it.entity.source == "task.chain" }
        }
        for (rule in chainRules) {
            if (EventMatcher.matches(chainEvent, rule.rule)) deliver(rule, chainEvent, synthetic = false)
        }

        // Queue drains: one pending payload per rule, only when the task is free again.
        val queuedRules = synchronized(entries) {
            entries.values.filter { it.entity.taskId == taskId && it.entity.busyPolicy == "queue" }
        }
        for (entry in queuedRules) {
            val state = states[entry.entity.ruleId] ?: continue
            val payload = synchronized(state) {
                if (runStore.runs.value.any { it.taskId == taskId && it.active }) null
                else state.queue.removeFirstOrNull()
            } ?: continue
            fire(entry, payload)
        }
    }

    /** Named external trigger (§11.9 `external`): RUN_TASK intent, Dev API test, webhooks. */
    fun externalFire(name: String, payload: JsonObject): Boolean {
        val event = TriggerEvent(
            kind = "external",
            payload = JsonObject(payload + mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name))),
            atMs = clock.nowMs(),
        )
        val matched = synchronized(entries) {
            entries.values.filter { it.entity.enabled && it.entity.source == "external" }
        }.filter { EventMatcher.matches(event, it.rule) }
        for (entry in matched) deliver(entry, event, synthetic = true)
        return matched.isNotEmpty()
    }

    /** Dev API `triggers.test`: dispatch a synthetic event for one rule through the guard chain. */
    fun testFire(ruleId: String): JsonObject {
        val entry = synchronized(entries) { entries[ruleId] }
            ?: return buildJsonObject { put("fired", false); put("error", "no enabled rule '$ruleId'") }
        deliver(entry, syntheticEventFor(entry.rule), synthetic = true)
        return buildJsonObject {
            put("fired", true)
            put("source", entry.entity.source)
            put("note", "synthetic event dispatched through the normal guard chain")
        }
    }

    /** A representative event for rule testing. */
    private fun syntheticEventFor(rule: TriggerRule): TriggerEvent = TriggerEvent(
        kind = rule.source,
        pkg = rule.params["package"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
        payload = buildJsonObject {
            when (rule.source) {
                "notification.posted" -> { put("title", "trigger test"); put("text", "synthetic event") }
                "toast.shown" -> put("text", "trigger test")
                "battery.level" -> put("level", (rule.params["percent"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 50)
                "network.connected" -> put("type", rule.params["type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "wifi")
                "network.wifi_ssid" -> put("ssid", rule.params["ssid"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "")
                "external" -> put("name", rule.params["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "")
                "task.chain" -> { put("task", rule.params["task"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""); put("status", "SUCCEEDED") }
                else -> { }
            }
        },
        atMs = clock.nowMs(),
    )

    // ------------------------------------------------------------------ time sources (§11.9)

    /** Arms every enabled time.* rule through the shared backends (one fire per rule). */
    fun armTimeRules() {
        scope.launch {
            val rules = data.triggers.enabledRules()
                .filter { it.source in app.autohost.core.model.TriggerSource.TIMED }
            for (entity in rules) {
                val timed = parseTimedRule(entity) ?: continue
                val now = clock.nowMs()
                val next = engine.nextFires(timed.schedule, now, n = 1).firstOrNull()
                if (next == null) {
                    data.triggers.setArmed(entity.ruleId, false, now)
                    continue
                }
                val backend = backends["alarm"] ?: backends.values.firstOrNull() ?: continue
                val result = backend.arm(fireId(entity.ruleId), next, allowWhileIdle = backend.canDeliverExact())
                data.triggers.setArmed(entity.ruleId, result is SchedulerBackend.ArmResult.Armed, now)
            }
        }
    }

    /** One armed fire delivered by a backend (AlarmReceiver routes `trig-` here). */
    fun onTimeFire(fireId: String) {
        val ruleId = fireId.removePrefix(FIRE_PREFIX)
        scope.launch {
            val entity = data.triggers.byRuleId(ruleId) ?: return@launch
            if (!entity.enabled) return@launch
            val timed = parseTimedRule(entity) ?: return@launch
            val entry = Entry(entity, timed.rule, EventSourceCatalog.byKind(entity.source)?.requiredGrant)
            deliver(
                entry,
                TriggerEvent(kind = entity.source, payload = buildJsonObject { put("scheduled", true) }, atMs = clock.nowMs()),
                synthetic = true,
            )
            armTimeRules() // re-arm for the next occurrence (§11.3 one-alarm rule)
        }
    }

    private class TimedRule(val rule: TriggerRule, val schedule: app.autohost.core.model.ScheduleRule)

    private fun parseTimedRule(entity: app.autohost.core.data.db.TriggerRuleEntity): TimedRule? {
        val rule = parseRuleOf(entity) ?: return null
        val schedule = when (entity.source) {
            "time.delay" -> {
                val seconds = (rule.params["seconds"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return null
                app.autohost.core.model.ScheduleRule(
                    id = "trig-${entity.ruleId}", task = entity.taskId, type = "once",
                    at = java.time.Instant.ofEpochMilli(clock.nowMs() + seconds * 1000).toString(),
                    timezone = "UTC",
                )
            }
            else -> rule.toScheduleRule() ?: return null
        }
        return TimedRule(rule, schedule)
    }

    // ------------------------------------------------------------------ status (§11.9 operations)

    /** The `triggers.status` row for one rule: guards, counters, cooldown remaining, busy depth. */
    suspend fun statusOf(entity: app.autohost.core.data.db.TriggerRuleEntity): JsonObject {
        val state = states[entity.ruleId]
        val cooldownRemaining = if (entity.lastFireAt != null && entity.cooldownSeconds > 0) {
            (entity.lastFireAt + entity.cooldownSeconds * 1000 - clock.nowMs()).coerceAtLeast(0)
        } else {
            0L
        }
        val entry = synchronized(entries) { entries[entity.ruleId] }
        val grantOk = entry?.requiredGrant == null ||
            state?.grants?.contains(entry.requiredGrant) == true
        return buildJsonObject {
            put("id", entity.ruleId)
            put("task", entity.taskId)
            put("source", entity.source)
            put("enabled", entity.enabled)
            put("armed", entity.armed)
            put("last_fire_at", entity.lastFireAt)
            put("launch_count", entity.launchCount)
            put("cooldown_remaining_ms", cooldownRemaining)
            put("debouncing", state?.debounceJob != null)
            put("busy_queue_depth", state?.queue?.size ?: 0)
            put("grant_ok", grantOk)
            if (!grantOk) {
                put("note", "target task lacks the '${entry?.requiredGrant}' grant — events are dropped (§11.8)")
            }
        }
    }

    private fun recordStatus(entry: Entry, status: String, at: Long) {
        AhLog.i(TAG, "trigger $status", AhJson.meta {
            put("rule", entry.entity.ruleId)
            put("task", entry.entity.taskId)
            put("source", entry.entity.source)
        })
        scope.launch {
            data.triggers.recordEvent(entry.entity.ruleId, status)
        }
    }

    private fun TriggerEvent.combinedPayload(entry: Entry): JsonObject = JsonObject(
        payload + mapOf(
            "rule_id" to kotlinx.serialization.json.JsonPrimitive(entry.entity.ruleId),
            "source" to kotlinx.serialization.json.JsonPrimitive(entry.entity.source),
        ),
    )

    companion object {
        private const val TAG = "triggers"
        const val FIRE_PREFIX = "trig-"
        const val TRIGGER_EVENT = "event"

        fun fireId(ruleId: String): String = FIRE_PREFIX + ruleId
    }
}
