package app.autohost.core.engine

import app.autohost.core.broker.PermissionIndex
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.engine.plugins.PluginHost
import app.autohost.core.engine.plugins.PluginProvider
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11-02 (ADR-079): the pure plugin-host contract — describe() parsing, the api-level gate,
 * collision rejection, and the provider passthroughs. The AIDL/ServiceConnection half is the
 * app-side PluginController (UNVERIFIED until a real plugin APK is installed).
 */
class PluginHostTest {

    private class FakeBinder(
        private val api: Int = 1,
        private val describe: String,
        private val availability: String = """{"state":"available"}""",
        private val envelope: String = """{"ok":true,"result":{"now":123}}""",
        private val availabilityThrows: Boolean = false,
        private val invokeThrows: Boolean = false,
    ) : PluginHost.PluginBinder {
        override fun apiLevel() = api
        override fun describe() = describe
        override fun availability(envJson: String): String {
            if (availabilityThrows) throw IllegalStateException("binder gone")
            return availability
        }
        override fun invoke(callJson: String): String {
            if (invokeThrows) throw IllegalStateException("plugin crashed")
            return envelope
        }
    }

    private val describeOk = """
        [{"capability":"plugin.example.clock","tier":"T4","title":"Example clock",
          "methods":[{"name":"now","permission":"plugin.example.clock.use","title":"Now"},
                     {"name":"tick","permission":"plugin.example.clock.use"}]}]
    """.trimIndent()

    // ---- attach: the happy path -----------------------------------------------------------

    @Test
    fun attachProducesOneProviderPerDeclaredCapabilityAndPermissionFragment() {
        val result = PluginHost.attach("com.example.plugin", FakeBinder(describe = describeOk), takenCapabilityIds = emptySet())
        assertTrue(result.ok)
        val attached = result.attached!!
        assertEquals(listOf("plugin.example.clock"), attached.capabilities.map { it.capabilityId })
        assertEquals(1, attached.providers.size)
        assertTrue(attached.providers[0] is PluginProvider)
        assertEquals(mapOf("plugin.example.clock" to listOf("plugin.example.clock.use", "plugin.example.clock.use")), attached.permissionFragment)
    }

    // ---- attach: fail-closed gates ---------------------------------------------------------

    @Test
    fun apiLevelOutsideTheBandIsRejected() {
        val result = PluginHost.attach("p", FakeBinder(api = 99, describe = describeOk), emptySet())
        assertEquals(listOf<PluginHost.AttachIssue>(PluginHost.AttachIssue.PluginApi(99)), result.issues)
        assertNull(result.attached)
        val tooOld = PluginHost.attach("p", FakeBinder(api = 0, describe = describeOk), emptySet())
        assertTrue(tooOld.issues[0] is PluginHost.AttachIssue.PluginApi)
    }

    @Test
    fun malformedDescribeFailsTheWholePlugin() {
        for (bad in listOf(
            "not json",
            """{"capability":"plugin.x"}""",                       // object, not array
            """[]""",                                              // declares nothing
            """[{"capability":"plugin.x","methods":[]}]""",        // no methods
            """[{"capability":"plugin.x","methods":[{"name":"a"}]}]""", // permission missing
            """[{"capability":"host.device.ui","methods":[{"name":"a","permission":"p.x"}]}]""", // not namespaced
            """[{"capability":"plugin.x","methods":[{"name":"A","permission":"p.x"}]}]""", // bad method name
            """[{"capability":"plugin.x","methods":[{"name":"a","permission":"p.x"}],"extra":1}]""", // unknown key
        )) {
            val result = PluginHost.attach("p", FakeBinder(describe = bad), emptySet())
            assertTrue("expected Malformed for: $bad", result.issues.isNotEmpty() && result.issues[0] is PluginHost.AttachIssue.Malformed)
            assertNull(result.attached)
        }
    }

    @Test
    fun collisionWithAHostCapabilityIsRejectedNotSilentlyChained() {
        val result = PluginHost.attach("p", FakeBinder(describe = describeOk), takenCapabilityIds = setOf("plugin.example.clock"))
        assertEquals(listOf<PluginHost.AttachIssue>(PluginHost.AttachIssue.Collision("plugin.example.clock")), result.issues)
        assertNull(result.attached)
    }

    // ---- describe parsing edge cases -------------------------------------------------------

    @Test
    fun duplicateMethodNamesAreRejected() {
        val describe = """[{"capability":"plugin.x","methods":[{"name":"a","permission":"p.one"},{"name":"a","permission":"p.two"}]}]"""
        assertNull(PluginHost.parseDescribe(describe))
    }

