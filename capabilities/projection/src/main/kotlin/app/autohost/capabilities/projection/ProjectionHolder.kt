package app.autohost.capabilities.projection

import android.content.Intent
import android.media.projection.MediaProjectionManager

/**
 * Process-local holder for the MediaProjection (plan Phase 3): the consent activity stores the result,
 * [ProjectionService] turns it into a VirtualDisplay + ImageReader, [Screenshotter] captures from it.
 * The projection lives exactly as long as the foreground service; killing the service releases everything.
 */
object ProjectionHolder {

    data class Session(
        val projection: android.media.projection.MediaProjection,
        val display: android.hardware.display.VirtualDisplay,
        val reader: android.media.ImageReader,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val grantedAtMs: Long,
    )

    @Volatile
    var session: Session? = null
        private set

    /** Callbacks interested in the session ending (the app wires a log/notification update). */
    @Volatile
    var onSessionEnded: (() -> Unit)? = null

    @Volatile
    var pendingResultCode: Int? = null

    @Volatile
    var pendingData: Intent? = null

    fun storePending(resultCode: Int, data: Intent) {
        pendingResultCode = resultCode
        pendingData = data
    }

    fun consumePending(): Pair<Int, Intent>? {
        val code = pendingResultCode ?: return null
        val data = pendingData ?: return null
        pendingResultCode = null
        pendingData = null
        return code to data
    }

    fun start(
        projection: android.media.projection.MediaProjection,
        width: Int,
        height: Int,
        dpi: Int,
    ): Session {
        stop()
        val reader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        val display = projection.createVirtualDisplay(
            "autohost-shot",
            width, height, dpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null,
        )
        val session = Session(projection, display, reader, width, height, dpi, System.currentTimeMillis())
        projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                stop()
                onSessionEnded?.invoke()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        this.session = session
        return session
    }

    /** Releases display, reader and projection. Safe to call repeatedly. */
    @Synchronized
    fun stop() {
        val current = session ?: return
        session = null
        try {
            current.display.release()
        } catch (e: RuntimeException) {
            // already released
        }
        try {
            current.reader.close()
        } catch (e: RuntimeException) {
            // already closed
        }
        try {
            current.projection.stop()
        } catch (e: RuntimeException) {
            // already stopped
        }
    }

    fun active(): Boolean = session != null

    fun consentIntent(context: android.content.Context): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        return manager.createScreenCaptureIntent()
    }
}
