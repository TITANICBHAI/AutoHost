package app.autohost.capabilities.intents

import app.autohost.core.broker.CallContext
import app.autohost.core.common.FakeClock
import app.autohost.core.model.Availability
import app.autohost.core.model.ErrorCode
import app.autohost.core.model.Envelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8-06: the fail-closed intent allowlist (plan §6.1). */
class IntentPolicyTest {

    @Test
    fun `urls accept only http and https`() {
        assertEquals(IntentPolicy.Verdict.Allow, IntentPolicy.checkUrl("https://example.com"))
        assertEquals(IntentPolicy.Verdict.Allow, IntentPolicy.checkUrl("http://example.com/x"))
        assertTrue(IntentPolicy.checkUrl("intent://x") is IntentPolicy.Verdict.Deny)
        assertTrue(IntentPolicy.checkUrl("file:///etc/passwd") is IntentPolicy.Verdict.Deny)
    }

    @Test
    fun `settings pages are a closed map`() {
        assertEquals(IntentPolicy.Verdict.Allow, IntentPolicy.checkSettingsPage("wifi"))
        assertTrue(IntentPolicy.checkSettingsPage("root") is IntentPolicy.Verdict.Deny)
    }

    @Test
    fun `activities need the grant unless they are http(s) VIEW`() {
        val empty = IntentPolicy.Scope()
        assertTrue(IntentPolicy.checkActivity("com.example.RUN", null, null, empty) is IntentPolicy.Verdict.Deny)
        val scoped = IntentPolicy.Scope(actions = setOf("com.example.RUN"), packages = setOf("com.example"))
        assertEquals(IntentPolicy.Verdict.Allow, IntentPolicy.checkActivity("com.example.RUN", null, null, scoped))
        assertEquals(IntentPolicy.Verdict.Allow, IntentPolicy.checkActivity(null, "com.example", null, scoped))
        assertTrue(IntentPolicy.checkActivity(null, "com.other", null, scoped) is IntentPolicy.Verdict.Deny)
        // The one built-in convenience: VIEW over http/https.
        assertEquals(IntentPolicy.Verdict.Allow, IntentPolicy.checkActivity("android.intent.action.VIEW", null, "https://a.b", empty))
        assertTrue(IntentPolicy.checkActivity("android.intent.action.VIEW", null, "content://x", empty) is IntentPolicy.Verdict.Deny)
    }

    @Test
    fun `broadcasts need an explicit action grant`() {
        val empty = IntentPolicy.Scope()
        assertTrue(IntentPolicy.checkBroadcast("com.example.PING", empty) is IntentPolicy.Verdict.Deny)
        val scoped = IntentPolicy.Scope(actions = setOf("com.example.PING"))
        assertEquals(IntentPolicy.Verdict.Allow, IntentPolicy.checkBroadcast("com.example.PING", scoped))
        assertTrue(IntentPolicy.checkBroadcast(null, scoped) is IntentPolicy.Verdict.Deny)
    }
}

/** Provider behaviour with a recording dispatch seam. */
class IntentProviderTest {

    private val clock = FakeClock()

    private fun ctx(scope: JsonObject? = null) = CallContext(
        runId = "r", taskId = "t", profileId = null,
        grants = if (scope == null) emptyMap() else mapOf("device.intent" to scope),
        nowMs = clock.nowMs(),
    )

    private class RecordingDispatch : IntentProvider.Dispatch {
        var started = 0
        var sent = 0
        override fun startActivity(builder: (android.content.Intent) -> android.content.Intent): Boolean {
            started += 1
            return true
        }

        override fun broadcast(builder: (android.content.Intent) -> android.content.Intent): Boolean {
            sent += 1
            return true
        }
    }

    @Test
    fun `open_url dispatches http https and denies others`() = runBlocking {
        val dispatch = RecordingDispatch()
        val provider = IntentProvider(dispatch)
        val ok = provider.invoke(ctx(), "open_url", buildJsonObject { put("url", "https://example.com") })
        assertEquals(true, ok.ok)
        assertEquals(1, dispatch.started)
        val bad = provider.invoke(ctx(), "open_url", buildJsonObject { put("url", "intent://x") })
        assertEquals(ErrorCode.SCOPE_VIOLATION.name, bad.error?.code)
        assertEquals(1, dispatch.started)
    }

    @Test
    fun `start_activity respects the grant scope`() = runBlocking {
        val dispatch = RecordingDispatch()
        val provider = IntentProvider(dispatch)
        val denied = provider.invoke(
            ctx(), "start_activity",
            buildJsonObject { put("action", "com.example.RUN") },
        )
        assertEquals(ErrorCode.SCOPE_VIOLATION.name, denied.error?.code)
        val allowed = provider.invoke(
            ctx(
                buildJsonObject {
                    put("actions", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("com.example.RUN"))))
                },
            ),
            "start_activity",
            buildJsonObject { put("action", "com.example.RUN") },
        )
        assertEquals(true, allowed.ok)
        assertEquals(1, dispatch.started)
    }

    @Test
    fun `availability is always available`() {
        val provider = IntentProvider(RecordingDispatch())
        assertEquals(Availability.Available, provider.availability(app.autohost.core.broker.DeviceEnv(sdkInt = 29)))
    }
}
