package app.autohost.capabilities.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen reads mapping (P3-21) — updated for the P8-10 control surface. */
class ScreenProviderP3Test {

    class FakeState(
        var on: Boolean = true,
        var unlocked: Boolean = true,
        var secure: Boolean = false,
        var bright: Int = 120,
    ) : SystemState {
        var keyguardDismissed = false
        var brightnessSet: Int? = null
        override fun isOn(): Boolean = on
        override fun isUnlocked(): Boolean = unlocked
        override fun isSecure(): Boolean = secure
        override fun requestDismissKeyguard(): Boolean {
            keyguardDismissed = true
            return true
        }
        override fun brightness(): Int = bright
        override fun brightnessMode(): String = "manual"
        override fun setBrightness(level: Int): Boolean {
            brightnessSet = level
            bright = level * 255 / 100
            return true
        }
    }

    private fun call(method: String, args: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()), state: FakeState = FakeState()): app.autohost.core.model.Envelope {
        val ctx = app.autohost.core.broker.CallContext("r", "t", null, emptyMap(), 0)
        return kotlinx.coroutines.runBlocking { ScreenProvider(state).invoke(ctx, method, args) }
    }

    @Test fun `is on reflects power state`() {
        assertEquals(true, (call("is_on").result!!.jsonObject["on"] as kotlinx.serialization.json.JsonPrimitive).content.toBooleanStrict())
    }

    @Test fun `is unlocked reports lock and security together`() {
        val result = call("is_unlocked").result!!.jsonObject
        assertEquals(true, (result["unlocked"] as kotlinx.serialization.json.JsonPrimitive).content.toBooleanStrict())
        assertEquals(false, (result["secure"] as kotlinx.serialization.json.JsonPrimitive).content.toBooleanStrict())
    }

    @Test fun `reads map to the read permission`() {
        assertEquals("device.screen.read", ScreenProvider(FakeState()).permissionFor("is_on", kotlinx.serialization.json.JsonObject(emptyMap())))
    }

    @Test fun `unknown method is invalid args`() {
        assertEquals(app.autohost.core.model.ErrorCode.INVALID_ARGS.name, call("no_such").error?.code)
    }
}
