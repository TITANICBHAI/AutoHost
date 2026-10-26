package app.autohost.capabilities.screen

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8-10: device.screen control methods — never bypasses a secure keyguard; honest a11y gating. */
class ScreenControlTest {

    private class FakeState(
        var secure: Boolean = false,
        var bright: Int = 150,
        var mode: String = "manual",
    ) : SystemState {
        var dismissed = false
        var setTo: Int? = null
        override fun isOn(): Boolean = true
        override fun isUnlocked(): Boolean = !secure
        override fun isSecure(): Boolean = secure
        override fun requestDismissKeyguard(): Boolean {
            dismissed = true
            return true
        }
        override fun brightness(): Int = bright
        override fun brightnessMode(): String = mode
        override fun setBrightness(level: Int): Boolean {
            setTo = level
            bright = level * 255 / 100
            return true
        }
    }

    private class FakeA11y(var woke: Boolean = true, var locked: Boolean = true) : A11yScreenActions {
        override fun wakeUp(): Boolean = woke
        override fun lockScreen(): Boolean = locked
    }

    private fun ctx(grants: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap()) =
        CallContext("r", "t", null, grants, 0)

    private fun grant(permission: String) = mapOf(permission to JsonObject(emptyMap()))

    @Test fun `wake rides the a11y service and is honest when it is off`() = runBlocking {
        val provider = ScreenProvider(FakeState())
        val off = provider.invoke(ctx(grant(ScreenControlPolicy.PERMISSION_CONTROL)), "wake", JsonObject(emptyMap()))
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE.name, off.error?.code)
        assertEquals("OPEN_ACCESSIBILITY_SETTINGS", off.error?.fixAction)

