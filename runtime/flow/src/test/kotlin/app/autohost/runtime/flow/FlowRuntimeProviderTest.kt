package app.autohost.runtime.flow

import app.autohost.core.common.AhJson
import app.autohost.core.common.AppDispatchers
import app.autohost.core.model.Envelope
import app.autohost.core.model.RunSpec
import app.autohost.core.model.TriggerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P6-06 (written; awaiting Gradle): the provider contract — entry resolution, typed start
 * failures, in-process execution through a fake binder, plug-gated YAML, subflow registration,
 * and run-control semantics. The binder is a fake transport (call → envelope JSON) without Android.
 */
class FlowRuntimeProviderTest {

    private val dispatchers = AppDispatchers(
        main = UnconfinedTestDispatcher(),
        io = Dispatchers.Unconfined,
        default = UnconfinedTestDispatcher(),
    )
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    /** Pure-JVM stand-in for the AIDL adapter, with envelope semantics included. */
    private class FakeBinder : FlowBinderTransport {
        val calls = mutableListOf<Triple<String, String, JsonObject>>()
        val events = mutableListOf<JsonObject>()
        var failEnvelope: String? = null

        /** What a call answers, keyed "capability.method"; every other call answers {"ok": true}. */
        val answers = mutableMapOf<String, JsonObject>()

        /** A raw error envelope a call answers with, keyed "capability.method". */
        val refusals = mutableMapOf<String, String>()

        override fun call(runToken: String, capability: String, method: String, argsJson: String): String {
            failEnvelope?.let { return it }
            val args = AhJson.json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
            calls.add(Triple(capability, method, args))
            refusals["$capability.$method"]?.let { return it }
            return AhJson.json.encodeToString(
                Envelope.serializer(),
                Envelope.ok(answers["$capability.$method"] ?: buildJsonObject { put("ok", true) }),
            )
        }

        override fun emit(runToken: String, eventJson: String) {
            events.add(
                (AhJson.json.parseToJsonElement(eventJson) as? JsonObject) ?: JsonObject(emptyMap()),
            )
        }
    }

    private fun provider(binder: FakeBinder): FlowRuntimeProvider = FlowRuntimeProvider(
        binder = binder,
        dispatchers = dispatchers,
        scope = scope,
        apiLevel = 29,
        heartbeatIntervalMs = 60_000, // one heartbeat at most inside these tests
    )

    private fun spec(taskDir: File, entry: String = "flow.json") = RunSpec(
        runId = "run-1", taskId = "t", runtime = "flow",
        entry = entry, taskPath = taskDir.absolutePath, runToken = "tok",
        config = buildJsonObject { put("label", "Press") },
    )

