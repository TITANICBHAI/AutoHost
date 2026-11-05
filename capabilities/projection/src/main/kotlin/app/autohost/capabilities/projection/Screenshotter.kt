package app.autohost.capabilities.projection

import android.media.Image
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Captures a frame from the projection session into a PNG file. Region cropping and scaling are pure
 * bitmap math; the acquisition stays in [ProjectionHolder] so lifecycle and capture never tangle.
 */
object Screenshotter {

    data class Captured(val path: String, val width: Int, val height: Int, val provider: String = "projection")

    sealed class Failure(val code: String, val message: String) {
        object NotGranted : Failure("PROJECTION_NOT_GRANTED", "screen capture was never consented to")
        class Unknown(code: String, message: String) : Failure(code, message)
    }

    fun capture(target: File, region: List<Int>? = null, scale: Double = 1.0): Captured {
        val session = ProjectionHolder.session ?: throw Failed(Failure.NotGranted)
        val image = try {
            session.reader.acquireLatestImage()
        } catch (e: RuntimeException) {
            throw Failed(Failure.Unknown("SCREENSHOT_BLOCKED", "the projection surface is gone: ${e.message}"))
        } ?: throw Failed(Failure.Unknown("SCREENSHOT_BLOCKED", "no frame is available yet; retry shortly"))
        try {
            val bitmap = image.toBitmap() ?: throw Failed(Failure.Unknown("SCREENSHOT_BLOCKED", "frame could not be decoded"))
            val cropped = crop(bitmap, region)
            val scaled = scale(cropped, scale)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
            return Captured(target.absolutePath, scaled.width, scaled.height)
        } finally {
            image.close()
        }
    }

    class Failed(val failure: Failure) : Exception(failure.message)

    /**
     * P15/X-16-era test fix: the crop GEOMETRY is pure and unit-testable — the Bitmap call is the
     * only Android touch. Returns the clipped rect (l, t, w, h) or null for identity (no crop).
     */
    internal fun cropRegion(imageW: Int, imageH: Int, region: List<Int>?): CropRect? {
        val l = (region?.getOrNull(0) ?: 0).coerceIn(0, imageW)
        val t = (region?.getOrNull(1) ?: 0).coerceIn(0, imageH)
        val r = (region?.getOrNull(2) ?: imageW).coerceIn(l, imageW)
        val b = (region?.getOrNull(3) ?: imageH).coerceIn(t, imageH)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null
        if (w == imageW && h == imageH) return null
        return CropRect(l, t, w, h)
    }

    data class CropRect(val l: Int, val t: Int, val w: Int, val h: Int)

    /** The scaled dimensions: at least one pixel; null for identity (~1.0) and non-positive factors. */
    internal fun scaledSize(imageW: Int, imageH: Int, factor: Double): Pair<Int, Int>? {
        if (factor >= 0.999 && factor <= 1.001) return null
        if (factor <= 0.0) return null
        val w = (imageW * factor).toInt().coerceAtLeast(1)
        val h = (imageH * factor).toInt().coerceAtLeast(1)
        return Pair(w, h)
    }

    internal fun crop(bitmap: Bitmap, region: List<Int>?): Bitmap {
        val rect = cropRegion(bitmap.width, bitmap.height, region)
            ?: return bitmap // identity: no crop, or degenerate region (fail closed to the full image)
        return Bitmap.createBitmap(bitmap, rect.l, rect.t, rect.w, rect.h)
    }

    internal fun scale(bitmap: Bitmap, factor: Double): Bitmap {
        val (w, h) = scaledSize(bitmap.width, bitmap.height, factor)
            ?: return bitmap // identity: ~1.0 or non-positive factor
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** RGBA_8888 → Bitmap. Kept internal so tests can exercise the conversion shape. */
    internal fun Image.toBitmap(): Bitmap? = try {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
    } catch (e: RuntimeException) {
        null
    }

    /** PNG bytes of a bitmap (used by evidence capture paths that embed dumps). */
    fun pngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** Decoding helper for tests that verify written files round-trip. */
    fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(file.absolutePath)
}
