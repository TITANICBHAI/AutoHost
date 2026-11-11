package app.autohost.runtime.agent

import app.autohost.core.model.BrokerEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P14-05 (S31, with P13-15): the agent section rebuilt from PERSISTED events for the export. */
class AgentSectionBuilderTest {

    private val redact: (String) -> String = { it.replace("sk-canary", "[redacted]") }

    private fun log(kind: String, vararg pairs: Pair<String, Any>) = BrokerEvent(
        type = "log",
        ts = 1L,
        data = buildJsonObject {
            put("kind", kind)
            pairs.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Boolean -> put(key, value)
                    is Number -> put(key, value)
                    else -> error("unsupported test value for $key: ${value::class}")
                }
            }
        },
    )

    @Test
    fun `given agent events when built then the counters mirror the live AgentEventLog`() {
        val events = listOf(
            BrokerEvent(type = "progress", ts = 1L, data = buildJsonObject { put("turn", 2) }),
            log("agent.usage", "prompt_tokens" to 100, "completion_tokens" to 50, "estimated" to false),
            log("agent.result", "tool" to "ui_click", "ok" to true),
            BrokerEvent(
                type = "finished", ts = 1L,
                data = buildJsonObject { put("status", "FAILED"); put("reason", "AGENT_STUCK"); put("message", "stuck") },
            ),
        )
        val section = AgentSectionBuilder.fromEvents(events, redact)!!
        val usage = section["usage"] as kotlinx.serialization.json.JsonObject
        assertEquals(2, (usage["turns"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals(1, (usage["model_calls"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals(1, (usage["actions"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals(150L, (usage["tokens"] as kotlinx.serialization.json.JsonPrimitive).content.toLong())
        val stop = section["stop"] as kotlinx.serialization.json.JsonObject
        assertEquals("AGENT_STUCK", (stop["reason"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `given keep_transcript off when built then the section carries NO transcript (the §13 choice rides the export)`() {
        val events = listOf(log("agent.thought", "text" to "secret sk-canary plan"))
        val section = AgentSectionBuilder.fromEvents(events, redact)!!
        assertTrue(section["transcript"] == null)
    }

    @Test
    fun `given keep_transcript on when built then the transcript is present AND redacted like the bundle`() {
        val events = listOf(
            log("agent.thought", "text" to "the canary is sk-canary"),
            log("agent.action", "tool" to "ui_set_text", "args" to """{"text":"sk-canary"}"""),
        )
        val section = AgentSectionBuilder.fromEvents(events, redact, keepTranscript = true)!!
        val transcript = section["transcript"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, transcript.size)
        val asText = transcript.toString()
        assertTrue(!asText.contains("sk-canary"))
        assertTrue(asText.contains("[redacted]"))
    }

    @Test
    fun `given a non-agent run when built then null (no agent json in the bundle)`() {
        val events = listOf(BrokerEvent(type = "heartbeat", ts = 1L), BrokerEvent(type = "stage_begin", ts = 1L, stage = "setup"))
        assertNull(AgentSectionBuilder.fromEvents(events, redact))
    }
}
