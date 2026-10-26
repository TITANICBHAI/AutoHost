package app.autohost.runtime.js

import app.autohost.core.common.AhJson
import app.autohost.core.model.Envelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11-01 (written; awaiting Gradle): the outcome classification — the JS mirror of
 * `execute_with_runtime`. Pinned branch-for-branch with the simulator suite (test_p11_part1.py)
 * so a JS task fails identically in the sim and on the device.
 */
class JsOutcomeTest {

    private fun provider(): JsRuntimeProvider = JsRuntimeProvider(
        engineFactory = { error("not used in classify tests") },
        caller = object : JsBrokerCaller {
            override fun call(runToken: String, capability: String, method: String, argsJson: String): String =
                AhJson.json.encodeToString(Envelope.serializer(), Envelope.ok(JsonObject(emptyMap())))

            override fun describe(runToken: String): String = "{}"
            override fun emit(runToken: String, eventJson: String) = Unit
        },
        dispatchers = app.autohost.core.common.AppDispatchers(
            main = kotlinx.coroutines.test.UnconfinedTestDispatcher(),
            io = kotlinx.coroutines.Dispatchers.Unconfined,
            default = kotlinx.coroutines.test.UnconfinedTestDispatcher(),
        ),
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        clock = object : app.autohost.core.common.Clock {
            override fun nowMs(): Long = 1_793_000_000_000
            override fun monotonicMs(): Long = 0
        },
        secretOf = { _ -> null },
        redact = { it },
    )

    private fun classify(outcome: String, cancelled: Boolean = false, pending: Boolean = false): JsonObject {
        val p = provider()
        val bridge = JsBridge(
            caller = object : JsBrokerCaller {
                override fun call(runToken: String, capability: String, method: String, argsJson: String) = "{}"
                override fun describe(runToken: String) = "{}"
                override fun emit(runToken: String, eventJson: String) = Unit
            },
            clock = object : app.autohost.core.common.Clock {
                override fun nowMs(): Long = 0
                override fun monotonicMs(): Long = 0
            },
            controls = JsRunControls(),
            runInfo = JsRunInfo("tok", "run-1", 1, "manual", JsonObject(emptyMap()), "", JsonObject(emptyMap()),
                                JsonObject(emptyMap()), JsonObject(emptyMap())),
            secretOf = { _ -> null },
            redact = { it },
            sleeper = {},
        )
        if (pending) {
            bridge.pendingResult.set(buildJsonObject { put("code", JsonPrimitive("stored")); put("data", JsonObject(emptyMap())) })
        }
        val controls = JsRunControls().apply { if (cancelled) requestCancel() }
        return p.classify(outcome, bridge, controls)
    }

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun `a marked result return wins`() {
        val outcome = """{"status":"ok","value":{"__result__":{"code":"pressed","data":{"n":1}}}}"""
        val classified = classify(outcome)
        assertEquals("SUCCEEDED", str(classified, "status"))
        val result = classified["result"] as JsonObject
        assertEquals("pressed", str(result, "code"))
    }

    @Test
    fun `a plain return falls back to the ctx result then OK`() {
        val viaCtx = classify("""{"status":"ok","value":{"plain":true}}""", pending = true)
        assertEquals("SUCCEEDED", str(viaCtx, "status"))
        assertEquals("stored", str(viaCtx["result"] as JsonObject, "code"))
        val plain = classify("""{"status":"ok","value":null}""")
        assertEquals("OK", str(plain["result"] as JsonObject, "code"))
    }

    @Test
    fun `cancel after ok is still CANCELLED`() {
        val classified = classify("""{"status":"ok","value":null}""", cancelled = true)
        assertEquals("CANCELLED", str(classified, "status"))
        assertEquals("USER_CANCELLED", str(classified, "reason"))
    }

    @Test
    fun `needs_user becomes WAITING_USER with the reason and resume hint`() {
        val outcome = """
            {"status":"error","error":{"kind":"needs_user","code":"NEEDS_USER",
             "reason":"SECURITY_CHALLENGE","message":"captcha page","resume_hint":"solve"}}
        """.trimIndent()
        val classified = classify(outcome)
        assertEquals("WAITING_USER", str(classified, "status"))
        assertEquals("SECURITY_CHALLENGE", str(classified, "reason"))
        assertEquals("solve", str(classified, "resume_hint"))
    }

    @Test
    fun `autohost errors keep the reason or fall back to the code`() {
        val withReason = classify(
            """{"status":"error","error":{"kind":"autohost","code":"TIMEOUT","reason":"WAIT_TIMEOUT","message":"late"}}""",
        )
        assertEquals("FAILED", str(withReason, "status"))
        assertEquals("WAIT_TIMEOUT", str(withReason, "reason"))
        val codeOnly = classify("""{"status":"error","error":{"kind":"autohost","code":"TIMEOUT","message":"late"}}""")
        assertEquals("TIMEOUT", str(codeOnly, "reason"))
    }

    @Test
    fun `entry problems are FAILED ENTRY_NOT_FOUND`() {
        val classified = classify("""{"status":"error","error":{"kind":"entry","message":"no main() found"}}""")
        assertEquals("FAILED", str(classified, "status"))
        assertEquals("ENTRY_NOT_FOUND", str(classified, "reason"))
    }

    @Test
    fun `script exceptions carry the stack as the traceback`() {
        val classified = classify(
            """{"status":"error","error":{"kind":"exception","message":"TypeError: boom","stack":"at main (main.js:3)"}}""",
        )
        assertEquals("FAILED", str(classified, "status"))
        assertEquals("EXCEPTION", str(classified, "reason"))
        assertEquals("TypeError: boom", str(classified, "message"))
        assertEquals("at main (main.js:3)", str(classified, "traceback"))
    }

    @Test
    fun `an unparsable outcome is an honest EXCEPTION`() {
        val classified = classify("not-json")
        assertEquals("FAILED", str(classified, "status"))
        assertEquals("EXCEPTION", str(classified, "reason"))
        assertTrue(str(classified, "message")!!.contains("unparsable"))
    }
}
