package app.autohost.capabilities.a11y

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Bounds
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import app.autohost.core.model.UiNode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reader over hand-built trees; honours the packages filter exactly like the real one (no Android types). */
class FakeReader(var snapshot: A11ySnapshot) : A11yReader {
    var reads = 0

    override fun read(packages: Set<String>?): A11ySnapshot {
        reads++
        if (packages == null) return snapshot
        val filtered = snapshot.nodes.filter { it.pkg in packages }
        return snapshot.copy(nodes = filtered, infos = filtered.map { snapshot.infoAt(it.index) })
    }

    override fun keyboardVisible(packages: Set<String>?): Boolean = snapshot.keyboardVisible

    override fun windows(): List<RootResolver.WindowMeta> = snapshot.windows
}

class FakeUiActions : UiActions {
    val clicks = mutableListOf<String>()
    var clickOutcome = NodeActions.ClickOutcome(NodeActions.Route.NODE, true)
    var setTextOutcome = true
    var pasteOutcome = true
    val texts = mutableListOf<String>()
    val swipes = mutableListOf<FloatArray>()
    val globals = mutableListOf<String>()

    override fun click(info: android.view.accessibility.AccessibilityNodeInfo?): NodeActions.ClickOutcome {
        clicks.add("click")
        return clickOutcome
    }

    override fun longClick(info: android.view.accessibility.AccessibilityNodeInfo?): NodeActions.ClickOutcome {
        clicks.add("long_click")
        return clickOutcome
    }

    override fun setText(info: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean {
        texts.add(text)
        return setTextOutcome
    }

    override fun paste(info: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        clicks.add("paste")
        return pasteOutcome
    }

    override fun tap(x: Float, y: Float): Boolean {
        swipes.add(floatArrayOf(x, y, x, y))
        return true
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        swipes.add(floatArrayOf(x1, y1, x2, y2))
        return true
    }

    override fun global(key: String): Boolean {
        globals.add(key)
        return true
    }
}

private fun node(
    index: Int,
    parent: Int,
    depth: Int,
    id: String,
    text: String = "",
    cls: String = "android.widget.Button",
    pkg: String = "app.autohost.fixture",
    clickable: Boolean = false,
    editable: Boolean = false,
): UiNode = UiNode(
    index = index,
    parent = parent,
    depth = depth,
    pkg = pkg,
    cls = cls,
    id = id,
    text = text,
    desc = "",
    bounds = Bounds(0, index * 100, 100, index * 100 + 80),
    flags = buildSet {
        if (clickable) add("clickable")
        if (editable) add("editable")
    },
)

private fun snapshotOf(vararg nodes: UiNode, keyboard: Boolean = false, pkg: String = "app.autohost.fixture") =
    A11ySnapshot(
        tsMs = 1L,
        pkg = pkg,
        keyboardVisible = keyboard,
        nodes = nodes.toList(),
        infos = nodes.map { null },
        truncated = false,
    )

private fun selectorFor(term: JsonObject): JsonObject = buildJsonObject {
    put("terms", JsonArray(listOf(term)))
}

private fun idTerm(id: String): JsonObject = buildJsonObject {
    put("id", buildJsonObject { put("labels", JsonArray(listOf(JsonPrimitive(id)))) })
}

class A11yUiProviderTest {
    private val clock = FakeClock()
    private lateinit var reader: FakeReader
    private lateinit var actions: FakeUiActions
    private lateinit var registry: NodeRegistry
    private lateinit var provider: A11yUiProvider
    private var windowChangeMs = 0L
    private var connected = true

    private val ctx = CallContext(
        runId = "run-1",
        taskId = "task-1",
        profileId = null,
        grants = mapOf(
            "device.ui.observe" to buildJsonObject {
                put("packages", JsonArray(listOf(JsonPrimitive("app.autohost.fixture"))))
            },
            "device.ui.act" to buildJsonObject {
                put("packages", JsonArray(listOf(JsonPrimitive("app.autohost.fixture"))))
            },
        ),
        nowMs = 0L,
    )

    private fun setUpProvider(snapshot: A11ySnapshot) {
        reader = FakeReader(snapshot)
        actions = FakeUiActions()
        registry = NodeRegistry(clock)
        provider = A11yUiProvider(
            reader = reader,
            registry = registry,
            actions = actions,
            windowChanges = { windowChangeMs },
            clock = clock,
            connected = { connected },
            pollIntervalMs = 10,
        )
    }

    private fun tree() = listOf(
        node(0, -1, 0, "fx_title", text = "Fixture", cls = "android.widget.TextView"),
        node(1, 0, 1, "fx_btn_continue", text = "Continue", clickable = true),
        node(2, 0, 1, "fx_text", text = "", cls = "android.widget.EditText", editable = true),
    )

    @Test
    fun `given service connected when availability then available`() {
        setUpProvider(snapshotOf())
        assertEquals(Availability.Available, provider.availability(DeviceEnv(30)))
    }

