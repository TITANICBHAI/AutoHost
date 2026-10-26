package app.autohost.core.devapi

import app.autohost.core.common.AhJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcTest {

    @Test
    fun `parses a valid request`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","id":7,"method":"system.ping","params":{"a":1}}""")
        val request = (parsed as JsonRpc.Either.Left).value
        assertEquals("system.ping", request.method)
        assertEquals(7L, request.id)
        assertEquals("1", (request.params["a"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `bad json and missing method become typed failures`() {
        val bad = (JsonRpc.parse("nope") as JsonRpc.Either.Right).value as JsonRpc.Response.Failure
        assertEquals("PARSE_ERROR", bad.code)
        val noMethod = (JsonRpc.parse("""{"jsonrpc":"2.0","id":1}""") as JsonRpc.Either.Right).value as JsonRpc.Response.Failure
        assertEquals("INVALID_REQUEST", noMethod.code)
        val wrongVersion = (JsonRpc.parse("""{"jsonrpc":"1.0","id":1,"method":"m"}""") as JsonRpc.Either.Right).value as JsonRpc.Response.Failure
        assertEquals("INVALID_REQUEST", wrongVersion.code)
    }

    @Test
    fun `success and failure envelopes round-trip`() {
        val ok = JsonRpc.Response.Success(1L, buildJsonObject { put("ok", true) })
        val json = ok.toJson().toString()
        assertTrue(json.contains("\"result\""))
        val fail = JsonRpc.Response.Failure(2L, "SCOPE_VIOLATION", "outside granted packages")
        val failJson = fail.toJson().toString()
        assertTrue(failJson.contains("SCOPE_VIOLATION"))
        assertTrue(failJson.contains("-32000"))
    }

    @Test
    fun `token store issues, matches and revokes; compares in constant time`() {
        val tokens = TokenStore()
        val token = tokens.issue()
        assertEquals(64, token.length)
        assertTrue(tokens.matches(token))
        assertTrue(tokens.matches(" $token".trim()))
        assertFalse(tokens.matches(token.drop(1)))
        assertFalse(tokens.matches(null))
        tokens.revoke()
        assertFalse(tokens.matches(token))
    }

    @Test
    fun `rate limiter allows the limit and rejects beyond it`() {
        val limiter = RateLimiter(limit = 3, windowMs = 1000)
        assertTrue(limiter.allow(0))
        assertTrue(limiter.allow(1))
        assertTrue(limiter.allow(2))
        assertFalse(limiter.allow(3))
        assertTrue(limiter.allow(1200)) // window slid
    }
}

class RpcRouterTest {

    open class FakeBackend : DevApiBackend {
        var lastCall: Triple<String, String, JsonObject>? = null
        override fun systemInfo(): JsonObject = buildJsonObject {
            put("app", "app.autohost")
            put("flavor", "core")
            put("api", 29)
        }

        override fun capabilities(): JsonObject = buildJsonObject { put("capabilities", true) }
        override fun call(capability: String, method: String, args: JsonObject): JsonObject {
            lastCall = Triple(capability, method, args)
            return buildJsonObject { put("capability", capability); put("method", method) }
        }

        override fun tasksList(): JsonArray = JsonArray(emptyList())
        override fun tasksGet(taskId: String): JsonObject? = null
        override fun tasksRemove(taskId: String, keepHistory: Boolean): JsonObject = buildJsonObject { put("removed", true) }
        override fun tasksSetConfig(taskId: String, values: JsonObject): JsonObject = buildJsonObject { put("saved", true) }
        override fun runsStart(taskId: String, profileId: String?, args: JsonObject?): JsonObject = buildJsonObject { put("runId", "run-1") }
        override fun runsList(): JsonArray = JsonArray(emptyList())
        override fun runsStatus(runId: String): JsonObject? = buildJsonObject { put("runId", runId); put("status", "RUNNING") }
        override fun runsTail(runId: String, sinceSeq: Long): JsonArray = JsonArray(emptyList())
        override fun runsPause(runId: String): JsonObject = buildJsonObject { put("paused", true) }
        override fun runsResume(runId: String): JsonObject = buildJsonObject { put("resumed", true) }
        override fun runsCancel(runId: String): JsonObject = buildJsonObject { put("cancelled", true) }
        override fun healthRun(only: List<String>?): JsonObject = buildJsonObject { put("groups", 4) }

        // P5-11 full surface
        override fun triggersList(): JsonArray = JsonArray(emptyList())
        override fun triggersStatus(): JsonArray = JsonArray(emptyList())
        override fun triggersCatalog(): JsonObject = buildJsonObject { put("sources", JsonArray(emptyList())) }
        override fun triggerUpsert(rule: JsonObject): JsonObject = buildJsonObject { put("id", "r1") }
        override fun triggerDelete(ruleId: String): JsonObject = buildJsonObject { put("deleted", true) }
        override fun triggerTest(rule: JsonObject, count: Int, fire: Boolean): JsonArray = JsonArray(emptyList())
        override fun healthChecks(): JsonArray = JsonArray(emptyList())
        override fun runsExport(runId: String): JsonObject? = buildJsonObject { put("runId", runId); put("bundle_b64", "AA==") }
        override fun recorderStart(): JsonObject = buildJsonObject { put("__error", buildJsonObject { put("code", "NOT_IMPLEMENTED") }) }
        override fun recorderStop(): JsonObject = buildJsonObject { put("__error", buildJsonObject { put("code", "NOT_IMPLEMENTED") }) }
    }

    private val router = RpcRouter()
    private val backend = FakeBackend()

    private fun request(method: String, params: String = "{}"): JsonRpc.Request =
        (JsonRpc.parse("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""") as JsonRpc.Either.Left).value

    @Test
    fun `ping and system info route to the backend`() {
        val ping = RpcPostProcess.finalize(router.route(request("system.ping"), backend))
        assertTrue(ping is JsonRpc.Response.Success)
        val info = RpcPostProcess.finalize(router.route(request("system.info"), backend))
        assertEquals("app.autohost", (info as JsonRpc.Response.Success).result["app"]!!.toString().trim('"'))
    }

    @Test
    fun `unknown methods are METHOD_NOT_FOUND`() {
        val response = RpcPostProcess.finalize(router.route(request("device.screenshot"), backend))
        assertTrue(response is JsonRpc.Response.Failure)
        assertEquals("METHOD_NOT_FOUND", (response as JsonRpc.Response.Failure).code)
    }

    @Test
    fun `missing arguments produce INVALID_ARGS envelopes`() {
        val noSelector = RpcPostProcess.finalize(router.route(request("device.ui.find"), backend))
        assertEquals("INVALID_ARGS", (noSelector as JsonRpc.Response.Failure).code)
        val noTask = RpcPostProcess.finalize(router.route(request("runs.start"), backend))
        assertEquals("INVALID_ARGS", (noTask as JsonRpc.Response.Failure).code)
    }

    @Test
    fun `device calls delegate to the backend with capability and method`() {
        router.route(request("device.ui.click", """{"selector":{"text":"Continue"}}"""), backend)
        assertEquals(Triple("device.ui", "click", AhJson.json.parseToJsonElement("""{"selector":{"text":"Continue"}}""")), backend.lastCall)
        val launch = RpcPostProcess.finalize(router.route(request("device.app.launch", """{"package":"com.a"}"""), backend))
        assertTrue((launch as JsonRpc.Response.Success).result.toString().contains("device.app"))
    }

    @Test
    fun `method set matches docs/DEVAPI (v0 + P5 full surface)`() {
        val expected = setOf(
            "system.ping", "system.info", "system.capabilities",
            "health.run", "health.checks",
            "device.ui.snapshot", "device.ui.find", "device.ui.click", "device.ui.set_text", "device.ui.press", "device.ui.wait_for",
            "device.app.launch", "device.app.foreground", "device.screenshot",
            "tasks.list", "tasks.get", "tasks.remove", "tasks.set_config",
            "runs.start", "runs.list", "runs.status", "runs.tail", "runs.pause", "runs.resume", "runs.cancel", "runs.export",
            "triggers.list", "triggers.status", "triggers.upsert", "triggers.delete", "triggers.test",
            "recorder.start", "recorder.stop",
        )
        assertEquals(expected, router.methods())
    }

    @Test
    fun `P5 routes map onto the backend with typed errors`() {
        val exported = RpcPostProcess.finalize(router.route(request("runs.export", """{"id":"run-9"}"""), backend))
        assertEquals("run-9", ((exported as JsonRpc.Response.Success).result["runId"] as kotlinx.serialization.json.JsonPrimitive).content)
        val noId = RpcPostProcess.finalize(router.route(request("runs.export"), backend))
        assertEquals("INVALID_ARGS", (noId as JsonRpc.Response.Failure).code)
        val noRule = RpcPostProcess.finalize(router.route(request("triggers.upsert"), backend))
        assertEquals("INVALID_ARGS", (noRule as JsonRpc.Response.Failure).code)
        val upsert = RpcPostProcess.finalize(
            router.route(request("triggers.upsert", """{"rule":{"task":"t","source":"time.daily","params":{"time":"09:00"}}}"""), backend),
        )
        assertTrue(upsert is JsonRpc.Response.Success)
        val shot = RpcPostProcess.finalize(router.route(request("device.screenshot", """{"tag":"x"}"""), backend))
        assertEquals("device.screenshot", ((shot as JsonRpc.Response.Success).result["capability"] as kotlinx.serialization.json.JsonPrimitive).content)
    }
}

class DevApiServerTest {

    private class NoopBackend : RpcRouterTest.FakeBackend()

    private fun server(enabled: Boolean = true, allowRemote: Boolean = false, token: TokenStore = TokenStore().apply { issue() }): DevApiServer {
        val policy = DevApiPolicy(enabled = enabled, port = 0, allowRemote = allowRemote)
        return DevApiServer(NoopBackend(), policy, tokens = token)
    }

    @Test
    fun `dispatch requires the bearer token`() {
        val devServer = server()
        val response = devServer.dispatch(
            """{"jsonrpc":"2.0","id":1,"method":"system.ping"}""",
            authHeader = null,
            caller = "http",
        )
        assertTrue(response.contains("UNAUTHORIZED"))
        val token = devServer.tokens.currentOrNull()!!
        val ok = devServer.dispatch(
            """{"jsonrpc":"2.0","id":1,"method":"system.ping"}""",
            authHeader = "Bearer $token",
            caller = "http",
        )
        assertTrue(ok.contains("\"result\""))
    }

    @Test
    fun `disabled server refuses http calls but accepts in-process local calls`() {
        val devServer = server(enabled = false)
        assertTrue(devServer.dispatch("""{"jsonrpc":"2.0","id":1,"method":"system.ping"}""", null, "http").contains("DEV_API_OFF"))
        // ContentProvider/local path still evaluates (used by adb one-liners on debug builds)
        assertNotNull(devServer.handleRpc("""{"jsonrpc":"2.0","id":1,"method":"system.ping"}""", null))
    }

    @Test
    fun `http round trip on a real local socket`() {
        val devServer = server()
        val port = devServer.start()
        try {
            val token = devServer.tokens.currentOrNull()!!
            // unauthenticated ping
            val ping = URL("http://127.0.0.1:$port/ping").openConnection() as HttpURLConnection
            ping.connect()
            assertEquals(200, ping.responseCode)
            assertEquals("""{"status":"ok"}""", BufferedReader(InputStreamReader(ping.inputStream)).readText())

            // authorized rpc
            val rpc = URL("http://127.0.0.1:$port/rpc").openConnection() as HttpURLConnection
            rpc.requestMethod = "POST"
            rpc.setRequestProperty("Authorization", "Bearer $token")
            rpc.doOutput = true
            rpc.outputStream.write("""{"jsonrpc":"2.0","id":9,"method":"system.info"}""".encodeToByteArray())
            assertEquals(200, rpc.responseCode)
            val body = BufferedReader(InputStreamReader(rpc.inputStream)).readText()
            assertTrue(body.contains("\"result\""))
            assertTrue(body.contains("\"id\":9"))

            // unauthorized rpc → 401
            val denied = URL("http://127.0.0.1:$port/rpc").openConnection() as HttpURLConnection
            denied.requestMethod = "POST"
            denied.doOutput = true
            denied.outputStream.write("""{"jsonrpc":"2.0","id":1,"method":"system.ping"}""".encodeToByteArray())
            assertEquals(401, denied.responseCode)
        } finally {
            devServer.stop()
        }
    }

    @Test
    fun `localhost binding by default and lan only with the dev_api_remote flag`() {
        // Default (allowRemote=false): the socket binds loopback — a LAN address must refuse.
        val local = server()
        val localPort = local.start()
        try {
            val loopback = java.net.Socket("127.0.0.1", localPort)
            runCatching { loopback.close() }
            val lanAddr = java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            if (lanAddr != null) {
                val refused = runCatching { java.net.Socket(lanAddr, localPort) }
                assertTrue("LAN connect must fail on a loopback-only bind", refused.isFailure)
            }
        } finally {
            local.stop()
        }

        // allowRemote=true (the dev_api_remote switch on): the socket binds wildcard.
        val lan = server(allowRemote = true)
        val lanPort = lan.start()
        try {
            val socket = java.net.Socket("127.0.0.1", lanPort) // wildcard binds accept loopback too
            runCatching { socket.close() }
        } finally {
            lan.stop()
        }
    }

    @Test
    fun `restart rebinds with the flipped policy`() {
        val devServer = server(allowRemote = false)
        devServer.start()
        assertTrue(devServer.isRunning())
        devServer.policy.allowRemote = true // the switch flipped in Settings
        val port = devServer.restart()
        assertTrue(port != null && devServer.isRunning())
        devServer.stop()
        assertTrue(!devServer.isRunning())
        assertEquals(null, devServer.restart()) // restart on a stopped server is a no-op
    }

    @Test
    fun `content provider rpc path encodes args like adb does`() {
        val devServer = server()
        val token = devServer.tokens.currentOrNull()!!
        val args = URLEncoder.encode("""{"jsonrpc":"2.0","id":1,"method":"tasks.get","params":{"id":"com.example.a"}}""", StandardCharsets.UTF_8)
        val response = devServer.dispatch(
            java.net.URLDecoder.decode(args, StandardCharsets.UTF_8),
            authHeader = "Bearer $token",
            caller = "local",
        )
        assertTrue(response.contains("NOT_FOUND")) // fake backend returns null for tasks.get
    }
}
