package app.autohost.runtime.js

import app.autohost.core.common.AhJson
import app.autohost.core.common.Clock
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11-01 (written; awaiting Gradle): the bridge op table — the host side of the JS contract
 * (docs/SDK_JS.md). The same table is exercised from the JS side by the simulator suite
 * (test_p11_part1.py); these JVM tests pin the semantics: envelope handling, redaction,
 * cancellation during sleep, state round-trip and the typed error replies.
 */
class JsBridgeTest {

    private class FakeCaller : JsBrokerCaller {
        val calls = mutableListOf<Triple<String, Pair<String, String>, JsonObject>>()
        val events = mutableListOf<Pair<String, JsonObject>>()
        var envelope: String? = null
        var describeJson: String = "{}"

        override fun call(runToken: String, capability: String, method: String, argsJson: String): String {
            val args = AhJson.json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
            calls.add(Triple(runToken, capability to method, args))
            return envelope ?: AhJson.json.encodeToString(
                Envelope.serializer(),
                Envelope.ok(buildJsonObject { put("ok", true) }),
            )
        }

        override fun describe(runToken: String): String = describeJson

        override fun emit(runToken: String, eventJson: String) {
            events.add(runToken to (AhJson.json.parseToJsonElement(eventJson) as? JsonObject ?: JsonObject(emptyMap())))
        }
    }

    private class FakeClock : Clock {
        override fun nowMs(): Long = 1_793_000_000_000
        override fun monotonicMs(): Long = 500_000
    }

    private class FakeMonotonic {
        var now = 0L
        fun clock(): Long = now
        fun sleeper(ms: Long) { now += ms }
    }

    private fun controls(fm: FakeMonotonic = FakeMonotonic()): JsRunControls =
        JsRunControls(monotonicMs = fm::clock)

    private fun bridge(
        caller: FakeCaller = FakeCaller(),
        ctrl: JsRunControls = controls(),
        secretOf: (String) -> String? = { name -> if (name == "pw") "s3cret-value" else null },
        redact: (String) -> String = { it.replace("s3cret-value", "[REDACTED]") },
        sleeper: (Long) -> Unit = {},
    ): JsBridge = JsBridge(
        caller = caller,
        clock = FakeClock(),
        controls = ctrl,
        runInfo = JsRunInfo(
            runToken = "tok", runId = "run-1", attempt = 2, triggerType = "schedule",
            triggerPayload = buildJsonObject { put("rule", "every-30") },
            startedAtIso = "2026-10-26T00:00:00Z",
            config = buildJsonObject { put("label", "Continue") },
            profile = buildJsonObject { put("city", "hyd") },
            checkpoint = buildJsonObject { put("stage", 1) },
        ),
        secretOf = secretOf,
        redact = redact,
        sleeper = sleeper,
    )

    private fun decode(reply: String): JsonObject = AhJson.json.parseToJsonElement(reply).jsonObject

    private fun payload(vararg pairs: Pair<String, String>): String =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.let {
            AhJson.json.encodeToString(JsonObject.serializer(), it)
        }

    // --- call ------------------------------------------------------------------------------

    @Test
    fun `call forwards to the broker and unwraps a result`() {
        val caller = FakeCaller()
        caller.envelope = AhJson.json.encodeToString(
            Envelope.serializer(),
            Envelope.ok(buildJsonObject { put("value", 42) }),
        )
        val reply = decode(bridge(caller).handle("call", payload("capability" to "store", "method" to "get")))
        assertTrue(reply["ok"]!!.let { (it as JsonPrimitive).content }.toBoolean())
        assertEquals(42, (reply["result"] as JsonObject)["value"]!!.let { (it as JsonPrimitive).content.toInt() })
        assertEquals("store" to "get", caller.calls.single().second)
        assertEquals("tok", caller.calls.single().first)
    }