    @Test
    fun `given service off when availability then needs setup with fix action`() {
        setUpProvider(snapshotOf())
        connected = false
        val availability = provider.availability(DeviceEnv(30))
        assertTrue(availability is Availability.NeedsSetup)
        assertEquals("A11Y_SERVICE_DISABLED", (availability as Availability.NeedsSetup).reason)
        assertEquals("OPEN_ACCESSIBILITY_SETTINGS", availability.fixAction)
    }

    @Test
    fun `given observe methods then observe permission and act methods then act permission`() {
        setUpProvider(snapshotOf())
        for (method in listOf("snapshot", "find", "wait_for", "wait_for_settle", "is_window_updating", "dump", "keyboard_visible")) {
            assertEquals("device.ui.observe", provider.permissionFor(method, JsonObject(emptyMap())))
        }
        for (method in listOf("click", "long_click", "set_text", "scroll_until", "swipe", "tap", "press", "hide_keyboard")) {
            assertEquals("device.ui.act", provider.permissionFor(method, JsonObject(emptyMap())))
        }
        assertNull(provider.permissionFor("ghost", JsonObject(emptyMap())))
    }

    @Test
    fun `given snapshot call then nodes carry refs and compact dump`() = runTest {
        setUpProvider(snapshotOf(*tree().toTypedArray()))
        val envelope = provider.invoke(ctx, "snapshot", buildJsonObject { })
        assertTrue(envelope.ok)
        val result = envelope.result!!.jsonObject
        assertEquals(3, (result["node_count"] as JsonPrimitive).content.toInt())
        val nodes = result["nodes"]!!.jsonArray
        val first = nodes.first().jsonObject
        assertEquals("fx_title", (first["id"] as JsonPrimitive).content)
        assertNotNull(first["ref"])
        assertTrue(result["compact"]!!.jsonPrimitive.content.contains("fx_btn_continue"))
    }

