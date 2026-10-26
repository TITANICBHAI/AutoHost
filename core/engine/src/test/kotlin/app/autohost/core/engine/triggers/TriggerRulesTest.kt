package app.autohost.core.engine.triggers

import app.autohost.core.common.FakeClock
import app.autohost.core.engine.events.TriggerEvent
import app.autohost.core.model.TriggerRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** P8-03: source/param matching (Gate 8-e groundwork). */
class EventMatcherTest {

    private fun rule(source: String, params: String = "{}"): TriggerRule =
        TriggerRule(task = "t", source = source, params = JsonObject(emptyMap()))
            .let { base ->
                if (params == "{}") {
                    base
                } else {
                    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(params)
                    base.copy(params = parsed as JsonObject)
                }
            }

    private fun event(kind: String, pkg: String? = null, payload: JsonObject = JsonObject(emptyMap())) =
        TriggerEvent(kind, pkg = pkg, payload = payload, atMs = 1)

    @Test
    fun `kind must match`() {
        assertEquals(false, EventMatcher.matches(event("screen.off"), rule("screen.on")))
        assertEquals(true, EventMatcher.matches(event("screen.on"), rule("screen.on")))
    }

    @Test
    fun `notification rules match package and regexes`() {
        val r = rule(
            "notification.posted",
            """{"package":"com.mail","title_regex":"(?i)invoice","text_regex":"due"}""",
        )
        assertEquals(
            true,
            EventMatcher.matches(
                event("notification.posted", pkg = "com.mail", payload = buildJsonObject {
                    put("title", "Your INVOICE"); put("text", "payment due tomorrow")
                }),
                r,
            ),
        )
        assertEquals(
            false,
            EventMatcher.matches(
                event("notification.posted", pkg = "com.other", payload = buildJsonObject {
                    put("title", "invoice"); put("text", "due")
                }),
                r,
            ),
        )
        assertEquals(
            false,
            EventMatcher.matches(
                event("notification.posted", pkg = "com.mail", payload = buildJsonObject {
                    put("title", "invoice"); put("text", "paid already")
                }),
                r,
            ),
        )
    }

    @Test
    fun `invalid regex never matches and never throws`() {
        val r = rule("notification.posted", """{"title_regex":"(["}""")
        assertEquals(
            false,
            EventMatcher.matches(
                event("notification.posted", payload = buildJsonObject { put("title", "x") }),
                r,
            ),
        )
    }

    @Test
    fun `app transitions compare the package exactly`() {
        assertEquals(true, EventMatcher.matches(event("app.entered", pkg = "com.a"), rule("app.entered", """{"package":"com.a"}""")))
        assertEquals(false, EventMatcher.matches(event("app.entered", pkg = "com.b"), rule("app.entered", """{"package":"com.a"}""")))
    }

    @Test
    fun `battery level compares with the declared op`() {
        val r = rule("battery.level", """{"op":"lte","percent":15}""")
        assertEquals(true, EventMatcher.matches(event("battery.level", payload = buildJsonObject { put("level", 14) }), r))
        assertEquals(false, EventMatcher.matches(event("battery.level", payload = buildJsonObject { put("level", 80) }), r))
    }

    @Test
    fun `network type filter is optional`() {
        val r = rule("network.connected", """{"type":"wifi"}""")
        assertEquals(true, EventMatcher.matches(event("network.connected", payload = buildJsonObject { put("type", "wifi") }), r))
        assertEquals(false, EventMatcher.matches(event("network.connected", payload = buildJsonObject { put("type", "cellular") }), r))
        assertEquals(true, EventMatcher.matches(event("network.connected"), rule("network.connected")))
    }

    @Test
    fun `external matches by name and task chain by task and status`() {
        assertEquals(
            true,
            EventMatcher.matches(event("external", payload = buildJsonObject { put("name", "deploy") }), rule("external", """{"name":"deploy"}""")),
        )
        assertEquals(
            false,
            EventMatcher.matches(event("external", payload = buildJsonObject { put("name", "other") }), rule("external", """{"name":"deploy"}""")),
        )
        val chain = rule("task.chain", """{"task":"upstream","on":"failed"}""")
        assertEquals(
            true,
            EventMatcher.matches(event("task.chain", payload = buildJsonObject { put("task", "upstream"); put("status", "FAILED") }), chain),
        )
        assertEquals(
            false,
            EventMatcher.matches(event("task.chain", payload = buildJsonObject { put("task", "upstream"); put("status", "SUCCEEDED") }), chain),
        )
    }
}

/** P8-03: cooldown / debounce / lifetime caps (Gate 8-e). */
class TriggerDecisionEngineTest {

    private val clock = FakeClock()