        provider.a11yActions = FakeA11y()
        val on = provider.invoke(ctx(grant(ScreenControlPolicy.PERMISSION_CONTROL)), "wake", JsonObject(emptyMap()))
        assertEquals(true, on.result!!.jsonObject["on"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("a11y_wake_up", on.result!!.jsonObject["strategy"]!!.jsonPrimitive.content)
    }

    @Test fun `lock rides the a11y service`() = runBlocking {
        val provider = ScreenProvider(FakeState())
        provider.a11yActions = FakeA11y(locked = true)
        val result = provider.invoke(ctx(grant(ScreenControlPolicy.PERMISSION_CONTROL)), "lock", JsonObject(emptyMap()))
        assertEquals(true, result.result!!.jsonObject["locked"]!!.jsonPrimitive.content.toBooleanStrict())
    }

    @Test fun `secure keyguard is never bypassed`() = runBlocking {
        val state = FakeState(secure = true)
        val provider = ScreenProvider(state)
        val result = provider.invoke(ctx(grant(ScreenControlPolicy.PERMISSION_CONTROL)), "dismiss_keyguard", JsonObject(emptyMap()))
        val json = result.result!!.jsonObject
        assertEquals(false, json["dismissed"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals(true, json["secure"]!!.jsonPrimitive.content.toBooleanStrict())
        assertFalse(state.dismissed)
    }

    @Test fun `non-secure keyguard requests dismissal`() = runBlocking {
        val state = FakeState(secure = false)
        val provider = ScreenProvider(state)
        val result = provider.invoke(ctx(grant(ScreenControlPolicy.PERMISSION_CONTROL)), "dismiss_keyguard", JsonObject(emptyMap()))
        assertEquals(true, result.result!!.jsonObject["dismissed"]!!.jsonPrimitive.content.toBooleanStrict())
        assertTrue(state.dismissed)
    }

    @Test fun `brightness read works without write access and write is gated`() = runBlocking {
        val state = FakeState()
        val provider = ScreenProvider(state)
        provider.canWriteSettings = { false }
        val read = provider.invoke(ctx(grant(ScreenControlPolicy.PERMISSION_CONTROL)), "brightness", JsonObject(emptyMap()))
        assertEquals(150, read.result!!.jsonObject["level"]!!.jsonPrimitive.content.toInt())

        val denied = provider.invoke(ctx(grant(ScreenControlPolicy.PERMISSION_CONTROL)), "brightness", buildJsonObject { put("level", 40) })
        assertEquals(ErrorCode.NEEDS_SETUP.name, denied.error?.code)
        assertEquals("OPEN_WRITE_SETTINGS", denied.error?.fixAction)

        provider.canWriteSettings = { true }
        val written = provider.invoke(ctx(grant(ScreenControlPolicy.PERMISSION_CONTROL)), "brightness", buildJsonObject { put("level", 40) })
        assertEquals(40, written.result!!.jsonObject["level"]!!.jsonPrimitive.content.toInt())
        assertEquals(40, state.setTo)
        assertNull(written.error)
    }

    @Test fun `brightness level is validated`() = runBlocking {
        val provider = ScreenProvider(FakeState())
        provider.canWriteSettings = { true }
        val bad = provider.invoke(ctx(grant(ScreenControlPolicy.PERMISSION_CONTROL)), "brightness", buildJsonObject { put("level", 400) })
        assertEquals(ErrorCode.INVALID_ARGS.name, bad.error?.code)
    }

    @Test fun `control methods need the control permission`() {
        assertEquals("device.screen.control", ScreenControlPolicy.permissionFor("wake"))
        assertEquals("device.screen.control", ScreenControlPolicy.permissionFor("brightness"))
        assertEquals("device.screen.read", ScreenControlPolicy.permissionFor("is_on"))
    }
}

/** P8-10: ui.overlay provider over a fake sink. */
class UiOverlayProviderTest {

    private class FakeSink : UiOverlayProvider.Sink {
        var connected = true
        val highlights = mutableListOf<UiOverlayHighlight>()
        var panel: Pair<String, String>? = null
        var cleared = 0
        override fun connected(): Boolean = connected
        override fun highlight(runId: String, x: Int, y: Int, w: Int, h: Int, label: String?, durationMs: Long): Boolean {
            highlights.add(UiOverlayHighlight(runId, x, y, w, h, label, durationMs))
            return true
        }
        override fun panel(runId: String, text: String, corner: String): Boolean {
            panel = text to corner
            return true
        }
        override fun clear(runId: String): Int {
            cleared += 1
            return cleared
        }
    }

    private fun ctx() = CallContext("run-9", "t", null, emptyMap(), 0)

    @Test fun `highlight and panel round trip with run scoping`() = runBlocking {
        val sink = FakeSink()
        val provider = UiOverlayProvider(sink)
        val hi = provider.invoke(ctx(), "highlight", buildJsonObject {
            put("x", 10); put("y", 20); put("w", 100); put("h", 50); put("label", "target"); put("ms", 4000)
        })
        assertEquals(true, hi.result!!.jsonObject["shown"]!!.jsonPrimitive.content.toBooleanStrict())
        val box = sink.highlights.single()
        assertEquals("run-9", box.runId)
        assertEquals(4000L, box.durationMs)

        provider.invoke(ctx(), "panel", buildJsonObject { put("text", "step 2/5"); put("corner", "bottom") })
        assertEquals("step 2/5" to "bottom", sink.panel)

        val cleared = provider.invoke(ctx(), "clear", JsonObject(emptyMap()))
        assertEquals(1, cleared.result!!.jsonObject["removed"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `a11y off is a typed honest failure`() = runBlocking {
        val sink = FakeSink()
        sink.connected = false
        val provider = UiOverlayProvider(sink)
        val result = provider.invoke(ctx(), "panel", buildJsonObject { put("text", "x") })
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE.name, result.error?.code)
        assertEquals("OPEN_ACCESSIBILITY_SETTINGS", result.error?.fixAction)
    }

    @Test fun `invalid geometry and text are rejected`() = runBlocking {
        val provider = UiOverlayProvider(FakeSink())
        val bad = provider.invoke(ctx(), "highlight", buildJsonObject { put("x", -1); put("y", 0); put("w", 10); put("h", 10) })
        assertEquals(ErrorCode.INVALID_ARGS.name, bad.error?.code)
        val blank = provider.invoke(ctx(), "panel", buildJsonObject { put("text", " ") })
        assertEquals(ErrorCode.INVALID_ARGS.name, blank.error?.code)
    }

    @Test fun `availability reports the a11y setup need`() {
        val sink = FakeSink()
        val provider = UiOverlayProvider(sink)
        assertTrue(provider.availability(app.autohost.core.broker.DeviceEnv(29)) is app.autohost.core.model.Availability.Available)
        sink.connected = false
        val needs = provider.availability(app.autohost.core.broker.DeviceEnv(29))
        assertTrue(needs is app.autohost.core.model.Availability.NeedsSetup)
    }
}

private data class UiOverlayHighlight(
    val runId: String, val x: Int, val y: Int, val w: Int, val h: Int,
    val label: String?, val durationMs: Long,
)