    @Test
    fun `happy path runs in-process and emits finished with the outcome`() {
        val tmp = TemporaryFolder().apply { create() }
        try {
            val dir = tmp.newFolder("task")
            File(dir, "task.json").writeText("{}")
            File(dir, "flow.json").writeText("""{"schema":1,"steps":[{"setResult":{"code":"pressed"}}]}""")
            val binder = FakeBinder()
            val handle = runBlocking { provider(binder).start(spec(dir)) }
            // Unconfined dispatchers: the interpreter ran to completion inside start().
            val finished = binder.events.last { (it["type"] as? JsonPrimitive)?.content == "finished" }
            val outcome = finished["data"] as JsonObject
            assertEquals("SUCCEEDED", (outcome["status"] as JsonPrimitive).content)
            assertTrue(handle is InProcessRunHandle)
            handle.release()
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    @Test
    fun `missing entry file fails with a typed message`() {
        val tmp = TemporaryFolder().apply { create() }
        try {
            val dir = tmp.newFolder("task")
            runBlocking {
                try {
                    provider(FakeBinder()).start(spec(dir))
                    fail("expected IllegalStateException")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.contains("no flow document found"))
                }
            }
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    @Test
    fun `unknown command maps to a parse failure before any broker call`() {
        val tmp = TemporaryFolder().apply { create() }
        try {
            val dir = tmp.newFolder("task")
            File(dir, "flow.json").writeText("""{"schema":1,"steps":[{"noSuchCommand":{}}]}""")
            val binder = FakeBinder()
            runBlocking {
                try {
                    provider(binder).start(spec(dir))
                    fail("expected FlowSourceException")
                } catch (e: FlowSourceException) {
                    assertTrue(e.message!!.contains("failed to parse"))
                }
            }
            assertEquals(0, binder.calls.size)
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    @Test
    fun `yaml entry without the plug fails closed with the ADR-052 message`() {
        val tmp = TemporaryFolder().apply { create() }
        try {
            val dir = tmp.newFolder("task")
            File(dir, "flow.yaml").writeText("steps:\n  - log:\n      message: hi\n")
            runBlocking {
                try {
                    provider(FakeBinder()).start(spec(dir, entry = "flow.yaml"))
                    fail("expected FlowSourceException")
                } catch (e: FlowSourceException) {
                    assertTrue(e.message!!.contains("ADR-052"))
                }
            }
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    @Test
    fun `installed yaml plug parses flow yaml end to end`() {
        // The plug interface is what ships in v1; the real yamlkt plug lands on a Gradle machine
        // (ADR-052). This scripted plug asserts the exact text it was built for — no YAML grammar.
        val sample = "schema: 1\nsteps:\n  - setResult:\n      code: pressed\n"
        YamlParserPlug.install(object : YamlParserPlug {
            override fun parseToJsonObject(text: String): JsonObject {
                assertEquals(sample, text)
                return buildJsonObject {
                    put("schema", JsonPrimitive(1))
                    put("steps", AhJson.json.parseToJsonElement("""[{"setResult":{"code":"pressed"}}]"""))
                }
            }
        })
        try {
            val tmp = TemporaryFolder().apply { create() }
            try {
                val dir = tmp.newFolder("task")
                File(dir, "task.json").writeText("{}")
                File(dir, "flow.yaml").writeText(sample)
                val binder = FakeBinder()
                runBlocking { provider(binder).start(spec(dir, entry = "flow.yaml")) }
                val finished = binder.events.last { (it["type"] as? JsonPrimitive)?.content == "finished" }
                assertEquals("SUCCEEDED", ((finished["data"] as JsonObject)["status"] as JsonPrimitive).content)
            } finally {
                scope.cancel()
                tmp.delete()
            }
        } finally {
            YamlParserPlug.resetForTests()
        }
    }

    @Test
    fun `subflows resolve by file name and stem, task json is excluded`() {
        val tmp = TemporaryFolder().apply { create() }
        try {
            val dir = tmp.newFolder("task")
            File(dir, "task.json").writeText("{}")
            File(dir, "flow.json").writeText("""{"schema":1,"steps":[{"setResult":{"code":"main"}}]}""")
            File(dir, "child.json").writeText("""{"schema":1,"steps":[{"setResult":{"code":"from-child"}}]}""")
            val main = FlowSources.loadFlowDocument(File(dir, "flow.json"))
            val subflows = FlowSources.loadSubflows(dir, File(dir, "flow.json"))
            assertTrue(subflows.containsKey("child") && subflows.containsKey("child.json"))
            assertTrue(!subflows.containsKey("task.json"))
            assertEquals(1, main.steps.size)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `run controls throw the typed cancel exception even while paused`() = runBlocking {
        val controls = RunControls()
        controls.requestPause()
        controls.requestCancel() // cancel wins over pause (unparks via notifyAll)
        try {
            controls.checkpoint()
            fail("expected FlowCancelledException")
        } catch (e: FlowCancelledException) {
            assertTrue(true)
        }
    }

    @Test
    fun `run controls release the pause on resume`() {
        val controls = RunControls()
        controls.requestPause()
        // A real thread, not a coroutine: checkpoint() blocks the calling thread, and delay() on a
        // TestDispatcher uses virtual time that never advances outside runTest (C-03).
        val resumer = Thread {
            Thread.sleep(100)
            controls.requestResume()
        }
        resumer.start()
        val began = System.nanoTime()
        controls.checkpoint() // parks ~100ms, then resumes without throwing
        val waitedMs = (System.nanoTime() - began) / 1_000_000
        resumer.join(2_000)
        assertTrue("expected the pause gate to hold ~100ms, took $waitedMs", waitedMs >= 50)
    }

    @Test
    fun `broker errors become BrokerCallException with the envelope code`() {
        val binder = FakeBinder().apply {
            failEnvelope = """{"ok":false,"error":{"code":"TIMEOUT","message":"busy","retryable":true}}"""
        }
        val broker = BinderFlowBroker("tok", binder, Dispatchers.Unconfined)
        runBlocking {
            try {
                broker.call("device.ui", "click", buildJsonObject { })
                fail("expected BrokerCallException")
            } catch (e: BrokerCallException) {
                assertEquals("TIMEOUT", e.code)
                assertEquals("busy", e.message)
                assertTrue(e.retryable)
            }
        }
    }

    @Test
    fun `scalar broker results are wrapped like the sim adapter`() {
        val binder = FakeBinder().apply {
            failEnvelope = """{"ok":true,"result":"hello"}"""
        }
        val broker = BinderFlowBroker("tok", binder, Dispatchers.Unconfined)
        val out = runBlocking { broker.call("device.ime", "clipboard_get", buildJsonObject { }) }
        assertEquals("hello", (out["value"] as JsonPrimitive).content)
    }

    // --- INT-B: values a script passes with tasks.run reach the flow through the names its env declares ---

    /** Runs [flow] once with [trigger] and returns the (title, text) its `notify` step sent to the broker. */
    private fun notifySeen(flow: String, trigger: TriggerInfo): Pair<String, String> {
        val tmp = TemporaryFolder().apply { create() }
        try {
            val dir = tmp.newFolder("task")
            File(dir, "flow.json").writeText(flow)
            val binder = FakeBinder()
            val handle = runBlocking { provider(binder).start(spec(dir).copy(trigger = trigger)) }
            val call = binder.calls.first { it.first == "notify.post" }
            handle.release()
            return (call.third["title"] as JsonPrimitive).content to (call.third["text"] as JsonPrimitive).content
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    private val titleFlow =
        """{"schema":1,"env":{"title":"default title"},"steps":[{"notify":{"title":"${'$'}{title}","text":"${'$'}{run.trigger}:${'$'}{run.args.other}"}}]}"""

    private fun chainWith(args: JsonObject) = TriggerInfo(type = "chain", payload = buildJsonObject { put("args", args) })

    @Test
    fun `a script's args reach the flow through the names its env declares`() {
        val (title, text) = notifySeen(
            titleFlow,
            chainWith(buildJsonObject { put("title", "Hello"); put("other", "x") }),
        )
        assertEquals("Hello", title)
        // An undeclared name is not a variable, but the flow can still read it as run.args.<name>.
        assertEquals("chain:x", text)
    }

    @Test
    fun `without args the flow keeps its own default`() {
        val (title, _) = notifySeen(titleFlow, TriggerInfo())
        assertEquals("default title", title)
    }

    @Test
    fun `an argument the flow did not declare never becomes a variable`() {
        val flow = """{"schema":1,"env":{"title":"default title"},"steps":[{"notify":{"title":"${'$'}{other}","text":"t"}}]}"""
        val (title, _) = notifySeen(flow, chainWith(buildJsonObject { put("other", "x") }))
        assertEquals("\${other}", title)
    }

    @Test
    fun `a number sent for a text variable still resolves as text`() {
        val (title, _) = notifySeen(titleFlow, chainWith(buildJsonObject { put("title", 42) }))
        assertEquals("42", title)
    }

    // --- INT-A: runTask starts another task through tasks.run and waits for it ---

    /** Runs [flow] once; returns what the broker saw and what the run finished with. */
    private fun runOnce(flow: String, configure: FakeBinder.() -> Unit = {}): Pair<FakeBinder, JsonObject> {
        val tmp = TemporaryFolder().apply { create() }
        try {
            val dir = tmp.newFolder("task")
            File(dir, "flow.json").writeText(flow)
            val binder = FakeBinder().apply(configure)
            val handle = runBlocking { provider(binder).start(spec(dir)) }
            val finished = binder.events.last { (it["type"] as? JsonPrimitive)?.content == "finished" }
            handle.release()
            return binder to (finished["data"] as JsonObject)
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    /** What the host answers `tasks.run`: the child's run id and terminal status, with the text it ended on. */
    private fun taskAnswer(status: String, result: String? = null, timedOut: Boolean = false) = buildJsonObject {
        put("run_id", "run-7")
        put("status", status)
        if (result != null) put("result", result)
        if (timedOut) put("await", "timeout")
    }

    private fun childResult(code: String, idea: String) = """{"code":"$code","data":{"idea":"$idea"}}"""

    private fun text(obj: JsonObject, key: String) = (obj[key] as JsonPrimitive).content

    private fun callsTo(binder: FakeBinder, capability: String) = binder.calls.filter { it.first == capability }.map { it.third }

    private val runOneTask = """{"schema":1,"steps":[{"runTask":{"task":"t"}}]}"""

    @Test
    fun `runTask sends the task, the resolved inputs and a one minute wait, and into feeds the next steps`() {
        val flow = """{"schema":1,"env":{"idea":"a cat"},"steps":[
            {"runTask":{"task":"gemini-copy-idea","args":{"prompt":"Make a video about ${'$'}{idea}","n":2},"into":"r"}},
            {"notify":{"title":"${'$'}{env.r.code}","text":"${'$'}{env.r.data.idea}|${'$'}{env.r.status}|${'$'}{env.r.run_id}"}}]}"""
        val (binder, outcome) = runOnce(flow) {
            answers["tasks.run.run"] = taskAnswer("SUCCEEDED", childResult("COPIED", "a cat on a skateboard"))
        }
        assertEquals("SUCCEEDED", text(outcome, "status"))
        val sent = callsTo(binder, "tasks.run").single()
        assertEquals("gemini-copy-idea", text(sent, "task"))
        assertEquals(buildJsonObject { put("prompt", "Make a video about a cat"); put("n", 2) }, sent["args"])
        assertEquals("60000", text(sent, "timeout_ms"))
        val note = callsTo(binder, "notify.post").single()
        assertEquals("COPIED", text(note, "title"))
        assertEquals("a cat on a skateboard|SUCCEEDED|run-7", text(note, "text"))
    }

    @Test
    fun `runTask without inputs sends none, and its timeout in seconds goes out in milliseconds`() {
        val (binder, outcome) = runOnce("""{"schema":1,"steps":[{"runTask":{"task":"t","timeout":5}}]}""") {
            answers["tasks.run.run"] = taskAnswer("SUCCEEDED")
        }
        assertEquals("SUCCEEDED", text(outcome, "status"))
        val sent = callsTo(binder, "tasks.run").single()
        assertNull(sent["args"])
        assertEquals("5000", text(sent, "timeout_ms"))
    }

    @Test
    fun `a plain text answer lands in text with code and data left empty`() {
        val flow = """{"schema":1,"steps":[
            {"runTask":{"task":"t","into":"r"}},
            {"notify":{"title":"[${'$'}{env.r.code}]","text":"${'$'}{env.r.text}|${'$'}{env.r.data}"}}]}"""
        val (binder, _) = runOnce(flow) { answers["tasks.run.run"] = taskAnswer("SUCCEEDED", "just text") }
        val note = callsTo(binder, "notify.post").single()
        assertEquals("[]", text(note, "title"))
        assertEquals("just text|{}", text(note, "text"))
    }

    @Test
    fun `no answer at all still succeeds with every field empty`() {
        val flow = """{"schema":1,"steps":[
            {"runTask":{"task":"t","into":"r"}},
            {"notify":{"title":"${'$'}{env.r.status}","text":"[${'$'}{env.r.code}][${'$'}{env.r.text}]${'$'}{env.r.data}"}}]}"""
        val (binder, outcome) = runOnce(flow) { answers["tasks.run.run"] = taskAnswer("SUCCEEDED") }
        assertEquals("SUCCEEDED", text(outcome, "status"))
        val note = callsTo(binder, "notify.post").single()
        assertEquals("SUCCEEDED", text(note, "title"))
        assertEquals("[][]{}", text(note, "text"))
    }

    @Test
    fun `a task still running at the deadline fails the step with a retryable TIMEOUT`() {
        val (binder, outcome) = runOnce(runOneTask) { answers["tasks.run.run"] = taskAnswer("RUNNING", timedOut = true) }
        assertEquals("FAILED", text(outcome, "status"))
        assertEquals("TIMEOUT", text(outcome, "reason"))
        val evidence = binder.events.first { (it["type"] as? JsonPrimitive)?.content == "evidence" }["data"] as JsonObject
        assertEquals("true", text(evidence, "retryable"))
    }

    @Test
    fun `a cancelled task fails the step as CANCELLED`() {
        val (_, outcome) = runOnce(runOneTask) { answers["tasks.run.run"] = taskAnswer("CANCELLED") }
        assertEquals("FAILED", text(outcome, "status"))
        assertEquals("CANCELLED", text(outcome, "reason"))
    }

    @Test
    fun `a task that failed fails the step as INTERNAL and names its status`() {
        val (_, outcome) = runOnce(runOneTask) { answers["tasks.run.run"] = taskAnswer("FAILED") }
        assertEquals("FAILED", text(outcome, "status"))
        assertEquals("INTERNAL", text(outcome, "reason"))
        assertTrue(text(outcome, "message").contains("ended FAILED"))
    }

    @Test
    fun `an optional runTask that fails still leaves its answer to inspect`() {
        val flow = """{"schema":1,"steps":[
            {"runTask":{"task":"t","into":"r","optional":true}},
            {"notify":{"title":"${'$'}{env.r.status}","text":"x"}}]}"""
        val (binder, outcome) = runOnce(flow) { answers["tasks.run.run"] = taskAnswer("FAILED") }
        assertEquals("SUCCEEDED", text(outcome, "status"))
        assertEquals("FAILED", text(callsTo(binder, "notify.post").single(), "title"))
    }

    @Test
    fun `a refusal from the host keeps its own code`() {
        val (_, outcome) = runOnce(runOneTask) {
            refusals["tasks.run.run"] = """{"ok":false,"error":{"code":"SCOPE_VIOLATION","message":"not in scope","retryable":false}}"""
        }
        assertEquals("FAILED", text(outcome, "status"))
        assertEquals("SCOPE_VIOLATION", text(outcome, "reason"))
    }

    @Test
    fun `an input that resolves to an object is refused before the call`() {
        val flow = """{"schema":1,"steps":[
            {"runTask":{"task":"first","into":"r"}},
            {"runTask":{"task":"second","args":{"x":"${'$'}{env.r.data}"}}}]}"""
        val (binder, outcome) = runOnce(flow) { answers["tasks.run.run"] = taskAnswer("SUCCEEDED", childResult("A", "v")) }
        assertEquals("INVALID_ARGS", text(outcome, "reason"))
        assertEquals(listOf("first"), callsTo(binder, "tasks.run").map { text(it, "task") })
    }
}
