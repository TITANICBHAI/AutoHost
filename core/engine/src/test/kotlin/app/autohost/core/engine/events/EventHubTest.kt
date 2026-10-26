package app.autohost.core.engine.events

import app.autohost.core.common.FakeClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8-02: fan-out, kind filters, self-unsubscribing subscribers and the bounded ring buffer. */
class EventHubTest {

    private val clock = FakeClock()

    private fun event(kind: String, at: Long = 0): TriggerEvent =
        TriggerEvent(kind = kind, payload = JsonObject(emptyMap()), atMs = at)

    @Test
    fun `dispatch fans out to every subscriber`() {
        val hub = EventHub(clock)
        val seen = mutableListOf<String>()
        hub.subscribe { seen += "a:${it.kind}"; true }
        hub.subscribe { seen += "b:${it.kind}"; true }
        hub.dispatch(event("screen.on"))
        assertEquals(listOf("a:screen.on", "b:screen.on"), seen)
    }

    @Test
    fun `kind filter only delivers matching kinds`() {
        val hub = EventHub(clock)
        val seen = mutableListOf<String>()
        hub.subscribe(kinds = setOf("screen.on", "screen.off")) { seen += it.kind; true }
        hub.dispatch(event("screen.on"))
        hub.dispatch(event("battery.low"))
        hub.dispatch(event("screen.off"))
        assertEquals(listOf("screen.on", "screen.off"), seen)
    }

    @Test
    fun `returning false unsubscribes the subscriber`() {
        val hub = EventHub(clock)
        var count = 0
        hub.subscribe { count += 1; false }
        hub.dispatch(event("screen.on"))
        hub.dispatch(event("screen.on"))
        assertEquals(1, count)
    }

    @Test
    fun `a throwing subscriber does not starve the others`() {
        val hub = EventHub(clock)
        val seen = mutableListOf<String>()
        hub.subscribe { error("boom") }
        hub.subscribe { seen += it.kind; true }
        hub.dispatch(event("screen.on"))
        assertEquals(listOf("screen.on"), seen)
    }

    @Test
    fun `ring buffer keeps the newest events and respects the limit`() {
        val hub = EventHub(clock, bufferLimit = 3)
        repeat(5) { hub.dispatch(event("battery.level", at = it.toLong())) }
        val recent = hub.recent()
        assertEquals(3, recent.size)
        assertEquals(listOf(2L, 3L, 4L), recent.map { it.atMs })
        assertEquals(3, hub.bufferedCount())
    }

    @Test
    fun `recent filters by kind and trims to limit`() {
        val hub = EventHub(clock)
        repeat(4) { hub.dispatch(event("screen.on")) }
        repeat(2) { hub.dispatch(event("screen.off")) }
        assertEquals(2, hub.recent(kinds = setOf("screen.off")).size)
        assertEquals(listOf(5L, 6L), hub.recent(limit = 2).map { it.atMs })
        assertTrue(hub.recent(kinds = setOf("toast.shown")).isEmpty())
    }

    @Test
    fun `payload travels intact to the subscriber`() {
        val hub = EventHub(clock)
        var got: JsonObject? = null
        hub.subscribe { got = it.payload; true }
        val payload = buildJsonObject { put("title", "hello") }
        hub.dispatch(TriggerEvent("notification.posted", pkg = "com.a", payload = payload, atMs = 1))
        assertEquals(payload, got)
        assertEquals("com.a", hub.recent().first().pkg)
    }
}