    private fun rule(
        cooldownSeconds: Long = 0,
        maxLaunchCount: Int? = null,
        debounceMs: Long = 0,
    ) = TriggerRule(
        task = "t", source = "screen.on",
        cooldownSeconds = cooldownSeconds, maxLaunchCount = maxLaunchCount, debounceMs = debounceMs,
    )

    @Test
    fun `no guards means fire immediately`() {
        assertEquals(
            TriggerDecisionEngine.Decision.Fire(JsonObject(emptyMap())),
            TriggerDecisionEngine.evaluate(rule(), TriggerDecisionEngine.RuleState(), JsonObject(emptyMap()), clock.nowMs()),
        )
    }

    @Test
    fun `cooldown drops events inside the window and fires after`() {
        val r = rule(cooldownSeconds = 60)
        val payload = buildJsonObject { put("x", 1) }
        val first = TriggerDecisionEngine.evaluate(r, TriggerDecisionEngine.RuleState(), payload, clock.nowMs())
        assertEquals(TriggerDecisionEngine.Decision.Fire(payload), first)
        val busy = TriggerDecisionEngine.evaluate(
            r, TriggerDecisionEngine.RuleState(lastFireAtMs = clock.nowMs()), payload, clock.nowMs() + 30_000,
        )
        assertEquals(TriggerDecisionEngine.Decision.Cooldown(30_000), busy)
        val free = TriggerDecisionEngine.evaluate(
            r, TriggerDecisionEngine.RuleState(lastFireAtMs = clock.nowMs()), payload, clock.nowMs() + 60_000,
        )
        assertEquals(TriggerDecisionEngine.Decision.Fire(payload), free)
    }

    @Test
    fun `lifetime cap stops launches`() {
        val r = rule(maxLaunchCount = 3)
        assertEquals(
            TriggerDecisionEngine.Decision.LimitReached,
            TriggerDecisionEngine.evaluate(r, TriggerDecisionEngine.RuleState(launchCount = 3), JsonObject(emptyMap()), clock.nowMs()),
        )
        assertEquals(
            TriggerDecisionEngine.Decision.Fire(JsonObject(emptyMap())),
            TriggerDecisionEngine.evaluate(r, TriggerDecisionEngine.RuleState(launchCount = 2), JsonObject(emptyMap()), clock.nowMs()),
        )
    }

    @Test
    fun `debounce holds until the window elapses`() {
        val r = rule(debounceMs = 500)
        assertEquals(
            TriggerDecisionEngine.Decision.Debounce(500),
            TriggerDecisionEngine.evaluate(r, TriggerDecisionEngine.RuleState(), JsonObject(emptyMap()), clock.nowMs()),
        )
        assertEquals(
            TriggerDecisionEngine.Decision.Fire(JsonObject(emptyMap())),
            TriggerDecisionEngine.evaluate(r, TriggerDecisionEngine.RuleState(), JsonObject(emptyMap()), clock.nowMs(), debounceElapsed = true),
        )
    }

    @Test
    fun `cooldown is evaluated before debounce`() {
        val r = rule(cooldownSeconds = 60, debounceMs = 500)
        assertEquals(
            TriggerDecisionEngine.Decision.Cooldown(59_000),
            TriggerDecisionEngine.evaluate(
                r, TriggerDecisionEngine.RuleState(lastFireAtMs = clock.nowMs()), JsonObject(emptyMap()), clock.nowMs() + 1_000,
            ),
        )
    }
}

/** P8-03: busy policy — queue (bounded, oldest dropped first), drop, replace (Gate 8-e). */
class BusyGateTest {

    @Test
    fun `free task always fires`() {
        assertEquals(BusyGate.Decision.Fire, BusyGate.decide("drop", taskBusy = false, queueDepth = 0, queueMax = 2))
        assertEquals(BusyGate.Decision.Fire, BusyGate.decide("queue", taskBusy = false, queueDepth = 9, queueMax = 2))
    }

    @Test
    fun `drop policy drops while busy`() {
        assertEquals(BusyGate.Decision.Drop, BusyGate.decide("drop", taskBusy = true, queueDepth = 0, queueMax = 2))
    }

    @Test
    fun `replace policy replaces while busy`() {
        assertEquals(BusyGate.Decision.Replace, BusyGate.decide("replace", taskBusy = true, queueDepth = 0, queueMax = 2))
    }

    @Test
    fun `queue accepts until full then drops the oldest`() {
        assertEquals(BusyGate.Decision.Enqueue(droppedOldest = 0), BusyGate.decide("queue", taskBusy = true, queueDepth = 1, queueMax = 2))
        assertEquals(BusyGate.Decision.Enqueue(droppedOldest = 1), BusyGate.decide("queue", taskBusy = true, queueDepth = 2, queueMax = 2))
    }
}
