package app.autohost.runtime.flow

import app.autohost.core.model.FlowDocument
import app.autohost.core.model.FlowStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6-02 + P6-04: interpreter semantics with a stub broker — the Kotlin mirror of the Python
 * RunTests in test_ref_flows.py (control flow, optional/retry/repeat/runFlow, hooks, outcomes).
 */
@Suppress("UNUSED_PARAMETER")
class FlowInterpreterTest {
    private class StubBroker(
        handlers: Map<Pair<String, String>, (JsonObject) -> JsonObject> = emptyMap(),
        failFirst: Map<Pair<String, String>, MutableList<BrokerCallException>> = emptyMap(),
    ) : FlowBroker {
        val calls = mutableListOf<Triple<String, String, JsonObject>>()
        val sleeps = mutableListOf<Long>()
        private val handlers = handlers
        private val failFirst = failFirst

        override suspend fun call(capability: String, method: String, args: JsonObject): JsonObject {
            calls.add(Triple(capability, method, args))
            failFirst[capability to method]?.removeFirstOrNull()?.let { throw it }
            return handlers[capability to method]?.invoke(args) ?: buildJsonObject { }
        }

        fun sleep(ms: Long) {
            sleeps.add(ms)
        }
    }

    private class Recorder(broker: StubBroker, subflows: Map<String, FlowDocument> = emptyMap(),
                           apiLevel: Int = 29, cancelAfter: Int? = null) {
        val events = mutableListOf<JsonObject>()
        var checks = 0
        private var cancelAfter = cancelAfter
        val ex = FlowExec(
            broker,
            emit = { events.add(it) },
            checkCancel = {
                checks += 1
                if (cancelAfter != null && checks > cancelAfter) throw FlowCancelledException()
            },
            sleep = { ms -> broker.sleep(ms) },
            apiLevel = apiLevel,
            subflows = subflows,
        )
        val brokerRef = broker

        suspend fun run(flow: FlowDocument, config: JsonObject = buildJsonObject { },
                        env: JsonObject = buildJsonObject { },
                        secrets: Map<String, String> = emptyMap(),
                        run: JsonObject = buildJsonObject { put("attempt", 1) }): FlowOutcome =
            FlowInterpreter.runFlow(flow, ex, config = config, env = env, secrets = secrets, run = run)

        fun calls(capability: String, method: String) =
            brokerRef.calls.filter { it.first == capability && it.second == method }.map { it.third }

        fun byType(type: String) = events.filter { it.getValue("type").toString() == "\"$type\"" }
    }

    private fun flow(vararg steps: String): FlowDocument =
        FlowParser.parse("""{"schema": 1, "steps": [${steps.joinToString(",")}]}""")

    private fun pressFlow(): FlowDocument = FlowParser.parse(
        """{"schema": 1, "name": "Press button", "steps": [
           {"launchApp": {"package": "com.example.app"}},
           {"waitUntil": {"visible": {"text": ["Continue"]}, "timeout": 5}},
           {"tapOn": {"text": ["Continue"]}},
           {"setResult": {"code": "pressed"}}]}""",
    )

    private fun pressBroker(): StubBroker = StubBroker(
        handlers = mapOf(
            ("device.app" to "launch") to { _ -> buildJsonObject { put("result", "LAUNCHED_VERIFIED") } },
            ("device.ui" to "wait_for") to { _ -> buildJsonObject { put("node", buildJsonObject { put("text", "Continue") }) } },
            ("device.ui" to "click") to { _ -> buildJsonObject { put("method", "NODE_ACTION"); put("verified", true) } },
        ),
    )

