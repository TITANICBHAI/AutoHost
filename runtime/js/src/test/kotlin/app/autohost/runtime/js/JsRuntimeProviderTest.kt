package app.autohost.runtime.js

import app.autohost.core.common.AhJson
import app.autohost.core.common.AppDispatchers
import app.autohost.core.common.Clock
import app.autohost.core.model.Envelope
import app.autohost.core.model.RunLimits
import app.autohost.core.model.RunSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P11-01 (written; awaiting Gradle): the provider lifecycle with a scripted fake engine —
 * in-process start, the finished-event contract, worker-death on engine crash, the interrupt
 * wiring (cancel drives the engine check), the caps/policy handoff, and bridge reachability
 * from the engine. The engine here is a fake; the real QuickJS binding is owner-verified at
 * the touchpoint (QuickJsEngineFactory).
 */
class JsRuntimeProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatchers = AppDispatchers(
        main = UnconfinedTestDispatcher(),
        io = Dispatchers.Unconfined,
        default = UnconfinedTestDispatcher(),
    )
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    /** Records everything the provider hands to the engine; the outcome is scripted per test. */
    private class ScriptedEngine(private val script: (ScriptedEngine) -> String) : JsEngine {
        val bridges = mutableListOf<(String, String) -> String>()
        val limits = mutableListOf<Pair<Long, Long>>()
        val interruptChecks = mutableListOf<() -> Boolean>()
        var closed = false
        var entryName: String? = null

        override fun installBridge(bridge: (op: String, payload: String) -> String) {
            bridges.add(bridge)
        }

        override fun setLimits(maxMemoryBytes: Long, maxStackBytes: Long) {
            limits.add(maxMemoryBytes to maxStackBytes)
        }

        override fun setInterruptHandler(check: () -> Boolean) {
            interruptChecks.add(check)
        }

        override fun runEntry(
            preludeSource: String,
            entrySource: String,
            entryName: String,
            moduleLoader: ((String) -> String?)?,
        ): String {
            this.entryName = entryName
            assertTrue("prelude must be non-empty", preludeSource.contains("__autohost_run_main"))
            return script(this)
        }

        override fun close() {
            closed = true
        }
    }

    private class RecordingCaller : JsBrokerCaller {
        val events = mutableListOf<Pair<String, JsonObject>>()

        override fun call(runToken: String, capability: String, method: String, argsJson: String): String =
            AhJson.json.encodeToString(
                Envelope.serializer(),
                Envelope.ok(buildJsonObject { put("ok", true) }),
            )

        override fun describe(runToken: String): String = "{}"

        override fun emit(runToken: String, eventJson: String) {
            events.add(runToken to (AhJson.json.parseToJsonElement(eventJson).jsonObject))
        }
    }

    private class FixedClock : Clock {
        override fun nowMs(): Long = 1_793_000_000_000
        override fun monotonicMs(): Long = 100_000
    }

    private fun taskDir(): File {
        val dir = tmp.newFolder()
        File(dir, "task.json").writeText("{}")
        File(dir, "main.js").writeText("export function main() { return autohost.ctx.result('ok'); }")
        return dir
    }

    private fun spec(dir: File) = RunSpec(
        runId = "run-js-1", taskId = "t", runtime = "javascript",
        entry = "main.js", taskPath = dir.absolutePath, runToken = "tok",
        limits = RunLimits(timeoutMinutes = 5),
    )

    private fun provider(engine: JsEngine, caller: RecordingCaller = RecordingCaller()): JsRuntimeProvider =
        JsRuntimeProvider(
            engineFactory = { engine },
            caller = caller,
            dispatchers = dispatchers,
            scope = scope,
            clock = FixedClock(),
            secretOf = { _ -> null },
            redact = { it },
            heartbeatIntervalMs = 60_000,
        )

    private fun finished(caller: RecordingCaller): JsonObject {
        val event = checkNotNull(caller.events.lastOrNull {
            (it.second)["type"]?.let { t -> (t as? JsonPrimitive)?.content } == "finished"
        }) { "no finished event among ${caller.events.size}" }
        return event.second["data"]!!.jsonObject
    }

    /** The engine runs on its own thread: tests await the finished (or death) signal. */
    private fun awaitFinished(caller: RecordingCaller, seconds: Long = 2): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (
            caller.events.none {
                (it.second)["type"]?.let { t -> (t as? JsonPrimitive)?.content } == "finished"
            } && System.nanoTime() < deadline
        ) {
            Thread.sleep(5)
        }
        assertTrue(
            "run never finished",
            caller.events.any {
                (it.second)["type"]?.let { t -> (t as? JsonPrimitive)?.content } == "finished"
            },
        )
        return finished(caller)
    }

    private fun awaitDeath(captured: Array<String?>, seconds: Long = 2) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (captured[0] == null && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertTrue("death was never reported", captured[0] != null)
    }

    private fun str(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun `happy path emits the finished event with the classified outcome`() {
        val engine = ScriptedEngine {
            """{"status":"ok","value":{"__result__":{"code":"pressed","data":{}}}}"""
        }
        val caller = RecordingCaller()
        val handle = runBlocking { provider(engine, caller).start(spec(taskDir())) }
        val outcome = awaitFinished(caller)
        assertEquals("SUCCEEDED", str(outcome, "status"))
        assertEquals("pressed", str(outcome["result"]!!.jsonObject, "code"))
        assertEquals("main.js", engine.entryName)
        assertEquals(
            "caps come from the policy",
            JsRunPolicy.caps(lowRam = false, timeoutMinutes = 5).maxMemoryBytes,
            engine.limits.single().first,
        )
        handle.release()
        assertTrue(engine.closed)
    }

    @Test
    fun `missing entry fails start with a typed message`() {
        val dir = tmp.newFolder("empty-task")
        File(dir, "task.json").writeText("{}")
        try {
            runBlocking { provider(ScriptedEngine { "{}" }).start(spec(dir)) }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("no JS entry"))
        }
    }

    @Test
    fun `an engine crash is worker death, never a finished event`() {
        val engine = ScriptedEngine { throw IllegalStateException("engine exploded") }
        val caller = RecordingCaller()
        val death = arrayOf<String?>(null)
        val p = provider(engine, caller)
        p.setDeathListener { death[0] = it }
        runBlocking { p.start(spec(taskDir())) }
        awaitDeath(death)
        assertEquals("run-js-1", death[0])
        assertTrue(caller.events.none {
            (it.second)["type"]?.let { t -> (t as? JsonPrimitive)?.content } == "finished"
        })
    }

    @Test
    fun `cancelled and needs_user outcomes classify through the finished event`() {
        val cancelled = ScriptedEngine {
            """{"status":"error","error":{"kind":"cancelled","message":"run was cancelled"}}"""
        }
        val caller1 = RecordingCaller()
        runBlocking { provider(cancelled, caller1).start(spec(taskDir())) }
        assertEquals("CANCELLED", str(awaitFinished(caller1), "status"))

        val waiting = ScriptedEngine {
            """{"status":"error","error":{"kind":"needs_user","reason":"SECURITY_CHALLENGE","message":"captcha","resume_hint":"solve"}}"""
        }
        val caller2 = RecordingCaller()
        runBlocking { provider(waiting, caller2).start(spec(taskDir())) }
        val outcome = awaitFinished(caller2)
        assertEquals("WAITING_USER", str(outcome, "status"))
        assertEquals("solve", str(outcome, "resume_hint"))
    }

    @Test
    fun `requestCancel flips the engine interrupt check and the run cancels`() {
        val engine = ScriptedEngine { scripted ->
            // Block until the interrupt check flips, then report the cancelled outcome — the
            // real QuickJS engine unwinds the same way (interrupt handler, ADR-076).
            val check = scripted.interruptChecks.single()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!check() && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            if (check()) {
                """{"status":"error","error":{"kind":"cancelled","message":"run was cancelled"}}"""
            } else {
                """{"status":"ok","value":null}"""
            }
        }
        val caller = RecordingCaller()
        val handle = runBlocking { provider(engine, caller).start(spec(taskDir())) }
        handle.requestCancel()
        assertEquals("CANCELLED", str(awaitFinished(caller), "status"))
        handle.release()
    }

    @Test
    fun `the engine reaches the bridge and the op table answers`() {
        val caller = RecordingCaller()
        val engine = ScriptedEngine { scripted ->
            val bridgeFn = scripted.bridges.single()
            val frame = AhJson.json.encodeToString(JsonArray.serializer(), JsonArray(listOf(JsonPrimitive("clock"), JsonPrimitive("null"))))
            val reply = AhJson.json.parseToJsonElement(bridgeFn("clock", frame)).jsonObject
            val now = ((reply["result"] as JsonObject)["now_iso"] as JsonPrimitive).content
            val value = buildJsonObject { put("now", now) }
            AhJson.json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { put("status", "ok"); put("value", value) },
            )
        }
        runBlocking { provider(engine, caller).start(spec(taskDir())) }
        val value = awaitFinished(caller)["result"]!!.jsonObject
        assertEquals("2026-10-26T07:33:20Z", str(value, "now"))
    }
}