    @Test
    fun tierAndTitleAreOptional() {
        val describe = """[{"capability":"plugin.x","methods":[{"name":"a","permission":"plugin.x.use"}]}]"""
        val parsed = PluginHost.parseDescribe(describe)
        assertNotNull(parsed)
        assertEquals("T4", parsed!![0].tier)
        assertNull(parsed[0].title)
    }

    // ---- the provider passthroughs ----------------------------------------------------------

    @Test
    fun availabilityParsesAllThreeStatesAndHonestFailures() {
        fun provider(availability: String, throws: Boolean = false): PluginProvider =
            PluginProvider("p", PluginHost.parseDescribe(describeOk)!![0], FakeBinder(describe = describeOk, availability = availability, availabilityThrows = throws))

        assertEquals(Availability.Available, provider("""{"state":"available"}""").availability(DeviceEnv(sdkInt = 29)))
        val needsSetup = provider("""{"state":"needs_setup","reason":"DRIVER_OFF","fix_action":"OPEN_PLUGIN_SETTINGS"}""").availability(DeviceEnv(sdkInt = 29))
        assertTrue(needsSetup is Availability.NeedsSetup && needsSetup.reason == "DRIVER_OFF")
        val unsupported = provider("""{"state":"unsupported","reason":"NO_CAST"}""").availability(DeviceEnv(sdkInt = 29))
        assertTrue(unsupported is Availability.Unsupported && unsupported.reason == "NO_CAST")
        // A malformed / throwing binder can never look available.
        assertTrue(provider("garbage").availability(DeviceEnv(sdkInt = 29)) is Availability.Unsupported)
        assertTrue(provider("""{"state":"available"}""", throws = true).availability(DeviceEnv(sdkInt = 29)) is Availability.Unsupported)
    }

    @Test
    fun permissionForComesFromTheDeclarationAndUnknownMethodsAreNull() {
        val provider = PluginProvider("p", PluginHost.parseDescribe(describeOk)!![0], FakeBinder(describe = describeOk))
        assertEquals("plugin.example.clock.use", provider.permissionFor("now", kotlinx.serialization.json.JsonObject(emptyMap())))
        assertNull(provider.permissionFor("nope", kotlinx.serialization.json.JsonObject(emptyMap())))
    }

    @Test
    fun invokeUnwrapsThePluginEnvelopeAndMapsFailuresToTypedErrors() = runBlocking {
        val provider = PluginProvider("p", PluginHost.parseDescribe(describeOk)!![0], FakeBinder(describe = describeOk))
        val ctx = app.autohost.core.broker.CallContext("run-1", "task", null, emptyMap(), 0L)
        val ok = provider.invoke(ctx, "now", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertTrue(ok.ok)
        assertEquals(123L, (ok.result as? JsonPrimitive)?.content?.toLong())
        val unknown = provider.invoke(ctx, "nope", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(app.autohost.core.model.ErrorCode.INVALID_ARGS, unknown.error?.code)
        val crashed = PluginProvider("p", PluginHost.parseDescribe(describeOk)!![0], FakeBinder(describe = describeOk, invokeThrows = true))
            .invoke(ctx, "now", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(app.autohost.core.model.ErrorCode.CAPABILITY_UNAVAILABLE, crashed.error?.code)
        val malformed = PluginProvider("p", PluginHost.parseDescribe(describeOk)!![0], FakeBinder(describe = describeOk, envelope = "not-an-envelope"))
            .invoke(ctx, "now", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(app.autohost.core.model.ErrorCode.INTERNAL, malformed.error?.code)
        assertTrue(malformed.error?.reason == "PLUGIN_ENVELOPE_MALFORMED")
    }

    // ---- the permission index merge ---------------------------------------------------------

    @Test
    fun mergedIndexLetsPluginNeedsResolveButNeverShadowsTheHost() {
        val host = PermissionIndex.parse(
            """
            {"capabilities":[
              {"id":"device.ui","permissions":[{"id":"device.ui.act"},{"id":"device.ui.read"}]}
            ]}
            """.trimIndent(),
        )
        val merged = host.mergedWith(mapOf("plugin.example.clock" to listOf("plugin.example.clock.use")))
        assertEquals(listOf("plugin.example.clock.use"), merged.permissionsOf("plugin.example.clock"))
        assertEquals("plugin.example.clock", merged.capabilityOf("plugin.example.clock.use"))
        assertEquals("device.ui", merged.capabilityOf("device.ui.act"))
        // A plugin fragment cannot shadow a host capability or permission.
        val shadow = host.mergedWith(mapOf("device.ui" to listOf("plugin.x.use")))
        assertEquals(setOf("device.ui.act", "device.ui.read"), shadow.permissionsOf("device.ui").toSet())
        // Merging an empty fragment is the identity.
        assertEquals(merged.allPermissions(), merged.mergedWith(emptyMap()).allPermissions())
    }
}
