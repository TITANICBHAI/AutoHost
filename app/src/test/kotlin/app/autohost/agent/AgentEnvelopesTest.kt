package app.autohost.agent

import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import app.autohost.runtime.agent.AppSensitivity
import app.autohost.runtime.agent.ObservationFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INT-A3: what the agent runtime reads off a broker call. The loop counts `ok`, the pipeline reads `status` and
 * `answered` off the top level, and the observation filter reads `package` and `nodes` there: one shape for all three.
 */
class AgentEnvelopesTest {

    private fun text(result: JsonObject, key: String): String? = (result[key] as? JsonPrimitive)?.content

    @Test fun `a call that worked carries ok true beside the provider's own fields`() {
        val result = AgentEnvelopes.toolResult(
            Envelope.ok(buildJsonObject { put("run_id", "r1"); put("status", "RUNNING") }),
        )
        assertEquals("true", text(result, "ok"))
        assertEquals("r1", text(result, "run_id"))
        assertEquals("RUNNING", text(result, "status"))
    }

    @Test fun `a provider that said ok false keeps its word`() {
        val result = AgentEnvelopes.toolResult(Envelope.ok(buildJsonObject { put("ok", false); put("was_visible", true) }))
        assertEquals("false", text(result, "ok"))
        assertEquals("true", text(result, "was_visible"))
    }

    @Test fun `a provider that said ok true is passed through as it is`() {
        val provider = buildJsonObject { put("ok", true); put("performed", true) }
        assertEquals(provider, AgentEnvelopes.toolResult(Envelope.ok(provider)))
    }

    @Test fun `a call that worked with nothing to say is ok true and nothing else`() {
        val result = AgentEnvelopes.toolResult(Envelope.ok(JsonObject(emptyMap())))
        assertEquals(buildJsonObject { put("ok", true) }, result)
    }

    @Test fun `a result that is not an object is handed over as value`() {
        val result = AgentEnvelopes.toolResult(Envelope.ok(JsonPrimitive("done")))
        assertEquals("true", text(result, "ok"))
        assertEquals("done", text(result, "value"))
    }

    @Test fun `a call that failed carries ok false and the error in words`() {
        val envelope = Envelope.err(ErrorCode.NOT_FOUND, "no such task", reason = "TASK_MISSING")
        val result = AgentEnvelopes.toolResult(envelope)
        assertEquals("false", text(result, "ok"))
        val error = result["error"] as JsonObject
        assertEquals(envelope.error?.code, text(error, "code"))
        assertFalse("a code is given", text(error, "code").isNullOrEmpty())
        assertEquals("TASK_MISSING", text(error, "reason"))
        assertEquals("no such task", text(error, "message"))
    }

    @Test fun `the fields of a result leave out ok, and a failed result has none`() {
        val worked = AgentEnvelopes.toolResult(Envelope.ok(buildJsonObject { put("package", "com.example.app") }))
        assertEquals(buildJsonObject { put("package", "com.example.app") }, AgentEnvelopes.fields(worked))
        val failed = AgentEnvelopes.toolResult(Envelope.err(ErrorCode.TIMEOUT, "too slow"))
        assertTrue(AgentEnvelopes.fields(failed).isEmpty())
    }

    @Test fun `the foreground package is read from the top level`() {
        val inScope = AgentEnvelopes.toolResult(
            Envelope.ok(buildJsonObject { put("package", "com.example.app"); put("activity", ".Main") }),
        )
        assertEquals("com.example.app", AgentEnvelopes.foregroundPackage(inScope))
    }

    @Test fun `an app outside the run's scope is never named, and a failed read names nothing`() {
        val outside = AgentEnvelopes.toolResult(Envelope.ok(buildJsonObject { put("in_scope", false) }))
        assertNull(AgentEnvelopes.foregroundPackage(outside))
        assertNull(AgentEnvelopes.foregroundPackage(AgentEnvelopes.toolResult(Envelope.err(ErrorCode.TIMEOUT, "slow"))))
    }

    @Test fun `a screen read through the transport shape reaches the observation filter whole`() {
        val snapshot = buildJsonObject {
            put("package", "com.example.app")
            put("node_count", 1)
            put(
                "nodes",
                JsonArray(listOf(buildJsonObject { put("pkg", "com.example.app"); put("label", "Next") })),
            )
        }
        val seen = AgentEnvelopes.fields(AgentEnvelopes.toolResult(Envelope.ok(snapshot)))
        assertEquals("the screen is not lost on the way", snapshot, seen)
        val filtered = ObservationFilter.filterSnapshot(seen, setOf("com.example.app"), AppSensitivity())
        assertFalse(filtered.withheld)
        assertEquals(snapshot, filtered.observation)
    }

    @Test fun `a screen from an app outside the run is still withheld once it is read properly`() {
        val snapshot = buildJsonObject { put("package", "com.other.app"); put("node_count", 0) }
        val seen = AgentEnvelopes.fields(AgentEnvelopes.toolResult(Envelope.ok(snapshot)))
        val filtered = ObservationFilter.filterSnapshot(seen, setOf("com.example.app"), AppSensitivity())
        assertTrue("before the fix the filter only ever saw an empty screen, so it could never withhold", filtered.withheld)
    }
}