    @Test
    fun `call surfaces broker errors verbatim`() {
        val caller = FakeCaller()
        caller.envelope = AhJson.json.encodeToString(
            Envelope.serializer(),
            Envelope.err(ErrorCode.TIMEOUT, "late"),
        )
        val reply = decode(bridge(caller).handle("call", payload("capability" to "device.ui", "method" to "snapshot")))
        assertTrue((reply["ok"] as JsonPrimitive).content.toBoolean().not())
        val error = reply["error"] as JsonObject
        assertEquals("TIMEOUT", (error["code"] as JsonPrimitive).content)
        assertEquals(true, (error["retryable"] as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `call with an unparsable envelope is an honest INTERNAL`() {
        val caller = FakeCaller().apply { envelope = "not-json" }
        val reply = decode(bridge(caller).handle("call", payload("capability" to "store", "method" to "get")))
        assertEquals("INTERNAL", ((reply["error"] as JsonObject)["code"] as JsonPrimitive).content)
    }

    @Test
    fun `call without capability or method is INVALID_ARGS`() {
        val reply = decode(bridge().handle("call", payload("method" to "get")))
        assertEquals("INVALID_ARGS", ((reply["error"] as JsonObject)["code"] as JsonPrimitive).content)
    }

    // --- emit / sleep ------------------------------------------------------------------------

    @Test
    fun `emit redacts secret values before they leave`() {
        val caller = FakeCaller()
        val reply = decode(bridge(caller).handle("emit", payload("x" to "the secret is s3cret-value")))
        assertNull(reply["result"])
        val event = caller.events.single().second
        assertEquals("the secret is [REDACTED]", (event["x"] as JsonPrimitive).content)
    }

    @Test
    fun `sleep returns ok and honours the cap`() {
        val fm = FakeMonotonic()
        val ctrl = controls(fm)
        val sleeps = mutableListOf<Long>()
        val reply = decode(bridge(ctrl = ctrl, sleeper = { ms -> sleeps.add(ms); fm.sleeper(ms) }).handle("sleep", "{\"seconds\":7200}"))
        assertNull(reply["result"])
        // The full (capped) duration was slept in ≤200 ms slices, without real waiting.
        assertEquals(7_200_000L, sleeps.sum())
        assertTrue(sleeps.max() <= 200)
    }

    @Test
    fun `cancel during sleep surfaces as a CANCELLED error reply`() {
        val fm = FakeMonotonic()
        val ctrl = controls(fm)
        val reply = decode(
            bridge(ctrl = ctrl, sleeper = { _ -> ctrl.requestCancel() }).handle("sleep", "{\"seconds\":5}"),
        )
        assertEquals("CANCELLED", ((reply["error"] as JsonObject)["code"] as JsonPrimitive).content)
    }

    // --- ctx / state / clock -----------------------------------------------------------------

    @Test
    fun `ctx returns the python-shaped run and trigger objects`() {
        val run = decode(bridge().handle("ctx", payload("what" to "run")))["result"] as JsonObject
        assertEquals("run-1", (run["id"] as JsonPrimitive).content)
        assertEquals(2, (run["attempt"] as JsonPrimitive).content.toInt())
        assertEquals("schedule", ((run["trigger"] as JsonObject)["type"] as JsonPrimitive).content)
        val trigger = decode(bridge().handle("ctx", payload("what" to "trigger")))["result"] as JsonObject
        assertEquals("every-30", ((trigger["payload"] as JsonObject)["rule"] as JsonPrimitive).content)
        val config = decode(bridge().handle("ctx", payload("what" to "config")))["result"] as JsonObject
        assertEquals("Continue", (config["label"] as JsonPrimitive).content)
    }

    @Test
    fun `state_save persists through the broker and updates the snapshot`() {
        val caller = FakeCaller()
        val b = bridge(caller)
        val reply = decode(b.handle("state_save", "{\"stage\":2,\"done\":true}"))
        assertNull(reply["result"])
        assertEquals("state" to "set", caller.calls.single().second)
        val state = decode(b.handle("ctx", payload("what" to "state")))["result"] as JsonObject
        assertEquals(2, (state["stage"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `clock produces the same ISO shape the python SDK produces`() {
        val now = ((decode(bridge().handle("clock", "null"))["result"] as JsonObject)["now_iso"] as JsonPrimitive).content
        assertEquals("2026-10-26T00:00:00Z", now)
    }

    // --- secret / result / next_run / capabilities ---------------------------------------------

    @Test
    fun `secret resolves declared names and fails closed on unknown ones`() {
        val got = decode(bridge().handle("secret", payload("name" to "pw")))["result"]
        assertEquals("s3cret-value", (got as JsonPrimitive).content)
        val missing = decode(bridge().handle("secret", payload("name" to "nope")))
        assertEquals("NOT_FOUND", ((missing["error"] as JsonObject)["code"] as JsonPrimitive).content)
    }

    @Test
    fun `result op stores the pending run result`() {
        val b = bridge()
        decode(b.handle("result", "{\"code\":\"pressed\",\"data\":{\"n\":1}}"))
        assertEquals("pressed", (b.pendingResult.get()!!["code"] as JsonPrimitive).content)
    }

    @Test
    fun `next_run needs exactly one of after_minutes or at`() {
        val caller = FakeCaller()
        decode(bridge(caller).handle("next_run", "{\"after_minutes\":5}"))
        assertEquals("next_run", (caller.events.single().second)["type"]?.let { (it as JsonPrimitive).content })
        val both = decode(bridge().handle("next_run", "{\"after_minutes\":5,\"at\":\"2026-10-27T00:00:00Z\"}"))
        assertEquals("INVALID_ARGS", ((both["error"] as JsonObject)["code"] as JsonPrimitive).content)
        val none = decode(bridge().handle("next_run", "{}"))
        assertEquals("INVALID_ARGS", ((none["error"] as JsonObject)["code"] as JsonPrimitive).content)
    }

    @Test
    fun `capabilities passes the availability describe through`() {
        val caller = FakeCaller().apply { describeJson = "{\"device.ui\":{\"available\":true}}" }
        val result = decode(bridge(caller).handle("capabilities", "null"))["result"] as JsonObject
        assertTrue(result.containsKey("device.ui"))
    }

    @Test
    fun `unknown ops are INVALID_ARGS`() {
        val reply = decode(bridge().handle("teleport", "{}"))
        assertEquals("INVALID_ARGS", ((reply["error"] as JsonObject)["code"] as JsonPrimitive).content)
    }

    @Test
    fun `iso format truncates to seconds like the python runtime`() {
        assertEquals("1970-01-01T00:00:00Z", IsoFormat.ofMs(0))
        assertEquals("2026-10-26T00:00:00Z", IsoFormat.ofMs(1_793_000_000_999))
    }
}
