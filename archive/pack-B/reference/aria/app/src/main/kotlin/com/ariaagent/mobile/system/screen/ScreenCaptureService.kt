package com.ariaagent.mobile.system.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream

/**
 * ScreenCaptureService — MediaProjection screen capture.
 *
 * Captures device display, downsamples to 512×512, saves to internal storage.
 * The screenshot is then read by OcrEngine (text) and fed to the LLM (context).
 *
 * Why 512×512?
 *   - Exynos 9611 memory bandwidth is limited (Mali-G72 MP3 shares memory with CPU)
 *   - Full 1080×2340 captures would exhaust bandwidth and crash
 *   - 512×512 is sufficient for OCR text recognition and UI element detection
 *   - ML Kit OCR works well at this resolution
 *
 * Capture rate: 1-2 FPS for navigation (not continuous — thermal risk)
 *
 * Phase: 2 (Perception)
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "aria_screen_capture"
        private const val NOTIF_ID = 1002
        private const val CAPTURE_WIDTH = 512
        private const val CAPTURE_HEIGHT = 512

        var isActive = false
            private set

        private var latestScreenshotPath: String? = null

        /**
         * Returns the latest captured Bitmap, or null if no capture yet.
         * The bitmap is 512×512 downsampled.
         */
        fun captureLatest(): Bitmap? {
            val path = latestScreenshotPath ?: return null
            return android.graphics.BitmapFactory.decodeFile(path)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Background thread for image I/O — keeps disk writes off the main thread (Bug #2 fix)
    private var captureHandlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // isActive is set true only after setupCapture() succeeds (Bug #10 fix)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        captureHandlerThread = HandlerThread("ARIACaptureIO").also {
            it.start()
            captureHandler = Handler(it.looper)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: return START_NOT_STICKY
        val projectionIntent = intent.getParcelableExtra<Intent>("projectionData") ?: return START_NOT_STICKY

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, projectionIntent)

        // Android 14+ (API 34) requires registering a MediaProjection.Callback before
        // calling createVirtualDisplay(). Without it the system silently stops projection.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    android.util.Log.i("ScreenCaptureService", "MediaProjection stopped by system")
                    isActive = false
                    virtualDisplay?.release()
                    virtualDisplay = null
                    imageReader?.close()
                    imageReader = null
                }
            }, null)
        }

        setupCapture()
        return START_STICKY
    }

    private fun setupCapture() {
        val mp = mediaProjection ?: return
        val handler = captureHandler ?: return
        val metrics = resources.displayMetrics

        val reader = ImageReader.newInstance(
            CAPTURE_WIDTH, CAPTURE_HEIGHT,
            PixelFormat.RGBA_8888, 2
        )
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "ARIACapture",
            CAPTURE_WIDTH, CAPTURE_HEIGHT, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        // Bug #2 fix: pass background handler so image callbacks run on captureHandlerThread,
        // not on the main thread — prevents ANR from disk I/O in saveImage().
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                saveImage(image)
            }
        }, handler)

        // Bug #10 fix: only mark active after the capture pipeline is fully wired
        isActive = true
    }

    private fun saveImage(image: android.media.Image) {
        // Bug #3 fix: wrap all disk I/O in try-catch so a storage failure can't crash the service
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val rowStride = planes[0].rowStride
            val pixelStride = planes[0].pixelStride
            val rowPadding = rowStride - pixelStride * CAPTURE_WIDTH

            val bitmap = Bitmap.createBitmap(
                CAPTURE_WIDTH + rowPadding / pixelStride,
                CAPTURE_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to exact size if padding was added
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, CAPTURE_WIDTH, CAPTURE_HEIGHT)

            val screenshotFile = File(filesDir, "screenshots/latest.jpg").also {
                it.parentFile?.mkdirs()
            }
            FileOutputStream(screenshotFile).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            latestScreenshotPath = screenshotFile.absolutePath
            // Recycle cropped first (may be the same object as bitmap if no padding),
            // then recycle bitmap only when it is a distinct allocation.
            cropped.recycle()
            if (cropped !== bitmap) bitmap.recycle()
        } catch (e: Exception) {
            android.util.Log.e("ScreenCaptureService", "saveImage failed: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ARIA Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ARIA Agent")
            .setContentText("Screen observation active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureHandlerThread?.quitSafely()
        captureHandlerThread = null
        captureHandler = null
    }
}