    @Test fun `happy path press button flow`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(pressBroker())
        val outcome = recorder.run(
            pressFlow(),
            config = buildJsonObject { put("target_package", "com.example.app") },
        )
        assertEquals(
            FlowOutcome("SUCCEEDED", result = "pressed" to buildJsonObject { }),
            outcome,
        )
        assertEquals(3, recorder.brokerRef.calls.size)
        assertEquals("com.example.app", recorder.calls("device.app", "launch")[0].getValue("package").toString())
    }

    @Test fun `failed tap is typed failure with evidence`() = kotlinx.coroutines.runBlocking {
        val broker = StubBroker(
            handlers = mapOf(
                ("device.app" to "launch") to { _ -> buildJsonObject { } },
                ("device.ui" to "wait_for") to { _ -> buildJsonObject { put("node", buildJsonObject { }) } },
                ("device.ui" to "click") to { _ -> buildJsonObject { put("method", "COORDINATE"); put("verified", false) } },
            ),
        )
        val recorder = Recorder(broker)
        val outcome = recorder.run(pressFlow())
        assertEquals("FAILED", outcome.status)
        assertEquals("TIMEOUT", outcome.reason)
        val evidence = recorder.byType("evidence")
        assertEquals(1, evidence.size)
        val data = evidence[0].getValue("data").jsonObject
        assertEquals("\"TIMEOUT\"", data.getValue("code").toString())
        assertEquals("true", data.getValue("retryable").toString())
        assertTrue(data.getValue("selector").toString().contains("\"text\""))
    }

    @Test fun `optional step swallows failure with warn log`() = kotlinx.coroutines.runBlocking {
        val broker = StubBroker(
            handlers = mapOf(
                ("device.app" to "launch") to { _ -> buildJsonObject { } },
                ("device.ui" to "wait_for") to { _ -> buildJsonObject { put("node", buildJsonObject { }) } },
                ("device.ui" to "click") to { _ -> buildJsonObject { put("verified", false) } },
            ),
        )
        val recorder = Recorder(broker)
        val flow = FlowParser.parse(
            """{"schema": 1, "steps": [
               {"launchApp": {"package": "com.example.app"}},
               {"waitUntil": {"visible": {"text": ["Continue"]}, "timeout": 5}},
               {"tapOn": {"text": ["Continue"], "optional": true}},
               {"setResult": {"code": "pressed"}}]}""",
        )
        val outcome = recorder.run(flow)
        assertEquals("SUCCEEDED", outcome.status)
        assertEquals(1, recorder.byType("evidence").size)
        val warns = recorder.events.filter {
            it.getValue("type").toString() == "\"log\"" && it.getValue("level").toString() == "\"warn\""
        }
        assertEquals(1, warns.size)
    }

    @Test fun `optional does not swallow cancellation`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(StubBroker(), cancelAfter = 0)
        val outcome = recorder.run(flow("""{"launchApp": {"package": "p", "optional": true}}"""))
        assertEquals(FlowOutcome("CANCELLED", reason = "USER_CANCELLED"), outcome)
    }

    @Test fun `when visible gates the step`() = kotlinx.coroutines.runBlocking {
        val broker = StubBroker(
            handlers = mapOf(("device.ui" to "find") to { _ -> buildJsonObject { put("nodes", JsonArray()) } }),
        )
        val recorder = Recorder(broker)
        val outcome = recorder.run(flow("""{"tapOn": {"text": "Continue", "when": {"visible": {"id": "banner"}}}}"""))
        assertEquals("SUCCEEDED", outcome.status)
        assertEquals(0, recorder.calls("device.ui", "click").size)
    }

    @Test fun `when true gates the step`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(StubBroker())
        val gated = flow("""{"log": {"message": "ran", "when": {"true": "env.go == 'yes'"}}}""")
        recorder.run(gated, env = buildJsonObject { put("go", "no") })
        assertEquals(0, recorder.byType("log").size)
        recorder.run(gated, env = buildJsonObject { put("go", "yes") })
        assertEquals("\"ran\"", recorder.byType("log").last().getValue("message").toString())
    }

    @Test fun `when minApi gates the step`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(StubBroker(), apiLevel = 29)
        recorder.run(flow(
            """{"log": {"message": "new", "when": {"minApi": 30}}}""",
            """{"log": {"message": "old", "when": {"minApi": 29}}}""",
        ))
        assertEquals(listOf("\"old\""), recorder.byType("log").map { it.getValue("message").toString() })
    }

    @Test fun `retry retries retryable failure then succeeds`() = kotlinx.coroutines.runBlocking {
        val broker = StubBroker(
            handlers = mapOf(("device.app" to "launch") to { _ -> buildJsonObject { } }),
            failFirst = mapOf(
                ("device.app" to "launch") to mutableListOf(
                    BrokerCallException("TIMEOUT", "slow", retryable = true),
                ),
            ),
        )
        val recorder = Recorder(broker)
        val outcome = recorder.run(flow("""{"retry": {"times": 2, "steps": [{"launchApp": {"package": "p"}}]}}"""))
        assertEquals("SUCCEEDED", outcome.status)
        assertEquals(2, broker.calls.size)
        assertEquals(listOf(500L), broker.sleeps)
    }

    @Test fun `retry never retries terminal failure`() = kotlinx.coroutines.runBlocking {
        val broker = StubBroker(
            failFirst = mapOf(
                ("device.app" to "launch") to mutableListOf(BrokerCallException("NOT_FOUND", "gone", retryable = false)),
            ),
        )
        val recorder = Recorder(broker)
        val outcome = recorder.run(flow("""{"retry": {"times": 3, "steps": [{"launchApp": {"package": "p"}}]}}"""))
        assertEquals("FAILED", outcome.status)
        assertEquals("NOT_FOUND", outcome.reason)
        assertEquals(1, broker.calls.size)
        assertEquals(0, broker.sleeps.size)
    }

    @Test fun `retry exhaustion fails with the last code`() = kotlinx.coroutines.runBlocking {
        val broker = StubBroker(
            failFirst = mapOf(
                ("device.app" to "launch") to mutableListOf(
                    BrokerCallException("TIMEOUT", "slow", retryable = true),
                    BrokerCallException("TIMEOUT", "slow again", retryable = true),
                ),
            ),
        )
        val recorder = Recorder(broker)
        val outcome = recorder.run(flow("""{"retry": {"times": 1, "steps": [{"launchApp": {"package": "p"}}]}}"""))
        assertEquals("FAILED", outcome.status)
        assertEquals("TIMEOUT", outcome.reason)
        assertEquals(2, broker.calls.size)
    }

    @Test fun `repeat times runs exactly n times`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(StubBroker())
        val outcome = recorder.run(flow("""{"repeat": {"times": 3, "steps": [{"log": {"message": "tick"}}]}}"""))
        assertEquals("SUCCEEDED", outcome.status)
        assertEquals(3, recorder.byType("log").size)
    }

    @Test fun `repeat while exits when the state changes`() = kotlinx.coroutines.runBlocking {
        val counter = arrayOf(0)
        val broker = StubBroker(
            handlers = mapOf(
                ("device.app" to "launch") to { _ -> buildJsonObject { } },
                ("device.ui" to "find") to { _ ->
                    counter[0] += 1
                    buildJsonObject {
                        put("nodes", JsonArray(listOf(buildJsonObject { put("text", "${counter[0]}") })))
                    }
                },
            ),
        )
        val recorder = Recorder(broker)
        val outcome = recorder.run(
            FlowParser.parse(
                """{"schema": 1, "env": {"n": "0"}, "steps": [
                   {"repeat": {"while": "env.n != '3'", "steps": [
                       {"copyTextFrom": {"text": {"mode": "regex", "value": "."}, "into": "n"}}]}}]}""",
            ),
        )
        assertEquals("SUCCEEDED", outcome.status)
        assertEquals(3, counter[0])
    }

    @Test fun `while with a non-boolean result fails closed`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(StubBroker())
        val outcome = recorder.run(
            flow("""{"repeat": {"while": "env.n + ''", "steps": [{"log": {"message": "x"}}]}}"""),
            env = buildJsonObject { put("n", 1) },
        )
        assertEquals("FAILED", outcome.status)
        assertEquals("INVALID_ARGS", outcome.reason)
    }

    @Test fun `runFlow child env is isolated`() = kotlinx.coroutines.runBlocking {
        val child = FlowParser.parse(
            """{"schema": 1, "steps": [
               {"defineVariables": {"z": "9"}},
               {"log": {"message": "${'$'}{env.x}-${'$'}{env.y}"}}]}""",
        )
        val recorder = Recorder(StubBroker(), subflows = mapOf("child" to child))
        val outcome = recorder.run(
            flow(
                """{"defineVariables": {"x": "1"}}""",
                """{"runFlow": {"file": "child", "env": {"y": "2"}}}""",
                """{"log": {"message": "${'$'}{env.z}"}}""",
            ),
        )
        assertEquals("SUCCEEDED", outcome.status)
        assertEquals(
            listOf("\"1-2\"", "\"\${env.z}\""),
            recorder.byType("log").map { it.getValue("message").toString() },
        )
    }

    @Test fun `missing subflow fails typed`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(StubBroker())
        val outcome = recorder.run(flow("""{"runFlow": {"file": "nope"}}"""))
        assertEquals("FAILED", outcome.status)
        assertEquals("INVALID_ARGS", outcome.reason)
    }

    @Test fun `onFlowStart failure prevents the body`() = kotlinx.coroutines.runBlocking {
        val broker = StubBroker(
            handlers = mapOf(("device.ui" to "find") to { _ -> buildJsonObject { put("nodes", JsonArray()) } }),
        )
        val recorder = Recorder(broker)
        val outcome = recorder.run(
            FlowParser.parse(
                """{"schema": 1, "onFlowStart": [{"assertVisible": {"id": "gate"}}],
                   "steps": [{"launchApp": {"package": "com.example.app"}}]}""",
            ),
        )
        assertEquals("FAILED", outcome.status)
        assertEquals("NOT_FOUND", outcome.reason)
        assertEquals(0, recorder.calls("device.app", "launch").size)
    }

    @Test fun `onFlowComplete runs only on success`() = kotlinx.coroutines.runBlocking {
        val ok = Recorder(pressBroker())
        val okFlow = FlowParser.parse(
            """{"schema": 1, "onFlowComplete": [{"log": {"message": "bye"}}],
               "steps": [{"log": {"message": "body"}}]}""",
        )
        assertEquals("SUCCEEDED", ok.run(okFlow).status)
        assertEquals(listOf("\"bye\""), ok.byType("log").map { it.getValue("message").toString() })

        val bad = Recorder(StubBroker(
            handlers = mapOf(("device.ui" to "click") to { _ -> buildJsonObject { put("verified", false) } }),
        ))
        val badFlow = FlowParser.parse(
            """{"schema": 1, "onFlowComplete": [{"log": {"message": "bye"}}],
               "steps": [{"tapOn": {"text": "Missing"}}]}""",
        )
        assertEquals("FAILED", bad.run(badFlow).status)
        assertEquals(0, bad.byType("log").size)
    }

    @Test fun `ask unanswered ends waiting user`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(StubBroker(
            handlers = mapOf(("ui.prompt" to "ask") to { _ ->
                buildJsonObject { put("answered", false); put("reason", "timeout") }
            }),
        ))
        val outcome = recorder.run(flow("""{"ask": {"title": "Go on?", "message": "Ready?"}}"""))
        assertEquals("WAITING_USER", outcome.status)
        assertEquals("NEEDS_INPUT", outcome.reason)
    }

    @Test fun `log messages are redacted`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(StubBroker())
        recorder.run(flow("""{"log": {"message": "token s3cr3t leaked"}}"""), secrets = mapOf("pw" to "s3cr3t"))
        assertEquals("\"token [REDACTED] leaked\"", recorder.byType("log").last().getValue("message").toString())
    }

    @Test fun `setResult and nextRun reach the outcome`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(StubBroker())
        val outcome = recorder.run(flow(
            """{"nextRun": {"afterMinutes": 30}}""",
            """{"setResult": {"code": "done", "data": {"n": 1}}}""",
        ))
        assertEquals("SUCCEEDED", outcome.status)
        assertEquals("done" to buildJsonObject { put("n", 1) }, outcome.result)
        assertEquals(buildJsonObject { put("afterMinutes", 30) }, outcome.nextRun)
        assertEquals(1, recorder.byType("next_run").size)
    }

    @Test fun `cancelled run outcome`() = kotlinx.coroutines.runBlocking {
        val recorder = Recorder(StubBroker(), cancelAfter = 0)
        val outcome = recorder.run(flow("""{"log": {"message": "x"}}"""))
        assertEquals(FlowOutcome("CANCELLED", reason = "USER_CANCELLED"), outcome)
    }

}
