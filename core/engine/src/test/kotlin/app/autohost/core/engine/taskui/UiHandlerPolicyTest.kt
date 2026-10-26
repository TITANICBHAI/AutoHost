package app.autohost.core.engine.taskui

import app.autohost.core.common.FakeClock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P7-03: UI-handler policy — spec validation, single-flight gate, outcome parsing, idle policy. */
class UiHandlerPolicyTest {

    @Test
    fun builds_valid_spec_and_json_round_trip() {
        val spec = UiHandlerSpec.build(
            taskId = "com.example.ui", taskPath = "/tasks/demo", entry = "main.py",
            handler = "greet", args = buildJsonObject { put("who", "x") },
        )
        val json = spec.toJson()
        assertEquals("greet", json["handler"]?.let { it as JsonPrimitive }?.content)
        assertTrue(json.toString().contains("\"taskPath\":\"/tasks/demo\""))
    }

    @Test
    fun rejects_bad_names_and_paths() {
        org.junit.Assert.assertThrows(UiHandlerPolicyException::class.java) {
            UiHandlerSpec.build("t", "/p", "main.py", "")
        }
        org.junit.Assert.assertThrows(UiHandlerPolicyException::class.java) {
            UiHandlerSpec.build("t", "/p", "main.py", "has space")
        }
        org.junit.Assert.assertThrows(UiHandlerPolicyException::class.java) {
            UiHandlerSpec.build("t", "/p", "../escape.py", "greet")
        }
        org.junit.Assert.assertThrows(UiHandlerPolicyException::class.java) {
            UiHandlerSpec.build("t", "/p", "/abs/main.py", "greet")
        }
        org.junit.Assert.assertThrows(UiHandlerPolicyException::class.java) {
            UiHandlerSpec.build("", "/p", "main.py", "greet")
        }
    }

    @Test
    fun gate_is_single_flight_per_task() {
        val gate = UiHandlerGate()
        assertTrue(gate.tryAcquire("t1"))
        assertFalse(gate.tryAcquire("t1"))
        assertTrue(gate.tryAcquire("t2")) // another task is independent
        gate.release("t1")
        assertTrue(gate.tryAcquire("t1"))
    }

    @Test
    fun parses_worker_outcome() {
        val ok = UiHandlerOutcome.fromJson(
            """{"status":"SUCCEEDED","result":{"code":"OK","data":{"state":{"count":2}}}}""",
        )!!
        assertTrue(ok.ok)
        assertEquals(buildJsonObject { put("count", 2) }, ok.data)

        val failed = UiHandlerOutcome.fromJson(
            """{"status":"FAILED","reason":"HANDLER_NOT_FOUND","message":"no handler"}""",
        )!!
        assertEquals("HANDLER_NOT_FOUND", failed.reason)
        assertFalse(failed.ok)
        assertNull(failed.data)

        assertNull(UiHandlerOutcome.fromJson(null))
        assertNull(UiHandlerOutcome.fromJson(""))
        assertNull(UiHandlerOutcome.fromJson("not json"))
        assertNull(UiHandlerOutcome.fromJson("""{"reason":"no status"}"""))
    }

    @Test
    fun busy_and_timeout_sentinels_carry_reasons() {
        assertEquals("HANDLER_BUSY", UiHandlerOutcome.BUSY.reason)
        assertEquals("HANDLER_TIMEOUT", UiHandlerOutcome.TIMEOUT.reason)
        assertEquals(30_000L, UI_HANDLER_TIMEOUT_MS)
    }

    @Test
    fun idle_policy_stops_after_quiet_period() {
        val clock = FakeClock(startMs = 1_000_000L)
        val policy = UiHandlerIdlePolicy(idleStopMs = 30_000, clock = clock)
        assertFalse(policy.shouldStop())
        clock.advance(29_999)
        assertFalse(policy.shouldStop())
        clock.advance(1)
        assertTrue(policy.shouldStop())
        policy.touch()
        assertFalse(policy.shouldStop())
    }
}
