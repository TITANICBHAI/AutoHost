package app.autohost.capabilities.app

import app.autohost.core.broker.CallContext
import app.autohost.core.common.FakeClock
import app.autohost.core.common.ForegroundSource
import app.autohost.core.common.ForegroundTracker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12-17 (A9, ADR-098; G12-f): `device.app` honours its grant — `list` under a restricted
 * `device.app.query` grant returns ONLY the granted packages, and the foreground of an
 * out-of-scope app is redacted to `{in_scope:false}`, never named (the shape the agent's
 * observation floor uses).
 */
class AppScopeTest {

    private val clock = FakeClock()
    private val reader = FakePackageReader(
        listOf(
            AppInfo("app.autohost.fixture", "AutoHost Fixture", "1.0", 5),
            AppInfo("com.example.calculator", "Calculator", "2.3.1", 42),
            AppInfo("com.bank.test", "Bank", "9.9", 9),
        ),
    )

    private class MutableForeground(var current: ForegroundTracker.Foreground? = null) : ForegroundSource {
        override fun current(): ForegroundTracker.Foreground? = current
    }

    private fun ctx(queryGrant: String) = CallContext(
        runId = "run-1", taskId = "t", profileId = null,
        grants = mapOf("device.app.query" to Json.parseToJsonElement(queryGrant).jsonObject),
        nowMs = 0L,
    )

    private fun provider(foreground: ForegroundTracker.Foreground?) =
        AppProvider(reader, null, MutableForeground(foreground), clock, pollIntervalMs = 10)

    @Test
    fun `given a restricted query grant when list is called then ONLY the granted packages return`() = runBlocking {
        val env = provider(null).invoke(
            ctx("""{"packages":["app.autohost.fixture","com.bank.test"]}"""),
            "list", Json.parseToJsonElement("{}").jsonObject,
        )
        val packages = (env.result!!.jsonObject["packages"] as kotlinx.serialization.json.JsonArray)
            .map { (it as kotlinx.serialization.json.JsonObject)["package"]!!.let { p -> (p as JsonPrimitive).content } }
        assertEquals(listOf("app.autohost.fixture", "com.bank.test"), packages)
    }

    @Test
    fun `given an unrestricted query grant when list is called then everything returns (dev grant-all)`() = runBlocking {
        val env = provider(null).invoke(ctx("{}"), "list", Json.parseToJsonElement("{}").jsonObject)
        val packages = (env.result!!.jsonObject["packages"] as kotlinx.serialization.json.JsonArray)
        assertEquals(3, packages.size)
    }

    @Test
    fun `given the foreground app outside the grant when foreground is called then it is redacted to in_scope false`() = runBlocking {
        val env = provider(ForegroundTracker.Foreground("com.example.calculator", null, 0L)).invoke(
            ctx("""{"packages":["app.autohost.fixture"]}"""),
            "foreground", Json.parseToJsonElement("{}").jsonObject,
        )
        val result = env.result!!.jsonObject
        assertEquals(false, (result["in_scope"] as JsonPrimitive).content.toBoolean())
        assertTrue("the out-of-scope package is never named", result["package"] == null)
    }

    @Test
    fun `given the foreground app inside the grant when foreground is called then it is named`() = runBlocking {
        val env = provider(ForegroundTracker.Foreground("app.autohost.fixture", "com.example.MainActivity", 0L)).invoke(
            ctx("""{"packages":["app.autohost.fixture"]}"""),
            "foreground", Json.parseToJsonElement("{}").jsonObject,
        )
        val result = env.result!!.jsonObject
        assertEquals("app.autohost.fixture", (result["package"] as JsonPrimitive).content)
        assertEquals("com.example.MainActivity", (result["activity"] as JsonPrimitive).content)
    }

    @Test
    fun `given an unrestricted grant when foreground is called then it is named (dev grant-all)`() = runBlocking {
        val env = provider(ForegroundTracker.Foreground("com.example.calculator", null, 0L)).invoke(
            ctx("{}"), "foreground", Json.parseToJsonElement("{}").jsonObject,
        )
        assertEquals("com.example.calculator", (env.result!!.jsonObject["package"] as JsonPrimitive).content)
    }
}
