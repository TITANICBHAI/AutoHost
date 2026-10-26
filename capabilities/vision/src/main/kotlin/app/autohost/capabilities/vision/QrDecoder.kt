package app.autohost.capabilities.vision

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * P10-05 (ADR-072): the QR seam plus the ZXing-core implementation (Apache-2.0). Same seam
 * discipline as [OcrEngine]: pure provider, swappable engine, JVM tests use a fake.
 */
interface QrDecoder {
    /** Returns the decoded payload text, or null when no code is present. */
    fun decode(bitmap: Bitmap): String?

    val engineId: String
}

class ZxingQrDecoder : QrDecoder {

    private val reader by lazy {
        MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE))) }
    }

    override val engineId: String = "zxing.qr"

    override fun decode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}
