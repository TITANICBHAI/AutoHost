package app.autohost.capabilities.rawbridge

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Envelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8-12: the authorize handshake (the grant/switch gates run in the interceptor chain). */
class RawBridgeProviderTest {

    @Test fun `authorize returns the worker marker and arms the worker`() = runBlocking {
        var armed: Pair<String, Boolean>? = null
        val provider = RawBridgeProvider(workerPid = { 4242 }, workerArmer = { runId, on -> armed = runId to on })
        val result = provider.invoke(CallContext("run-1", "t", null, emptyMap(), 0), "authorize", JsonObject(emptyMap()))
        assertEquals(true, result.ok)
        assertEquals(true, result.result!!.jsonObject["authorized"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals(4242, result.result!!.jsonObject["worker_pid"]!!.jsonPrimitive.content.toInt())
        assertEquals("run-1" to true, armed)
    }

    @Test fun `unknown method is invalid args and permission maps to the capability`() = runBlocking {
        val provider = RawBridgeProvider(workerPid = { 1 })
        val result = provider.invoke(CallContext("r", "t", null, emptyMap(), 0), "jclass", JsonObject(emptyMap()))
        assertEquals("INVALID_ARGS", result.error?.code)
        assertEquals("android.api.direct", provider.permissionFor("authorize", JsonObject(emptyMap())))
        assertEquals("T2", provider.tier)
    }
}
