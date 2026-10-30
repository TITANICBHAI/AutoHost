package app.autohost.core.broker

import app.autohost.core.broker.interceptor.InMemoryAuditSink
import app.autohost.core.common.ApiGate
import app.autohost.core.common.FakeClock
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A provider with scripted availability and a recordable invoke, for pipeline tests. */
class FakeProvider(
    override val capabilityId: String,
    override val tier: String = "T0",
    var availability: Availability = Availability.Available,
    private val permissionByMethod: Map<String, String> = emptyMap(),
) : CapabilityProvider {
    override val providerId: String = "fake-$capabilityId"
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var result: (String, JsonObject) -> Envelope = { method, _ -> Envelope.ok(buildJsonObject { put("method", method) }) }

    override fun availability(env: DeviceEnv): Availability = this.availability

    override fun permissionFor(method: String, args: JsonObject): String? = permissionByMethod[method]

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        calls.add(method to args)
        return result(method, args)
    }
}

class BrokerPipelineTest {
    private val clock = FakeClock()
    private val tokens = RunTokenStore()
    private val audit = InMemoryAuditSink()
    private lateinit var registry: CapabilityRegistry
    private lateinit var provider: FakeProvider
    private lateinit var broker: BrokerService
    private var token = ""

    @Before
    fun setUp() {
        ApiGate.install(30)
        registry = CapabilityRegistry()
        provider = FakeProvider(
            capabilityId = "store",
            permissionByMethod = mapOf(
                "get" to "store.profile",
                "set" to "store.task",
            ),
        )
        registry.register(provider)
        broker = BrokerService(registry, tokens, DeviceEnv(sdkInt = 30), clock, audit)
    }

    @After
    fun tearDown() = ApiGate.reset()

    private fun grants(vararg permissions: String, scope: JsonObject = buildJsonObject {}): Map<String, JsonObject> =
        permissions.associateWith { scope }

    private fun issue(vararg permissions: String, scope: JsonObject = buildJsonObject {}): String =
        tokens.issue(RunGrants("run-1", "task-1", null, grants(*permissions, scope = scope), clock.nowMs()))

    private fun restrictedScope(vararg packages: String): JsonObject = buildJsonObject {
        put("packages", kotlinx.serialization.json.JsonArray(packages.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }

    @Test
    fun `given valid token and grant when call then provider invoked and audit written`() = runTest {
        token = issue("store.profile")
        val envelope = broker.call(token, "store", "get", buildJsonObject { put("scope", "profile"); put("key", "k") })
        assertTrue(envelope.ok)
        assertEquals(1, provider.calls.size)
        assertEquals("get", provider.calls.first().first)
        assertEquals(1, audit.snapshot().size)
        assertTrue(audit.snapshot().first().ok)
        assertEquals("run-1", audit.snapshot().first().runId)
    }

    @Test
    fun `given unknown token when call then permission denied with unknown run token reason`() = runTest {
        val envelope = broker.call("not-a-token", "store", "get", JsonObject(emptyMap()))
        assertEquals(false, envelope.ok)
        assertEquals(ErrorCode.PERMISSION_DENIED.name, envelope.error?.code)
        assertEquals("UNKNOWN_RUN_TOKEN", envelope.error?.reason)
        assertEquals(0, provider.calls.size)
    }

    @Test
    fun `given permission not granted when call then denied with review fix action`() = runTest {
        token = issue("store.task") // grants store.task, call needs store.profile
        val envelope = broker.call(token, "store", "get", buildJsonObject { put("scope", "profile"); put("key", "k") })
        assertEquals(ErrorCode.PERMISSION_DENIED.name, envelope.error?.code)
        assertEquals("CAPABILITY_NOT_GRANTED", envelope.error?.reason)
        assertEquals("OPEN_PERMISSION_REVIEW", envelope.error?.fixAction)
    }

    @Test
    fun `given out of scope package when call then scope violation`() = runTest {
        token = issue("store.profile", scope = restrictedScope("com.allowed"))
        val envelope = broker.call(token, "store", "get", buildJsonObject {
            put("scope", "profile"); put("key", "k"); put("package", "com.elsewhere")
        })
        assertEquals(ErrorCode.SCOPE_VIOLATION.name, envelope.error?.code)
        assertEquals(0, provider.calls.size)
    }

    @Test
    fun `given in scope package when call then passes scope check`() = runTest {
        token = issue("store.profile", scope = restrictedScope("com.allowed"))
        val envelope = broker.call(token, "store", "get", buildJsonObject {
            put("scope", "profile"); put("key", "k"); put("package", "com.allowed")
        })
        assertTrue(envelope.ok)
    }

    @Test
    fun `given unavailable provider when call then capability unavailable`() = runTest {
        provider.availability = Availability.Unsupported(reason = "SHELL_NOT_PAIRED")
        token = issue("store.profile")
        val envelope = broker.call(token, "store", "get", buildJsonObject { put("scope", "profile") })
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE.name, envelope.error?.code)
        assertEquals("SHELL_NOT_PAIRED", envelope.error?.reason)
    }

    @Test
    fun `given needs setup provider when call then needs setup with fix action`() = runTest {
        provider.availability = Availability.NeedsSetup("A11Y_SERVICE_DISABLED", "OPEN_ACCESSIBILITY_SETTINGS")
        token = issue("store.profile")
        val envelope = broker.call(token, "store", "get", buildJsonObject { put("scope", "profile") })
        assertEquals(ErrorCode.NEEDS_SETUP.name, envelope.error?.code)
        assertEquals("A11Y_SERVICE_DISABLED", envelope.error?.reason)
        assertEquals("OPEN_ACCESSIBILITY_SETTINGS", envelope.error?.fixAction)
    }

    @Test
    fun `given unknown method when call then invalid args`() = runTest {
        token = issue("store.profile")
        val envelope = broker.call(token, "store", "nope", buildJsonObject { put("scope", "profile") })
        assertEquals(ErrorCode.INVALID_ARGS.name, envelope.error?.code)
        assertEquals("UNKNOWN_METHOD", envelope.error?.reason)
    }

    @Test
    fun `given unknown capability when call then capability unavailable with no provider`() = runTest {
        token = issue("store.profile")
        val envelope = broker.call(token, "device.ghost", "do", JsonObject(emptyMap()))
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE.name, envelope.error?.code)
        assertEquals("NO_PROVIDER", envelope.error?.reason)
    }

    @Test
    fun `given provider throws when call then internal envelope not exception`() = runTest {
        token = issue("store.profile")
        provider.result = { _, _ -> throw IllegalStateException("boom") }
        val envelope = broker.call(token, "store", "get", buildJsonObject { put("scope", "profile") })
        assertEquals(false, envelope.ok)
        assertEquals(ErrorCode.INTERNAL.name, envelope.error?.code)
    }

    @Test
    fun `given unrestricted grant when call with any package then allowed`() = runTest {
        token = issue("store.profile") // empty grant object = unrestricted
        val envelope = broker.call(token, "store", "get", buildJsonObject {
            put("scope", "profile"); put("package", "com.anything")
        })
        assertTrue(envelope.ok)
    }

    @Test
    fun `given describe with valid token then capabilities listed`() {
        token = issue("store.profile")
        val json = EnvelopeCodec.decodeEnvelope(null) // smoke: codec null path
        assertNull(json)
        val described = broker.describe(token)
        val store = described["capabilities"]!!.let { it as JsonObject }["store"]
        assertNotNull(store)
    }
}
