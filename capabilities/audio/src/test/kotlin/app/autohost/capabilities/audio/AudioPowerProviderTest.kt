package app.autohost.capabilities.audio

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
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8-09: audio + power provider behaviour over fake framework seams. */
class AudioPowerProviderTest {

    private class FakeAudio : AudioProvider.System {
        val volumes = mutableMapOf("music" to Triple(8, 15, false), "ring" to Triple(5, 8, false))
        var ringer = "normal"
        var ringerChangeOk = true
        val tts = mutableListOf<String>()
        val vibrations = mutableListOf<Long>()
        var torch = false
        var torchOk = true
        val tones = mutableListOf<String>()
        override fun volume(stream: String) = volumes[stream] ?: Triple(0, 15, true)
        override fun setVolume(stream: String, level: Int): Boolean {
            val (_, max, _) = volumes[stream] ?: return false
            volumes[stream] = Triple(level, max, level == 0)
            return true
        }
        override fun ringerMode() = ringer
        override fun setRingerMode(mode: String): Boolean {
            if (!ringerChangeOk) return false
            ringer = mode
            return true
        }
        override fun dndState() = false to 0
        override fun speak(text: String, language: String?, queue: Boolean): Boolean {
            tts.add(text)
            return true
        }
        override fun vibrate(ms: Long): Boolean {
            vibrations.add(ms)
            return true
        }
        override fun setTorch(on: Boolean): Boolean {
            torch = on
            return true
        }
        override fun torchAvailable() = torchOk
        override fun playTone(tone: String): Boolean {
            tones.add(tone)
            return true
        }
    }

    private class FakePower : PowerProvider.System {
        var battery = PowerProvider.System.Battery(80, false, "none", "good")
        val wakeLocks = mutableListOf<Pair<String, String?>>()
        val released = mutableListOf<String>()
        val keepAwake = mutableListOf<Pair<String, Boolean>>()
        override fun battery() = battery
        override fun acquireWakeLock(runId: String, reason: String?): Boolean {
            wakeLocks.add(runId to reason)
            return true
        }
        override fun releaseWakeLock(runId: String): Boolean {
            released.add(runId)
            return true
        }
        override fun setKeepAwake(runId: String, on: Boolean): Boolean {
            keepAwake.add(runId to on)
            return true
        }
    }

    private fun ctx() = CallContext("run-1", "task-1", null, emptyMap(), 0)

    private fun AudioProvider.call(method: String, args: JsonObject = JsonObject(emptyMap())): Envelope =
        runBlocking { invoke(ctx(), method, args) }

    private fun PowerProvider.call(method: String, args: JsonObject = JsonObject(emptyMap())): Envelope =
        runBlocking { invoke(ctx(), method, args) }

    @Test fun `volume reads and sets with honest percent`() {
        val audio = FakeAudio()
        val provider = AudioProvider(audio)
        val read = provider.call("volume", buildJsonObject { put("stream", "music") })
        assertEquals(8, read.result!!.jsonObject["level"]!!.jsonPrimitive.content.toInt())
        assertEquals(53, read.result!!.jsonObject["percent"]!!.jsonPrimitive.content.toInt())

        val set = provider.call("volume", buildJsonObject { put("stream", "music"); put("level", 100) })
        assertEquals(15, set.result!!.jsonObject["level"]!!.jsonPrimitive.content.toInt())
        assertEquals(100, set.result!!.jsonObject["percent"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `volume validates stream and level`() {
        val provider = AudioProvider(FakeAudio())
        assertEquals(ErrorCode.INVALID_ARGS.name, provider.call("volume", buildJsonObject { put("stream", "x") }).error?.code)
        assertEquals(ErrorCode.INVALID_ARGS.name, provider.call("volume", buildJsonObject { put("stream", "music"); put("level", 200) }).error?.code)
    }

    @Test fun `ringer refused is a typed needs-setup`() {
        val audio = FakeAudio()
        audio.ringerChangeOk = false
        val provider = AudioProvider(audio)
        val refused = provider.call("ringer", buildJsonObject { put("mode", "vibrate") })
        assertEquals(ErrorCode.NEEDS_SETUP.name, refused.error?.code)
    }

    @Test fun `speak vibrate flashlight and tones`() {
        val audio = FakeAudio()
        val provider = AudioProvider(audio)
        assertEquals(true, provider.call("speak", buildJsonObject { put("text", "hello") }).result!!.jsonObject["spoken"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals(listOf("hello"), audio.tts)
        assertTrue(provider.call("vibrate", buildJsonObject { put("ms", 150) }).result!!.jsonObject["vibrated"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals(listOf(150L), audio.vibrations)
        assertTrue(provider.call("play_sound", buildJsonObject { put("tone", "confirm") }).result!!.jsonObject["played"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("confirm", audio.tones.single())
    }

    @Test fun `flashlight unavailable is honest when the torch is not`() {
        val audio = FakeAudio()
        audio.torchOk = false
        val provider = AudioProvider(audio)
        val refused = provider.call("flashlight", buildJsonObject { put("on", true) })
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE.name, refused.error?.code)
        assertEquals("FLASHLIGHT_UNAVAILABLE", refused.error?.reason)
    }

    @Test fun `battery wake lock and keep awake`() {
        val power = FakePower()
        val provider = PowerProvider(power)
        val battery = provider.call("battery")
        assertEquals(80, battery.result!!.jsonObject["level"]!!.jsonPrimitive.content.toInt())
        assertTrue(provider.call("wake_lock", buildJsonObject { put("reason", "long job") }).result!!.jsonObject["held"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("run-1" to "long job", power.wakeLocks.single())
        assertTrue(provider.call("wake_lock", buildJsonObject { put("hold", false) }).result!!.jsonObject["held"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("run-1", power.released.single())
        assertTrue(provider.call("keep_awake", buildJsonObject { put("on", true) }).result!!.jsonObject["on"]!!.jsonPrimitive.content.toBooleanStrict())
    }

    @Test fun `releaseAll drops the run locks on the terminal transition`() {
        val power = FakePower()
        PowerProvider.releaseAll(power, "run-7")
        assertEquals("run-7", power.released.single())
        assertEquals("run-7" to false, power.keepAwake.single())
    }

    @Test fun `unknown methods are invalid args`() {
        assertEquals(ErrorCode.INVALID_ARGS.name, AudioProvider(FakeAudio()).call("x").error?.code)
        assertEquals(ErrorCode.INVALID_ARGS.name, PowerProvider(FakePower()).call("x").error?.code)
    }
}
