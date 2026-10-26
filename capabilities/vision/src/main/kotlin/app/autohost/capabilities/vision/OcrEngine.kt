package app.autohost.capabilities.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * P10-05 (ADR-072): the OCR engine seam plus the bundled ML Kit implementation. The seam keeps
 * the provider pure (fake engines in JVM tests, real engine on device) and the S28 spike can
 * swap implementations without touching the provider.
 */
interface OcrEngine {
    /** Recognizes text in [bitmap]; returns lines top-to-bottom with optional confidence. */
    suspend fun recognize(bitmap: Bitmap): List<OcrLine>

    val engineId: String

    data class OcrLine(val text: String, val confidence: Double?, val left: Int?, val top: Int?, val width: Int?, val height: Int?)
}

/** The only ML Kit touchpoint in the codebase (Apache-2.0, bundled — no Play Services needed). */
class MlKitOcrEngine : OcrEngine {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override val engineId: String = "mlkit.latin"

    override suspend fun recognize(bitmap: Bitmap): List<OcrEngine.OcrLine> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val box = line.boundingBox
                OcrEngine.OcrLine(
                    text = line.text,
                    confidence = null, // ML Kit Latin does not expose per-line confidence
                    left = box?.left,
                    top = box?.top,
                    width = box?.width(),
                    height = box?.height(),
                )
            }
        }
    }
}
