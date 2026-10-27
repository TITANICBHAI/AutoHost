package app.autohost.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import app.autohost.capabilities.data.DataPolicy
import app.autohost.capabilities.data.HardwareSeams
import java.io.File

/**
 * P10-07 (ADR-073): the Android seam implementations for the hardware cluster. Everything is
 * one-shot and user-visible (a camera shot, a bounded recording, a single location fix) —
 * no background streams, no stealth surfaces.
 */
class AndroidLocation(private val context: Context) : HardwareSeams.LocationSource {

    private val manager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun hasFine(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true // pre-29 background access follows the foreground permission
        }

    override fun backgroundAllowed(): Boolean = hasBackgroundLocationPermission()

    override fun lastKnown(): HardwareSeams.LocFix? {
        if (!hasFine()) return null
        val best = manager.allProviders.asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return best.toFix()
    }

    override fun current(timeoutMs: Long): HardwareSeams.LocFix? {
        if (!hasFine()) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var fix: Location? = null
        val listener = LocationListener { location ->
            fix = location
            latch.countDown()
        }
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER to 10_000L,
            LocationManager.GPS_PROVIDER to 20_000L,
        ).filter { manager.isProviderEnabled(it.first) }
        try {
            providers.forEach { (provider, minMs) ->
                runCatching { manager.requestLocationUpdates(provider, minMs, 0f, listener, context.mainLooper) }
            }
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } finally {
            runCatching { manager.removeUpdates(listener) }
        }
        return fix?.toFix() ?: lastKnown()
    }

    private fun Location.toFix() = HardwareSeams.LocFix(
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracy.toDouble(),
        altitudeM = if (hasAltitude()) altitude else null,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        timeMs = time,
        provider = provider ?: "unknown",
    )
}

class AndroidCamera(private val context: Context, private val outputDir: File) : HardwareSeams.Camera {

    override fun capture(front: Boolean): HardwareSeams.Capture? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val camera = try {
            if (front) openFront() ?: Camera.open() else Camera.open()
        } catch (e: Exception) {
            return null
        }
        return try {
            val latch = java.util.concurrent.CountDownLatch(1)
            var jpeg: ByteArray? = null
            camera.takePicture(
                { }, // shutter — visible to the user, no suppression knob exists
                null,
                { data, _ ->
                    jpeg = data
                    latch.countDown()
                },
            )
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            val bytes = jpeg ?: return null
            outputDir.mkdirs()
            val file = File(outputDir, "camera-${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            HardwareSeams.Capture(file.absolutePath, options.outWidth, options.outHeight, bytes.size.toLong())
        } catch (e: Exception) {
            null
        } finally {
            runCatching { camera.release() }
        }
    }

    private fun openFront(): Camera? {
        val info = android.hardware.Camera.CameraInfo()
        val count = Camera.getNumberOfCameras()
        for (i in 0 until count) {
            Camera.getCameraInfo(i, info)
            if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) return Camera.open(i)
        }
        return null
    }
}

class AndroidMic(private val context: Context, private val outputDir: File) : HardwareSeams.Mic {

    override fun record(durationMs: Int): HardwareSeams.Recording? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val bounded = DataPolicy.recordMs(durationMs)
        val file = File(outputDir, "mic-${System.currentTimeMillis()}.m4a")
        file.parentFile?.mkdirs()
        val recorder = MediaRecorder()
        return try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            Thread.sleep(bounded.toLong())
            recorder.stop()
            HardwareSeams.Recording(file.absolutePath, bounded, file.length())
        } catch (e: Exception) {
            file.delete()
            null
        } finally {
            runCatching { recorder.release() }
        }
    }
}

class AndroidSensors(private val context: Context) : HardwareSeams.Sensors {

    private val manager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override fun list(): List<HardwareSeams.SensorInfo> = manager.getSensorList(Sensor.TYPE_ALL).map { sensor ->
        HardwareSeams.SensorInfo(sensor.name, sensor.vendor, sensor.stringType)
    }

    override fun read(names: List<String>?): Map<String, DoubleArray> {
        val wanted = manager.getSensorList(Sensor.TYPE_ALL)
            .filter { names == null || it.name in names }
            .take(12) // bounded one-shot sample set
        if (wanted.isEmpty()) return emptyMap()
        val latch = java.util.concurrent.CountDownLatch(1)
        val samples = java.util.concurrent.ConcurrentHashMap<String, DoubleArray>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                samples.putIfAbsent(event.sensor.name, event.values.map { it.toDouble() }.toDoubleArray())
                if (samples.size >= wanted.size) latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        wanted.forEach { sensor -> manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
        latch.await(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
        manager.unregisterListener(listener)
        return samples
    }
}