    @Test
    fun `given snapshot with explicit scope outside grant then grant still narrows`() = runTest {
        setUpProvider(snapshotOf(*tree().toTypedArray()))
        val envelope = provider.invoke(ctx, "snapshot", buildJsonObject {
            put("scope", buildJsonObject { put("packages", JsonArray(listOf(JsonPrimitive("com.elsewhere")))) })
        })
        assertTrue(envelope.ok)
        assertEquals(0, (envelope.result!!.jsonObject["node_count"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `given find by selector then ref returned`() = runTest {
        setUpProvider(snapshotOf(*tree().toTypedArray()))
        val envelope = provider.invoke(ctx, "find", buildJsonObject { put("selector", selectorFor(idTerm("fx_btn_continue"))) })
        assertTrue(envelope.ok)
        val ref = (envelope.result!!.jsonObject["ref"] as JsonPrimitive).content
        assertTrue(ref.startsWith("s"))
    }

    @Test
    fun `given no match with zero timeout then not found with reason`() = runTest {
        setUpProvider(snapshotOf(*tree().toTypedArray()))
        val envelope = provider.invoke(ctx, "find", buildJsonObject {
            put("selector", selectorFor(buildJsonObject {
                put("text", buildJsonObject { put("labels", JsonArray(listOf(JsonPrimitive("missing")))) })
            }))
        })
        assertEquals(ErrorCode.NOT_FOUND.name, envelope.error?.code)
        assertEquals("NO_MATCH", envelope.error?.reason)
    }

    @Test
    fun `given wait_for when element appears late then found`() = runTest {
        val treeNodes = tree()
        setUpProvider(snapshotOf()) // starts empty
        val lateReader = object : A11yReader {
            var reads = 0
            override fun read(packages: Set<String>?): A11ySnapshot {
                reads++
                return if (reads < 2) snapshotOf() else snapshotOf(*treeNodes.toTypedArray())
            }

            override fun keyboardVisible(packages: Set<String>?): Boolean = false
        }
        provider = A11yUiProvider(
            reader = lateReader,
            registry = registry,
            actions = actions,
            windowChanges = { windowChangeMs },
            clock = clock,
            connected = { connected },
            pollIntervalMs = 10,
        )
        val envelope = provider.invoke(ctx, "wait_for", buildJsonObject {
            put("timeout_ms", 5_000)
            put("selector", selectorFor(idTerm("fx_btn_continue")))
        })
        assertTrue(envelope.ok)
    }

    @Test
    fun `given click by ref then action dispatched with route`() = runTest {
        setUpProvider(snapshotOf(*tree().toTypedArray()))
        val found = provider.invoke(ctx, "find", buildJsonObject { put("selector", selectorFor(idTerm("fx_btn_continue"))) })
        val ref = (found.result!!.jsonObject["ref"] as JsonPrimitive).content
        val clicked = provider.invoke(ctx, "click", buildJsonObject { put("ref", ref) })
        assertTrue(clicked.ok)
        assertEquals("NODE", (clicked.result!!.jsonObject["method"] as JsonPrimitive).content)
        assertEquals(1, actions.clicks.size)
    }

    @Test
    fun `given cleared registry when clicked then stale node error`() = runTest {
        setUpProvider(snapshotOf(*tree().toTypedArray()))
        val found = provider.invoke(ctx, "find", buildJsonObject { put("selector", selectorFor(idTerm("fx_btn_continue"))) })
        val ref = (found.result!!.jsonObject["ref"] as JsonPrimitive).content
        registry.clear()
        val clicked = provider.invoke(ctx, "click", buildJsonObject { put("ref", ref) })
        assertEquals(ErrorCode.STALE_NODE.name, clicked.error?.code)
    }

    @Test
    fun `given set_text then field written and verified`() = runTest {
        setUpProvider(snapshotOf(*tree().toTypedArray()))
        val found = provider.invoke(ctx, "find", buildJsonObject { put("selector", selectorFor(idTerm("fx_text"))) })
        val ref = (found.result!!.jsonObject["ref"] as JsonPrimitive).content
        val result = provider.invoke(ctx, "set_text", buildJsonObject { put("ref", ref); put("text", "hello") })
        assertTrue(result.ok)
        assertEquals("hello", actions.texts.single())
    }

    @Test
    fun `given set_text rejected then text input rejected envelope`() = runTest {
        setUpProvider(snapshotOf(*tree().toTypedArray()))
        actions.setTextOutcome = false
        val found = provider.invoke(ctx, "find", buildJsonObject { put("selector", selectorFor(idTerm("fx_text"))) })
        val ref = (found.result!!.jsonObject["ref"] as JsonPrimitive).content
        val result = provider.invoke(ctx, "set_text", buildJsonObject { put("ref", ref); put("text", "x") })
        assertEquals(ErrorCode.TEXT_INPUT_REJECTED.name, result.error?.code)
    }

    @Test
    fun `given press with unknown key then invalid args and with back then performed`() = runTest {
        setUpProvider(snapshotOf())
        val result = provider.invoke(ctx, "press", buildJsonObject { put("key", "nuclear") })
        assertEquals(ErrorCode.INVALID_ARGS.name, result.error?.code)
        val ok = provider.invoke(ctx, "press", buildJsonObject { put("key", "back") })
        assertTrue(ok.ok)
        assertEquals("back", actions.globals.single())
    }

    @Test
    fun `given tap without numbers then invalid args`() = runTest {
        setUpProvider(snapshotOf())
        val result = provider.invoke(ctx, "tap", buildJsonObject { put("x", "left") })
        assertEquals(ErrorCode.INVALID_ARGS.name, result.error?.code)
    }

    @Test
    fun `given swipe with numbers then gesture dispatched`() = runTest {
        setUpProvider(snapshotOf())
        val result = provider.invoke(ctx, "swipe", buildJsonObject {
            put("x1", 100); put("y1", 200); put("x2", 100); put("y2", 800)
        })
        assertTrue(result.ok)
        assertEquals(1, actions.swipes.size)
    }

    @Test
    fun `given hide keyboard when visible then back performed`() = runTest {
        setUpProvider(snapshotOf(*tree().toTypedArray(), keyboard = true))
        val result = provider.invoke(ctx, "hide_keyboard", buildJsonObject { })
        assertTrue(result.ok)
        assertEquals("back", actions.globals.single())
    }

    @Test
    fun `given window recently changed then window is updating`() = runTest {
        setUpProvider(snapshotOf())
        windowChangeMs = clock.nowMs() - 200
        val result = provider.invoke(ctx, "is_window_updating", buildJsonObject { })
        assertTrue((result.result!!.jsonObject["updating"] as JsonPrimitive).content.toBooleanStrict())
        windowChangeMs = clock.nowMs() - 5_000
        val settled = provider.invoke(ctx, "is_window_updating", buildJsonObject { })
        assertFalse((settled.result!!.jsonObject["updating"] as JsonPrimitive).content.toBooleanStrict())
    }

    @Test
    fun `given settle call when quiet then settled immediately`() = runTest {
        setUpProvider(snapshotOf())
        windowChangeMs = clock.nowMs() - 2_000
        val result = provider.invoke(ctx, "wait_for_settle", buildJsonObject { put("quiet_ms", 500) })
        assertTrue(result.ok)
        assertTrue((result.result!!.jsonObject["settled"] as JsonPrimitive).content.toBooleanStrict())
    }

    @Test
    fun `given settle call when busy past timeout then timeout error`() = runTest {
        // A clock that advances a little on every read keeps the quiet window busy and eventually hits the
        // deadline, so the poll loop terminates under runTest's virtual time.
        val tickingClock = FakeClock(autoAdvancePerReadMs = 50)
        val busyReader = FakeReader(snapshotOf())
        val busyActions = FakeUiActions()
        val busyProvider = A11yUiProvider(
            reader = busyReader,
            registry = NodeRegistry(tickingClock),
            actions = busyActions,
            windowChanges = { tickingClock.nowMs() - 100 },
            clock = tickingClock,
            connected = { true },
            pollIntervalMs = 10,
        )
        val result = busyProvider.invoke(ctx, "wait_for_settle", buildJsonObject {
            put("quiet_ms", 500); put("timeout_ms", 1_000)
        })
        assertEquals(ErrorCode.TIMEOUT.name, result.error?.code)
        assertEquals("UI_BUSY", result.error?.reason)
    }
}
