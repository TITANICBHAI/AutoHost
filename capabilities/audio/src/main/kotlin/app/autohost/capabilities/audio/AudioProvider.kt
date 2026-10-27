package app.autohost.capabilities.audio

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-09: the `device.audio` capability (plan §6.1 W2: "Volume, ringer, DND, TTS, play sound,
 * vibrate, flashlight"). DND state is honest read-only in v1 (changing it needs
 * notification-policy access, a special access screen — the fix action opens it).
 *
 * All framework calls sit behind [System] so the provider is JVM-testable.
 */
class AudioProvider(private val system: System) : CapabilityProvider {

    /** Audio framework seam (AndroidAudio in production). */
    interface System {
        fun volume(stream: String): Triple<Int, Int, Boolean> // (level, max, muted)
        fun setVolume(stream: String, level: Int): Boolean
        fun ringerMode(): String
        fun setRingerMode(mode: String): Boolean
        fun dndState(): Pair<Boolean, Int> // (on, interruption filter)
        fun speak(text: String, language: String?, queue: Boolean): Boolean
        fun vibrate(ms: Long): Boolean
        fun setTorch(on: Boolean): Boolean
        fun torchAvailable(): Boolean
        fun playTone(tone: String): Boolean
    }

    override val capabilityId: String = "device.audio"
    override val providerId: String = "audio.system"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in METHODS) "device.audio" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        return when (method) {
        "volume" -> {
            val stream = (args["stream"] as? JsonPrimitive)?.content ?: "music"
            if (stream !in STREAMS) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "stream must be one of ${STREAMS.joinToString("|")}")
            }
            val requested = (args["level"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (requested != null) {
                if (requested !in 0..100) {
                    return Envelope.err(ErrorCode.INVALID_ARGS, "level must be 0-100")
                }
                val (_, max, _) = system.volume(stream)
                system.setVolume(stream, requested * max / 100)
            }
            val (level, max, muted) = system.volume(stream)
            Envelope.ok(buildJsonObject {
                put("stream", stream)
                put("level", level)
                put("max", max)
                put("muted", muted)
                put("percent", if (max > 0) level * 100 / max else 0)
            })
        }
        "ringer" -> {
            val mode = (args["mode"] as? JsonPrimitive)?.content
            if (mode != null) {
                if (mode !in RINGER_MODES) {
                    return Envelope.err(ErrorCode.INVALID_ARGS, "mode must be ${RINGER_MODES.joinToString("|")}")
                }
                if (!system.setRingerMode(mode)) {
                    return Envelope.err(
                        ErrorCode.NEEDS_SETUP,
                        "this device refused the ringer change (DND/policy or OEM restriction)",
                        reason = "RINGER_CHANGE_REFUSED",
                        fixAction = "OPEN_DO_NOT_DISTURB_ACCESS",
                    )
                }
            }
            Envelope.ok(buildJsonObject { put("mode", system.ringerMode()) })
        }
        "dnd" -> {
            val (on, filter) = system.dndState()
            Envelope.ok(buildJsonObject { put("on", on); put("interrupted", filter) })
        }
        "speak" -> {
            val text = (args["text"] as? JsonPrimitive)?.content
                ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"text\" is required")
            if (text.isBlank()) return Envelope.err(ErrorCode.INVALID_ARGS, "text must not be blank")
            if (text.length > MAX_TTS_CHARS) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "text exceeds the $MAX_TTS_CHARS-char cap")
            }
            val language = (args["language"] as? JsonPrimitive)?.content
            val queue = (args["queue"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            if (!system.speak(text, language, queue)) {
                Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "no TTS engine is available on this device")
            } else {
                Envelope.ok(buildJsonObject { put("spoken", true) })
            }
        }
        "vibrate" -> {
            val ms = (args["ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 200L
            if (ms !in 0..10_000) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "ms must be 0-10000")
            }
            Envelope.ok(buildJsonObject { put("vibrated", system.vibrate(ms)) })
        }
        "flashlight" -> {
            val on = (args["on"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"on\" is required (bool)")
            if (on && !system.torchAvailable()) {
                return Envelope.err(
                    ErrorCode.CAPABILITY_UNAVAILABLE,
                    "the torch is unavailable (camera in use or no flash unit)",
                    reason = "FLASHLIGHT_UNAVAILABLE",
                )
            }
            Envelope.ok(buildJsonObject { put("on", system.setTorch(on)) })
        }
        "play_sound" -> {
            val tone = (args["tone"] as? JsonPrimitive)?.content ?: "beep"
            if (tone !in TONES) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "tone must be ${TONES.joinToString("|")}")
            }
            Envelope.ok(buildJsonObject { put("played", system.playTone(tone)) })
        }
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.audio method '$method'")
        }
    }

    companion object {
        val STREAMS = setOf("music", "ring", "notification", "alarm")
        val RINGER_MODES = setOf("normal", "silent", "vibrate")
        val TONES = setOf("beep", "confirm", "alert")
        const val MAX_TTS_CHARS = 4_000
        val METHODS = setOf("volume", "ringer", "dnd", "speak", "vibrate", "flashlight", "play_sound")
    }
}
