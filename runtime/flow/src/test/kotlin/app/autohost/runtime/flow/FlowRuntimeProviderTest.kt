package app.autohost.runtime.flow

import app.autohost.broker.ICapabilityBroker
import app.autohost.core.common.AhJson
import app.autohost.core.common.AppDispatchers
import app.autohost.core.model.Envelope
import app.autohost.core.model.RunSpec
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P6-06 (written; awaiting Gradle): the provider contract — entry resolution, typed start
 * failures, in-process execution through a fake binder, plug-gated YAML, subflow registration,
 * and run-control semantics. The binder is a fake `ICapabilityBroker` mirroring the AIDL proxy
 * (call → envelope JSON) without Android.
 */
class FlowRuntimeProviderTest {

    private val dispatchers = AppDispatchers(
        main = UnconfinedTestDispatcher(),
        io = Dispatchers.Unconfined,
        default = UnconfinedTestDispatcher(),
    )
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    /** Pure-JVM stand-in for the AIDL proxy: same call/emit surface, envelope semantics included. */
    private class FakeBinder : ICapabilityBroker {
        val calls = mutableListOf<Triple<String, String, JsonObject>>()
        val events = mutableListOf<JsonObject>()
        var failEnvelope: String? = null

        override fun call(runToken: String?, capability: String?, method: String?, argsJson: String?): String {
            failEnvelope?.let { return it }
            val args = AhJson.json.parseToJsonElement(argsJson.orEmpty()) as? JsonObject ?: JsonObject(emptyMap())
            calls.add(Triple(capability ?: "", method ?: "", args))
            return AhJson.json.encodeToString(
                Envelope.serializer(),
                Envelope.ok(buildJsonObject { put("ok", true) }),
            )
        }

        override fun emit(runToken: String?, eventJson: String?) {
            events.add(
                (AhJson.json.parseToJsonElement(eventJson ?: "{}") as? JsonObject) ?: JsonObject(emptyMap()),
            )
        }

        override fun describe(runToken: String?): String = "{}"
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
            File(dir, "flow.json").writeText("""{"steps":[{"setResult":{"code":"pressed"}}]}""")
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
            File(dir, "flow.json").writeText("""{"steps":[{"noSuchCommand":{}}]}""")
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
        val sample = "steps:\n  - setResult:\n      code: pressed\n"
        YamlParserPlug.install(object : YamlParserPlug {
            override fun parseToJsonObject(text: String): JsonObject {
                assertEquals(sample, text)
                return buildJsonObject {
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
            File(dir, "flow.json").writeText("""{"steps":[{"setResult":{"code":"main"}}]}""")
            File(dir, "child.json").writeText("""{"steps":[{"setResult":{"code":"from-child"}}]}""")
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
    fun `run controls release the pause on resume`() = runBlocking {
        val controls = RunControls()
        controls.requestPause()
        scope.launch {
            kotlinx.coroutines.delay(100) // let checkpoint() park first
            controls.requestResume()
        }
        val began = System.nanoTime()
        controls.checkpoint() // parks ~100ms, then resumes without throwing
        val waitedMs = (System.nanoTime() - began) / 1_000_000
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
}
