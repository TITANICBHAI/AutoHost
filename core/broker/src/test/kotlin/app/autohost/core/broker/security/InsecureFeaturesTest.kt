package app.autohost.core.broker.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P8-13: switch policy tests (Gate 8-b). Defaults off, deny wins over relaxed, enabled beats
 * default, unrelated capabilities pass, stale ids are dropped on parse.
 */
class InsecureFeaturesTest {

    private val off = InsecureFeatures.parseState(false, null, null)

    @Test
    fun `everything is off by default`() {
        for (cap in listOf("device.shell", "android.api.direct", "files.all", "device.sms")) {
            assertEquals(
                "$cap must be disabled by default",
                InsecureFeatures.Decision.Disabled(InsecureFeatures.featureFor(cap, null)!!),
                InsecureFeatures.evaluate(cap, null, off),
            )
        }
    }

    @Test
    fun `low risk capabilities pass without any switch`() {
        assertEquals(InsecureFeatures.Decision.Allowed, InsecureFeatures.evaluate("device.ui", "device.ui.act", off))
        assertEquals(InsecureFeatures.Decision.Allowed, InsecureFeatures.evaluate("net.http", null, off))
        assertNull(InsecureFeatures.featureFor("device.ui", null))
    }

    @Test
    fun `permission mapping wins for mixed families`() {
        // device.app.query stays unswitched even when the manage method is gated
        assertEquals("app_manage", InsecureFeatures.featureFor("device.app", "device.app.manage"))
        assertNull(InsecureFeatures.featureFor("device.app", "device.app.query"))
    }

    @Test
    fun `explicit enable allows the call`() {
        val state = InsecureFeatures.parseState(false, """["raw_api"]""", null)
        assertEquals(InsecureFeatures.Decision.Allowed, InsecureFeatures.evaluate("android.api.direct", null, state))
        // ...and only for the enabled feature
        assertEquals(
            InsecureFeatures.Decision.Disabled("shell"),
            InsecureFeatures.evaluate("device.shell", null, state),
        )
    }

    @Test
    fun `deny always wins - over relaxed and over enabled`() {
        val state = InsecureFeatures.parseState(true, """["raw_api"]""", """["raw_api"]""")
        assertEquals(
            InsecureFeatures.Decision.Disabled("raw_api"),
            InsecureFeatures.evaluate("android.api.direct", null, state),
        )
    }

    @Test
    fun `relaxed mode enables every known feature except denies`() {
        val state = InsecureFeatures.parseState(true, null, null)
        for (cap in listOf("device.shell", "android.api.direct", "files.all")) {
            assertEquals("relaxed must cover $cap", InsecureFeatures.Decision.Allowed, InsecureFeatures.evaluate(cap, null, state))
        }
        val denied = InsecureFeatures.parseState(true, null, """["shell"]""")
        assertEquals(InsecureFeatures.Decision.Disabled("shell"), InsecureFeatures.evaluate("device.shell", null, denied))
    }

    @Test
    fun `unknown and stale ids are dropped on parse`() {
        val state = InsecureFeatures.parseState(false, """["raw_api","nope"]""", """["also-nope"]""")
        assertEquals(setOf("raw_api"), state.enabled)
        assertEquals(emptySet<String>(), state.denied)
    }

    @Test
    fun `malformed json parses to an empty off state`() {
        val state = InsecureFeatures.parseState(false, "{not json", null)
        assertEquals(emptySet<String>(), state.enabled)
        assertEquals(
            InsecureFeatures.Decision.Disabled("raw_api"),
            InsecureFeatures.evaluate("android.api.direct", null, state),
        )
    }
}
