package app.autohost.capabilities.a11y

import app.autohost.core.broker.CallContext
import app.autohost.core.common.FakeClock
import app.autohost.core.model.UiNode
import app.autohost.core.model.Bounds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12-16 (A7/A8, ADR-098; G12-f): the scope-closure suite — a run cannot ACT outside its grants:
 * refs are RUN-SCOPED (run B never touches run A's handles), the resolved target's package is
 * checked against the ACT scope, and the `allow_coordinates`/`allow_global` spec flags bind
 * whenever the act grant is explicit (absent = false; an unrestricted dev grant is not bound).
 */
class ScopeClosureTest {

    private fun node(index: Int, pkg: String) = UiNode(
        index = index, parent = -1, depth = 0, pkg = pkg,
        cls = "android.widget.Button", id = "btn$index", text = "B$index", desc = "",
        bounds = Bounds(0, 0, 100, 50), flags = setOf("clickable"),
    )

    private fun ctx(runId: String, actScope: JsonObject) = CallContext(
        runId = runId, taskId = "t", profileId = null,
        grants = mapOf(
            "device.ui.observe" to Json.parseToJsonElement("""{"packages":["app.autohost.fixture"]}""").jsonObject,
            "device.ui.act" to actScope,
        ),
        nowMs = 0L,
    )

    private fun newProvider() = A11yUiProvider(
        reader = FakeReader(
            A11ySnapshot(
                tsMs = 1L, pkg = "app.autohost.fixture", keyboardVisible = false,
                nodes = listOf(node(0, "app.autohost.fixture")), infos = listOf(null),
            ),
        ),
        registry = NodeRegistry(FakeClock()),
        actions = FakeUiActions(),
        windowChanges = { 999_999L },
        clock = FakeClock(startMs = 1_000_000L, autoAdvancePerReadMs = 1),
        connected = { true },
        pollIntervalMs = 1,
    )

    private suspend fun invoke(provider: A11yUiProvider, ctx: CallContext, method: String, args: String) =
        provider.invoke(ctx, method, Json.parseToJsonElement(args).jsonObject)

    private fun firstRef(snap: JsonObject): String =
        (snap["nodes"] as kotlinx.serialization.json.JsonArray)[0].jsonObject["ref"]!!.let { it as kotlinx.serialization.json.JsonPrimitive }.content

    // --- A7 leg 1: refs are run-scoped -----------------------------------------------------------

    @Test
    fun `given run A minted a ref when run B resolves it then STALE_NODE (one shared registry)`() = runBlocking {
        val provider = newProvider()
        // mint through the minting run own snapshot path
        val snapCtx = ctx("run-A", Json.parseToJsonElement("""{"packages":["app.autohost.fixture"]}""").jsonObject)
        val snap = invoke(provider, snapCtx, "snapshot", "{}").result!!.jsonObject
        val ref = firstRef(snap)

        val b = ctx("run-B", Json.parseToJsonElement("""{"packages":["app.autohost.fixture"]}""").jsonObject)
        val env = invoke(provider, b, "click", """{"ref":"$ref"}""")
        assertEquals("STALE_NODE", env.error?.code)
        assertTrue(env.error!!.message.contains("another run"))
    }

    @Test
    fun `given the owner run own ref when resolved then it works (the scoping never bites the minting run)`() = runBlocking {
        val provider = newProvider()
        val a = ctx("run-A", Json.parseToJsonElement("""{"packages":["app.autohost.fixture"]}""").jsonObject)
        val snap = invoke(provider, a, "snapshot", "{}").result!!.jsonObject
        val ref = firstRef(snap)
        val env = invoke(provider, a, "click", """{"ref":"$ref"}""")
        assertTrue("the minting run ref must act: ${env.error}", env.ok)
    }

    // --- A7 leg 2: the act scope binds on the resolved target's package --------------------------

    @Test
    fun `given a ref to a node of package P under an act scope of Q when resolved then SCOPE_NOT_GRANTED`() = runBlocking {
        val registry = NodeRegistry(FakeClock())
        val foreign = node(0, "com.other.app")
        val ref = registry.put(foreign, null, "run-A")
        // resolveHandle is private — the check rides guardAndResolve; drive it through click
        val provider = newProviderWithRegistry(registry)
        val a = ctx("run-A", Json.parseToJsonElement("""{"packages":["app.autohost.fixture"]}""").jsonObject)
        val env = invoke(provider, a, "click", """{"ref":"$ref"}""")
        assertEquals("SCOPE_VIOLATION", env.error?.code)
        assertEquals("SCOPE_NOT_GRANTED", env.error?.reason)
    }

    // --- A8: the spec scope flags ----------------------------------------------------------------

    @Test
    fun `given an explicit act grant WITHOUT allow_coordinates when tap then SCOPE_VIOLATION (absent = false)`() = runBlocking {
        val provider = newProvider()
        val env = invoke(provider, ctx("r", Json.parseToJsonElement("""{"packages":["app.autohost.fixture"]}""").jsonObject), "tap", """{"x":10,"y":10}""")
        assertEquals("SCOPE_VIOLATION", env.error?.code)
        assertEquals("SCOPE_NOT_GRANTED", env.error?.reason)
    }

    @Test
    fun `given allow_coordinates true when tap then the gesture dispatches`() = runBlocking {
        val provider = newProvider()
        val env = invoke(
            provider,
            ctx("r", Json.parseToJsonElement("""{"packages":["app.autohost.fixture"],"allow_coordinates":true}""").jsonObject),
            "tap", """{"x":10,"y":10}""",
        )
        assertTrue("tap must run with the flag: ${env.error}", env.ok)
    }

    @Test
    fun `given an UNRESTRICTED act grant when tap then the flags do not bind (dev grant-all keeps working)`() = runBlocking {
        val provider = newProvider()
        val env = invoke(provider, ctx("r", Json.parseToJsonElement("""{"packages":"*"}""").jsonObject), "tap", """{"x":10,"y":10}""")
        assertTrue("the null/unrestricted grant is not flag-bound: ${env.error}", env.ok)
    }

    @Test
    fun `given allow_global absent when pressing home then refused; back stays always allowed`() = runBlocking {
        val provider = newProvider()
        val act = Json.parseToJsonElement("""{"packages":["app.autohost.fixture"]}""").jsonObject
        val home = invoke(provider, ctx("r", act), "press", """{"key":"home"}""")
        assertEquals("SCOPE_VIOLATION", home.error?.code)
        val back = invoke(provider, ctx("r", act), "press", """{"key":"back"}""")
        assertTrue("back is the escape hatch and stays allowed: ${back.error}", back.ok)
    }

    @Test
    fun `given allow_global true when pressing home then it runs`() = runBlocking {
        val provider = newProvider()
        val env = invoke(
            provider,
            ctx("r", Json.parseToJsonElement("""{"packages":["app.autohost.fixture"],"allow_global":true}""").jsonObject),
            "press", """{"key":"home"}""",
        )
        assertTrue(env.ok)
    }

    @Test
    fun `given swipe under an explicit grant without the flag when called then refused like tap`() = runBlocking {
        val provider = newProvider()
        val env = invoke(provider, ctx("r", Json.parseToJsonElement("""{"packages":["app.autohost.fixture"]}""").jsonObject), "swipe", """{"x1":1,"y1":1,"x2":2,"y2":2,"duration_ms":100}""")
        assertEquals("SCOPE_VIOLATION", env.error?.code)
    }

    // -- the helper: the same provider with a caller-owned registry --------------------------------

    private fun newProviderWithRegistry(registry: NodeRegistry): A11yUiProvider = A11yUiProvider(
        reader = FakeReader(
            A11ySnapshot(
                tsMs = 1L, pkg = "app.autohost.fixture", keyboardVisible = false,
                nodes = listOf(node(0, "app.autohost.fixture")), infos = listOf(null),
            ),
        ),
        registry = registry,
        actions = FakeUiActions(),
        windowChanges = { 999_999L },
        clock = FakeClock(startMs = 1_000_000L, autoAdvancePerReadMs = 1),
        connected = { true },
        pollIntervalMs = 1,
    )
}
