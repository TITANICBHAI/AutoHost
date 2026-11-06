package app.autohost.runtime.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P13-15 (AGENT_RUNTIME §11/§13): the event log — the §11 table, the 80% crossings, the drop counters. */
class AgentEventLogTest {

    private val budgets = RunBudget.Limits(
        maxTurns = 10, wallClockMs = 600_000, maxModelCalls = 10,
        tokenBudget = 1_000, maxActions = 4, maxOutputTokens = 100,
    )

    private fun log(
        events: MutableList<JsonObject>,
        redact: (String) -> String = { it },
        keepTranscript: Boolean = false,
        traceCap: Int = 500,
    ) = AgentEventLog(
        goal = "open settings",
        packages = listOf("com.android.settings"),
        providerProfileId = "profile-1",
        budgets = budgets,
        emit = { events.add(it) },
        redact = redact,
        keepTranscript = keepTranscript,
        traceCap = traceCap,
    )

    private fun kindOf(event: JsonObject): String? =
        ((event["data"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.content

    private fun typeOf(event: JsonObject): String? = (event["type"] as? JsonPrimitive)?.content

    @Test
    fun `stage events map to stage_begin and stage_end with the stage name`() {
        val events = mutableListOf<JsonObject>()
        val l = log(events)
        l.on("stage_begin", buildJsonObject { put("stage", "observe") })
        l.on("stage_end", buildJsonObject { put("stage", "observe") })
        assertEquals(listOf("stage_begin", "stage_end"), events.map { typeOf(it) })
        assertEquals("observe", ((events[0]["stage"]) as JsonPrimitive).content)
    }

    @Test
    fun `progress maps to the turn-over-max progress float`() {
        val events = mutableListOf<JsonObject>()
        val l = log(events)
        l.on("progress", buildJsonObject { put("turn", 3); put("generation", 3) })
        val progress = events.first { typeOf(it) == "progress" }
        val data = progress["data"] as JsonObject
        assertEquals(0.3f, (data["progress"] as JsonPrimitive).content.toFloat(), 1e-6f)
        assertEquals(10, (data["max"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `thought and action texts are REDACTED before the event leaves the runtime`() {
        val events = mutableListOf<JsonObject>()
        val l = log(events, redact = { it.replace("hunter2", "[redacted]") })
        l.on("agent.thought", buildJsonObject { put("text", "password is hunter2") })
        l.on("agent.action", buildJsonObject { put("tool", "ui_set_text"); put("args", """{"text":"hunter2"}""") })
        val thought = events.first { kindOf(it) == "agent.thought" }
        val action = events.first { kindOf(it) == "agent.action" }
        assertTrue(!((thought["data"] as JsonObject)["text"] as JsonPrimitive).content.contains("hunter2"))
        assertTrue(!((action["data"] as JsonObject)["args"] as JsonPrimitive).content.contains("hunter2"))
    }

    @Test
    fun `usage events accumulate and the 80 percent token crossing fires exactly once`() {
        val events = mutableListOf<JsonObject>()
        val l = log(events)
        repeat(3) { l.on("agent.usage", usage(300)) } // 900 of 1000 → crosses at 800
        val crossings = events.filter { kindOf(it) == "agent.budget" }
        assertEquals(1, crossings.size)
        assertEquals("tokens", ((crossings[0]["data"] as JsonObject)["budget"] as JsonPrimitive).content)
        // a fourth usage event does not re-warn
        l.on("agent.usage", usage(300))
        assertEquals(1, events.filter { kindOf(it) == "agent.budget" }.size)
    }

    @Test
    fun `the 80 percent crossings latch per budget (turns, model calls, actions)`() {
        val events = mutableListOf<JsonObject>()
        val l = log(events)
        repeat(9) { l.on("progress", buildJsonObject { put("turn", it + 1) }) } // 9/10
        repeat(9) { l.on("agent.usage", usage(1)) } // 9 model calls of 10
        repeat(4) { l.on("agent.result", buildJsonObject { put("tool", "ui_click"); put("ok", true) }) } // 4/4 actions
        val names = events.filter { kindOf(it) == "agent.budget" }
            .map { ((it["data"] as JsonObject)["budget"] as JsonPrimitive).content }
        assertEquals(setOf("turns", "model_calls", "actions"), names.toSet())
    }

    @Test
    fun `the stop-path budget event rides at warn level`() {
        val events = mutableListOf<JsonObject>()
        val l = log(events)
        l.on("agent.budget", buildJsonObject { put("budget", "BUDGET_TURNS") })
        val budget = events.first { kindOf(it) == "agent.budget" }
        assertEquals("warn", (budget["level"] as JsonPrimitive).content)
    }

    @Test
    fun `trace overflow drops the OLDEST entries and the drop counter is VISIBLE and cumulative`() {
        val events = mutableListOf<JsonObject>()
        val l = log(events, traceCap = 3)
        repeat(6) { l.on("agent.action", buildJsonObject { put("tool", "t$it"); put("args", "{}") }) }
        val drops = events.filter { kindOf(it) == "agent.dropped" }
        assertEquals(3, drops.size) // 6 events into a 3-cap trace → 3 evictions, each visible
        val counters = drops.map { ((it["data"] as JsonObject)["dropped"] as JsonPrimitive).content.toInt() }
        assertEquals(listOf(1, 2, 3), counters) // cumulative, never reset
        assertEquals("warn", (drops[0]["level"] as JsonPrimitive).content)
    }

    @Test
    fun `agentSection carries goal, budgets, usage counters and the drop counter — never a key`() {
        val events = mutableListOf<JsonObject>()
        val l = log(events, traceCap = 1) // small cap so the log events overflow visibly
        l.on("progress", buildJsonObject { put("turn", 2) })
        l.on("agent.usage", usage(100))
        l.on("agent.result", buildJsonObject { put("tool", "ui_click"); put("ok", true) })
        l.on("agent.action", buildJsonObject { put("tool", "t"); put("args", "{}") }) // overflows the 1-cap trace
        l.stop = AgentEventLog.StopInfo("FAILED", "AGENT_STUCK", "stuck")
        val section = l.agentSection()
        assertEquals("open settings", (section["goal"] as JsonPrimitive).content)
        assertEquals("profile-1", (section["provider_profile"] as JsonPrimitive).content)
        val usage = section["usage"] as JsonObject
        assertEquals(2, (usage["turns"] as JsonPrimitive).content.toInt())
        assertEquals(1, (usage["model_calls"] as JsonPrimitive).content.toInt())
        assertEquals(1, (usage["actions"] as JsonPrimitive).content.toInt())
        assertEquals(100L, (usage["tokens"] as JsonPrimitive).content.toLong())
        val trace = section["trace"] as JsonObject
        assertTrue((trace["dropped"] as JsonPrimitive).content.toInt() >= 1)
        assertEquals(false, (trace["keep_transcript"] as JsonPrimitive).content)
        val stop = section["stop"] as JsonObject
        assertEquals("AGENT_STUCK", (stop["reason"] as JsonPrimitive).content)
        assertTrue(!section.toString().contains("sk-") ) // no key-shaped material
    }

    @Test
    fun `the transcript is included ONLY with keep_transcript (the §13 explicit choice)`() {
        val events = mutableListOf<JsonObject>()
        val on = log(events, keepTranscript = true)
        on.on("agent.action", buildJsonObject { put("tool", "t"); put("args", "{}") })
        assertTrue((on.agentSection()["transcript"] as? kotlinx.serialization.json.JsonArray) != null)

        val events2 = mutableListOf<JsonObject>()
        val off = log(events2, keepTranscript = false)
        off.on("agent.action", buildJsonObject { put("tool", "t"); put("args", "{}") })
        assertTrue(off.agentSection()["transcript"] == null)
    }

    @Test
    fun `given the verifier evidence event when sunk then it passes through AS an evidence event (never a log line)`() {
        val events = mutableListOf<JsonObject>()
        val l = log(events)
        val shaped = buildJsonObject {
            put("type", "evidence")
            put("data", buildJsonObject { put("path", "/runs/r/evidence/final.json"); put("kind", "ui_snapshot") })
        }
        l.on("evidence_event", shaped)
        val evidence = events.single { (it["type"] as JsonPrimitive).content == "evidence" }
        val data = evidence["data"] as JsonObject
        assertEquals("/runs/r/evidence/final.json", (data["path"] as JsonPrimitive).content)
        assertEquals("ui_snapshot", (data["kind"] as JsonPrimitive).content)
        assertTrue("not double-wrapped into a log line", events.none { kindOf(it) == "evidence_event" })
    }

    private fun usage(total: Int) = buildJsonObject {
        put("prompt_tokens", total * 2 / 3)
        put("completion_tokens", total - total * 2 / 3)
        put("estimated", false)
        put("latency_ms", 5L)
    }
}
