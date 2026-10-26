package app.autohost.capabilities.notifications

import app.autohost.core.common.FakeClock
import app.autohost.core.broker.CallContext
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAccessPolicyTest {

    @Test
    fun `parses the system setting format`() {
        val setting = "app.autohost/app.autohost.capabilities.notifications.AutoHostNotificationListener:com.other/com.other.Listener"
        val components = NotificationAccessPolicy.enabledComponents(setting)
        assertEquals(2, components.size)
        assertEquals("app.autohost", components[0].pkg)
        assertTrue(NotificationAccessPolicy.isEnabledFor(setting, "app.autohost", "AutoHostNotificationListener"))
        assertFalse(NotificationAccessPolicy.isEnabled(setting, "app.autohost", "app.autohost.OtherListener"))
    }

    @Test
    fun `blank and malformed settings are disabled`() {
        assertFalse(NotificationAccessPolicy.isEnabledFor(null, "app.autohost", "AutoHostNotificationListener"))
        assertFalse(NotificationAccessPolicy.isEnabledFor("", "app.autohost", "AutoHostNotificationListener"))
        assertFalse(NotificationAccessPolicy.isEnabledFor("::", "app.autohost", "AutoHostNotificationListener"))
        assertFalse(NotificationAccessPolicy.isEnabledFor("just-a-package", "app.autohost", "X"))
    }
}

/** Provider behaviour against a stubbed bridge (fake, not a mock of our own classes). */
class NotificationsProviderTest {

    private val clock = FakeClock()

    private fun ctx(packages: Set<String>? = null) = CallContext(
        runId = "r", taskId = "t", profileId = null,
        grants = if (packages == null) emptyMap() else mapOf(
            "device.notifications" to buildJsonObject {
                put("packages", kotlinx.serialization.json.JsonArray(packages.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            },
        ),
        nowMs = clock.nowMs(),
    )

    private fun seed(vararg items: NotificationBridge.Active) {
        NotificationBridge.init(clock)
        NotificationBridge.resetForTest(items.toList())
    }

    private fun item(key: String, pkg: String, title: String = "Hello", text: String = "World") =
        NotificationBridge.Active(
            key = key, pkg = pkg, title = title, text = text, subtext = "", bigText = "",
            postedAtMs = 1, ongoing = false, actions = listOf(NotificationBridge.Action(0, "Open")),
        )

    @Test
    fun `availability is honest about the listener connection`() {
        val provider = NotificationsProvider(clock, access = { true })
        assertEquals(Availability.Available, provider.availability(app.autohost.core.broker.DeviceEnv(sdkInt = 29)))
        val off = NotificationsProvider(clock, access = { false })
        val needs = off.availability(app.autohost.core.broker.DeviceEnv(sdkInt = 29))
        assertTrue(needs is Availability.NeedsSetup)
        assertEquals("OPEN_NOTIFICATION_LISTENER_SETTINGS", (needs as Availability.NeedsSetup).fixAction)
    }

    @Test
    fun `list returns scoped items only`() = runBlocking {
        seed(item("a", "com.mail"), item("b", "com.chat"))
        val provider = NotificationsProvider(clock, access = { true })
        val all = provider.invoke(ctx(), "list", JsonObject(emptyMap()))
        assertEquals(2, ((all.result as JsonObject)["count"])?.jsonPrimitive?.content?.toInt())
        val scoped = provider.invoke(
            ctx(packages = setOf("com.chat")), "list", JsonObject(emptyMap()),
        )
        val rows = ((scoped.result as JsonObject)["notifications"] as kotlinx.serialization.json.JsonArray)
            .map { it.jsonObject }
        assertEquals(1, rows.size)
        assertEquals("com.chat", rows[0]["package"]?.jsonPrimitive?.content)
    }

    @Test
    fun `read of an out-of-scope notification is NOT_FOUND`() = runBlocking {
        seed(item("a", "com.mail"))
        val provider = NotificationsProvider(clock, access = { true })
        val result = provider.invoke(
            ctx(packages = setOf("com.chat")), "read",
            buildJsonObject { put("id", "a") },
        )
        assertEquals(false, result.ok)
        assertEquals(ErrorCode.NOT_FOUND.name, result.error?.code)
    }

    @Test
    fun `press sends through the handler slot`() = runBlocking {
        seed(item("a", "com.mail"))
        var pressed: Pair<String, Int>? = null
        AutoHostNotificationListenerHandlers.press = { key, index -> pressed = key to index; true }
        val provider = NotificationsProvider(clock, access = { true })
        val result = provider.invoke(
            ctx(), "press", buildJsonObject { put("id", "a"); put("action", "Open") },
        )
        assertEquals(true, ((result.result as JsonObject)["sent"])?.jsonPrimitive?.content?.toBoolean())
        assertEquals("a" to 0, pressed)
        AutoHostNotificationListenerHandlers.press = null
    }

    @Test
    fun `wait_for times out honestly when nothing matches`() = runBlocking {
        seed()
        val provider = NotificationsProvider(clock, access = { true })
        val result = provider.invoke(
            ctx(), "wait_for", buildJsonObject { put("timeout_ms", 400); put("title_regex", "nope") },
        )
        assertEquals(false, result.ok)
        assertEquals(ErrorCode.TIMEOUT.name, result.error?.code)
    }

    @Test
    fun `wait_for finds a matching notification immediately`() = runBlocking {
        seed(item("late", "com.mail", title = "Invoice due"))
        val provider = NotificationsProvider(clock, access = { true })
        val result = provider.invoke(
            ctx(), "wait_for", buildJsonObject { put("timeout_ms", 1000); put("title_regex", "Invoice") },
        )
        assertEquals(true, ((result.result as JsonObject)["found"])?.jsonPrimitive?.content?.toBoolean())
        assertNull(result.error)
    }
}
