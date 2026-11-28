package app.autohost.agent

import android.os.IBinder
import app.autohost.broker.ICapabilityBroker
import app.autohost.core.common.AhJson
import app.autohost.core.common.AppDispatchers
import app.autohost.core.common.SystemClockImpl
import app.autohost.core.llm.BaseUrlValidator
import app.autohost.core.llm.ProfileStore
import app.autohost.core.llm.ProviderProfile
import app.autohost.core.model.Bounds
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import app.autohost.core.model.RunSpec
import app.autohost.core.model.UiNode
import app.autohost.runtime.agent.AgentLoop
import app.autohost.runtime.agent.AppSensitivity
import app.autohost.runtime.agent.ToolPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * INT-A11: the agent's REAL wiring, end to end on the JVM.
 *
 * Every seam bug found so far (A2 to A13) hid behind a test double that spoke the shape the code wanted instead of the
 * shape the other side really speaks. Here nothing between the model and the phone is a double: the real
 * [AgentLoopFactory], [BinderAgentTransport], tool pipeline, completion check, [app.autohost.core.llm.LlmGateway], chat
 * codec, HTTP engine and the production clock. The only things played are the two ends:
 *
 *  - the MODEL: a small HTTP server on the loopback address that answers like a Gemini 3 endpoint on its OpenAI-compatible
 *    path (the first tool call carries `extra_content.google.thought_signature`, which must come back on the next turn);
 *  - the PHONE: a broker that answers with the real node summary and the real answer shapes (`keyboard` a Boolean, the
 *    provider's fields at the top of the result, a choice prompt answering `{answered, choice}`).
 *
 * A failing assertion carries the outcome, the phone calls and the model requests, so one CI round shows what broke.
 */
class AgentWiringEndToEndTest {

    private val pkg = "com.example.video"
    private val apiKey = "test-key-CANARY-4711"

    private class Seen(val authorization: String?, val body: String)

    private class Call(val runToken: String, val name: String, val args: JsonObject)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val events = CopyOnWriteArrayList<String>()
    private var server: ServerSocket? = null
    private val taskDir: File = Files.createTempDirectory("autohost-e2e").toFile()

    @After
    fun tearDown() {
        server?.close()
        taskDir.deleteRecursively()
    }

    // -- the two played ends ---------------------------------------------------------------------

    /**
     * A model that answers the given bodies in turn (the last one again if asked more often); returns its port. Plain
     * sockets, one request at a time: the `app` module's test code is compiled against the Android platform, which has
     * no `com.sun.net.httpserver` (the first try of this test failed to compile for exactly that reason).
     */
    private fun startModel(vararg replies: String): Int {
        val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = listener
        thread(isDaemon = true, name = "fake-model") {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { answer(it, replies) }
                } catch (e: IOException) {
                    // the test closed the listener, or a client went away: nothing left to serve
                }
            }
        }
        return listener.localPort
    }

    /** One HTTP/1.1 exchange: read the request head and its Content-Length body, record both, send the next reply, close. */
    private fun answer(client: Socket, replies: Array<out String>) {
        client.soTimeout = 15_000
        val input = client.getInputStream()
        val head = ByteArrayOutputStream()
        var lastFour = 0
        while (lastFour != END_OF_HEAD) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
            lastFour = (lastFour shl 8) or b
        }
        val lines = head.toString("UTF-8").split("\r\n")
        fun header(name: String): String? =
            lines.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val length = header("Content-Length")?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(body, got, length - got)
            if (n < 0) return
            got += n
        }
        seen.add(Seen(header("Authorization"), String(body, Charsets.UTF_8)))
        val reply = replies[minOf(seen.size, replies.size) - 1].toByteArray()
        val out = client.getOutputStream()
        out.write(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(),
        )
        out.write(reply)
        out.flush()
    }

    /** The phone service: [approval] is the button the person presses on the approval question. */
    private inner class FakePhone(private val approval: String = "Allow once") : ICapabilityBroker {
        val calls = CopyOnWriteArrayList<Call>()

        override fun asBinder(): IBinder? = null

        override fun call(runToken: String, capability: String, method: String, argsJson: String): String {
            val args = Json.parseToJsonElement(argsJson).jsonObject
            val name = "$capability.$method"
            calls.add(Call(runToken, name, args))
            val result: JsonObject = when (name) {
                "device.app.foreground" -> buildJsonObject { put("package", pkg); put("activity", ".Main") }
                "device.ui.snapshot" -> screen()
                "device.ui.click" -> buildJsonObject { put("clicked", true) }
                "ui.prompt.ask" -> when (args["kind"]?.jsonPrimitive?.content) {
                    "confirm" -> buildJsonObject { put("answered", true); put("confirmed", true) }
                    "choice" -> buildJsonObject { put("answered", true); put("choice", approval) }
                    else -> return refused("a prompt kind the test does not play")
                }
                else -> return refused("a call the test does not play: $name")
            }
            return AhJson.json.encodeToString(Envelope.serializer(), Envelope.ok(result))
        }

        override fun emit(runToken: String, eventJson: String) = Unit

        override fun describe(runToken: String): String = "{}"

        private fun refused(message: String): String =
            AhJson.json.encodeToString(Envelope.serializer(), Envelope.err(ErrorCode.INVALID_ARGS, message))
    }

    /** One screen as the phone service really answers: the real node summary, `keyboard` a Boolean, fields at the top. */
    private fun screen(): JsonObject {
        val button = UiNode(
            index = 0, parent = -1, depth = 0, pkg = pkg, cls = "android.widget.Button", id = "go", text = "Go",
            desc = "", bounds = Bounds(40, 812, 1040, 940), flags = setOf("clickable"),
        )
        return buildJsonObject {
            put("ts", 1L)
            put("package", pkg)
            put("keyboard", false)
            put("node_count", 1)
            put("empty", false)
            put("degraded", false)
            putJsonArray("nodes") { add(button.summary("ref-1")) }
            put("compact", "")
        }
    }

    // -- the real wiring -------------------------------------------------------------------------

    private fun profiles(port: Int): ProfileStore = object : ProfileStore {
        private val profile = ProviderProfile(
            id = "gem", label = "Gemini", baseUrl = "http://127.0.0.1:$port/v1", model = "gemini-3-test",
            egressAcceptedAtMs = 1L,
        )

        override fun load(): List<ProviderProfile> = listOf(profile)
        override fun save(profiles: List<ProviderProfile>) = Unit
        override fun readApiKey(profileId: String): String? = if (profileId == "gem") apiKey else null
        override fun registerKeyForRedaction(value: String) = Unit
    }

    private fun spec(): RunSpec = RunSpec(
        runId = "run-1", taskId = "builtin.agent.adhoc", runtime = "agent", entry = "agent",
        taskPath = taskDir.absolutePath, runToken = "tok-1",
        config = Json.parseToJsonElement("""{"agent":{"goal":"Press Go","packages":["$pkg"],"provider":"gem"}}""").jsonObject,
    )

    private suspend fun run(phone: FakePhone, port: Int): AgentLoop.Outcome = withTimeout(60_000) {
        val factory = AgentLoopFactory(
            binder = phone,
            profileStore = profiles(port),
            dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.IO, Dispatchers.Default),
            clock = SystemClockImpl(),
            sensitivity = AppSensitivity(),
            urlEnv = { BaseUrlValidator.UrlEnv(cleartextLoopbackEnabled = true, debugBuild = false) },
            screenMetrics = { 1080 to 2400 },
            taskRuns = { _, _ -> ToolPipeline.TaskRuns.Outcome.Gone },
        )
        val sink = AgentLoop.Sink { kind, data -> events.add("$kind $data") }
        factory.create(spec(), sink, AgentLoop.PauseGate { }).run("Press Go")
    }

    private fun shown(outcome: AgentLoop.Outcome, phone: FakePhone): String =
        "outcome=$outcome | phone=${phone.calls.joinToString(" ") { it.name }} | model requests=${seen.size} | " +
            "events=${events.joinToString(" ") { it.substringBefore(' ') }} | last request ends: ${seen.lastOrNull()?.body?.takeLast(400)}"

    private fun messagesOf(requestBody: String): List<JsonObject> =
        Json.parseToJsonElement(requestBody).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }

    // -- the cases -------------------------------------------------------------------------------

    @Test
    fun `given a model that clicks and then finishes when the agent runs then the real chain succeeds and every seam holds`() = runBlocking {
        val port = startModel(CLICK_REPLY, FINISH_REPLY)
        val phone = FakePhone()
        val outcome = run(phone, port)
        val why = shown(outcome, phone)

        val done = outcome as? AgentLoop.Outcome.Succeeded ?: throw AssertionError(why)
        assertEquals(why, "Pressed Go", done.claimedSummary)
        assertEquals(why, 2, done.turns)

        // the model side: what went over the wire (A12: the profile's model; A13: the call was made at all)
        assertEquals(why, 2, seen.size)
        assertTrue(why, seen.all { it.authorization == "Bearer $apiKey" })
        val first = Json.parseToJsonElement(seen[0].body).jsonObject
        assertEquals(why, "gemini-3-test", first["model"]!!.jsonPrimitive.content)
        val toolNames = first["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(why + " tools=$toolNames", "ui_click" in toolNames && "finish" in toolNames && "ask_user" in toolNames)
        assertFalse(why + " tools=$toolNames", "run_task" in toolNames || "task_status" in toolNames)
        assertTrue(why, messagesOf(seen[0].body).first()["role"]!!.jsonPrimitive.content == "system")

        // A10: the signature the model put on its call comes back on the next turn, and the click's result with it
        val second = messagesOf(seen[1].body)
        val calls = second.filter { it["role"]?.jsonPrimitive?.content == "assistant" }
            .flatMap { (it["tool_calls"] as? JsonArray).orEmpty() }
        assertEquals(why, 1, calls.size)
        val signature = calls.single().jsonObject["extra_content"]?.jsonObject?.get("google")?.jsonObject
            ?.get("thought_signature")?.jsonPrimitive?.content
        assertEquals(why, "SIG-ONE", signature)
        assertTrue(why, second.any { it["role"]?.jsonPrimitive?.content == "tool" && it["tool_call_id"]?.jsonPrimitive?.content == "call-1" })

        // the phone side: the approval came first, the click carries the phone's own handle (A4), every call the run token
        val names = phone.calls.map { it.name }
        assertTrue(why, phone.calls.all { it.runToken == "tok-1" })
        assertTrue(why, names.indexOf("ui.prompt.ask") in 0 until names.indexOf("device.ui.click"))
        val click = phone.calls.single { it.name == "device.ui.click" }
        assertEquals(why, "ref-1", click.args["ref"]?.jsonPrimitive?.content)
        assertFalse(why, click.args.containsKey("id"))
        val kinds = phone.calls.filter { it.name == "ui.prompt.ask" }.map { it.args["kind"]!!.jsonPrimitive.content }
        assertEquals(why, listOf("choice", "confirm"), kinds)

        // the completion check wrote its evidence, and the key went nowhere but the Authorization header
        assertEquals(why, 1, File(taskDir, "evidence").listFiles().orEmpty().size)
        assertTrue(why, events.none { it.contains(apiKey) })
        assertTrue(why, phone.calls.none { it.args.toString().contains(apiKey) })
        assertTrue(why, seen.none { it.body.contains(apiKey) })
    }

    @Test
    fun `given the person denies the approval when the agent runs then the click never reaches the phone and the model is told`() = runBlocking {
        val port = startModel(CLICK_REPLY, FINISH_REPLY)
        val phone = FakePhone(approval = "Deny")
        val outcome = run(phone, port)
        val why = shown(outcome, phone)

        assertTrue(why, outcome is AgentLoop.Outcome.Succeeded)
        assertTrue(why, phone.calls.none { it.name == "device.ui.click" })
        assertEquals(why, 2, seen.size)
        assertTrue(why, seen[1].body.contains("APPROVAL_DENIED"))
    }

    private companion object {
        /** The bytes `\r\n\r\n` that end an HTTP request head, as the last four bytes read. */
        const val END_OF_HEAD = 0x0D0A0D0A

        /** Turn 1: a Gemini 3 style tool call; the signature sits on the call, under `extra_content.google`. */
        val CLICK_REPLY = """{"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","extra_content":{"google":{"thought_signature":"SIG-ONE"}},"function":{"name":"ui_click","arguments":"{\"id\":\"1\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":120,"completion_tokens":8,"total_tokens":128}}"""

        /** Turn 2: the model says it is done. */
        val FINISH_REPLY = """{"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-2","type":"function","function":{"name":"finish","arguments":"{\"status\":\"done\",\"summary\":\"Pressed Go\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":200,"completion_tokens":9,"total_tokens":209}}"""
    }
}
