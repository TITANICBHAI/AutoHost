package app.autohost.capabilities.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator

/** The Android implementation of [AudioProvider.System] (AudioManager + TTS + torch). */
class AndroidAudio(private val appContext: Context) : AudioProvider.System {

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile
    var tts: android.speech.tts.TextToSpeech? = null

    @Volatile
    var ttsReady = false

    /** Called once from AppGraph init: TTS warms up asynchronously and honestly reports readiness. */
    fun initTts(onReady: (Boolean) -> Unit = {}) {
        try {
            tts = android.speech.tts.TextToSpeech(appContext) { status ->
                ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
                onReady(ttsReady)
            }
        } catch (e: Exception) {
            app.autohost.core.common.AhLog.w("audio", "tts init failed: ${e.message}")
            ttsReady = false
        }
    }

    override fun volume(stream: String): Triple<Int, Int, Boolean> {
        val am = audioManager ?: return Triple(0, 0, true)
        val streamId = streamConstant(stream)
        val max = am.getStreamMaxVolume(streamId)
        val level = am.getStreamVolume(streamId)
        val muted = level == 0
        return Triple(level, max, muted)
    }

    override fun setVolume(stream: String, level: Int): Boolean {
        return try {
            val am = audioManager ?: return false
            am.setStreamVolume(streamConstant(stream), level, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun ringerMode(): String = when (audioManager?.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    override fun setRingerMode(mode: String): Boolean {
        return try {
            val am = audioManager ?: return false
            am.ringerMode = when (mode) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun dndState(): Pair<Boolean, Int> = try {
        val nm = appContext.getSystemService(android.app.NotificationManager::class.java)
        val filter = nm?.currentInterruptionFilter ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        (filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL) to filter
    } catch (e: Exception) {
        false to 0
    }

    override fun speak(text: String, language: String?, queue: Boolean): Boolean {
        return try {
            val engine = tts ?: return false
            if (!ttsReady) return false
            language?.let { engine.language = java.util.Locale.forLanguageTag(it) }
            val mode = if (queue) {
                android.speech.tts.TextToSpeech.QUEUE_ADD
            } else {
                android.speech.tts.TextToSpeech.QUEUE_FLUSH
            }
            engine.speak(text, mode, null, "autohost-${System.nanoTime()}")
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun vibrate(ms: Long): Boolean {
        return try {
            val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return false
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun setTorch(on: Boolean): Boolean {
        return try {
            val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                ?: return false
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cameraManager.setTorchMode(cameraId, on)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun torchAvailable(): Boolean {
        return try {
            val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                ?: return false
            cameraManager.cameraIdList.any { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun playTone(tone: String): Boolean = try {
        val type = when (tone) {
            "confirm" -> ToneGenerator.TONE_PROP_ACK
            "alert" -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            else -> ToneGenerator.TONE_PROP_BEEP
        }
        val gen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        gen.startTone(type, 200)
        Thread {
            Thread.sleep(400)
            runCatching { gen.release() }
        }.start()
        true
    } catch (e: Exception) {
        false
    }

    private fun streamConstant(stream: String): Int = when (stream) {
        "ring" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "alarm" -> AudioManager.STREAM_ALARM
        else -> AudioManager.STREAM_MUSIC
    }
}
