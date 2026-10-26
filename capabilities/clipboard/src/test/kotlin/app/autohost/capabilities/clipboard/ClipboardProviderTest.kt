package app.autohost.capabilities.clipboard

import app.autohost.core.broker.CallContext
import app.autohost.core.common.FakeClock
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8-05: focus-gated reads and always-allowed writes (plan §6.1 "Background read limits"). */
class ClipboardProviderTest {

    private val clock = FakeClock()
    private val store = object : ClipboardProvider.System {
        var value: String? = "initial"
        var written: String? = null
        override fun read(): String? = value
        override fun write(text: String): Boolean {
            written = text
            value = text
            return true
        }
    }

    private fun ctx() = CallContext(
        runId = "r", taskId = "t", profileId = null, grants = emptyMap(), nowMs = clock.nowMs(),
    )

    @Test
    fun `get refuses honestly without focus and points at the IME path`() = runBlocking {
        val provider = ClipboardProvider(store)
        provider.focusSource = { false }
        val result = provider.invoke(ctx(), "get", JsonObject(emptyMap()))
        assertEquals(false, result.ok)
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE.name, result.error?.code)
        assertTrue(result.error?.message.orEmpty().contains("device.ime.clipboard_get"))
    }

    @Test
    fun `get reads while focused`() = runBlocking {
        val provider = ClipboardProvider(store)
        provider.focusSource = { true }
        val result = provider.invoke(ctx(), "get", JsonObject(emptyMap()))
        assertEquals(true, result.ok)
        assertEquals("initial", ((result.result as kotlinx.serialization.json.JsonObject)["text"])?.jsonPrimitive?.content)
    }

    @Test
    fun `set always writes`() = runBlocking {
        val provider = ClipboardProvider(store)
        provider.focusSource = { false } // write is allowed without focus
        val result = provider.invoke(ctx(), "set", buildJsonObject { put("text", "hello") })
        assertEquals(true, result.ok)
        assertEquals("hello", store.written)
    }

    @Test
    fun `set validates args`() = runBlocking {
        val provider = ClipboardProvider(store)
        val missing = provider.invoke(ctx(), "set", JsonObject(emptyMap()))
        assertEquals(ErrorCode.INVALID_ARGS.name, missing.error?.code)
        val unknown = provider.invoke(ctx(), "clear", JsonObject(emptyMap()))
        assertEquals(ErrorCode.INVALID_ARGS.name, unknown.error?.code)
    }
}
