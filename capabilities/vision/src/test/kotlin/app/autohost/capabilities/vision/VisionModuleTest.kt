package app.autohost.capabilities.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * P10-05 JVM tests: VisionPolicy validation, the pure NCC TemplateMatcher against deterministic
 * golden vectors, and the VisionProvider over fake engines/images (hint flag, honest errors).
 */
class VisionPolicyTest {

    @Test fun `null region means the full image`() {
        val verdict = VisionPolicy.regionVerdict(null, 100, 80)
        assertEquals(VisionPolicy.Region(0, 0, 100, 80), (verdict as VisionPolicy.RegionVerdict.Ok).region)
    }

    @Test fun `regions inside bounds pass`() {
        val verdict = VisionPolicy.regionVerdict(VisionPolicy.Region(10, 5, 40, 30), 100, 80)
        assertTrue(verdict is VisionPolicy.RegionVerdict.Ok)
    }

    @Test fun `regions outside bounds and non-positive sizes are refused`() {
        assertTrue(VisionPolicy.regionVerdict(VisionPolicy.Region(90, 0, 20, 10), 100, 80) is VisionPolicy.RegionVerdict.Bad)
        assertTrue(VisionPolicy.regionVerdict(VisionPolicy.Region(-1, 0, 20, 10), 100, 80) is VisionPolicy.RegionVerdict.Bad)
        assertTrue(VisionPolicy.regionVerdict(VisionPolicy.Region(0, 0, 0, 10), 100, 80) is VisionPolicy.RegionVerdict.Bad)
    }

    @Test fun `threshold and stride windows are enforced`() {
        assertEquals(0.92, VisionPolicy.thresholdVerdict(null), 1e-9)
        assertEquals(-1.0, VisionPolicy.thresholdVerdict(0.2), 1e-9)
        assertEquals(0.75, VisionPolicy.thresholdVerdict(0.75), 1e-9)
        assertEquals(4, VisionPolicy.strideVerdict(null))
        assertEquals(-1, VisionPolicy.strideVerdict(0))
        assertEquals(-1, VisionPolicy.strideVerdict(33))
    }
}

class TemplateMatcherTest {

    /** Deterministic horizontal gradient haystack with a planted needle patch. */
    private fun haystackWithNeedle(width: Int = 200, height: Int = 120): Triple<IntArray, IntArray, Pair<Int, Int>> {
        val hay = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val v = (x * 255 / width)
            argb(v, v, v)
        }
        val needleWidth = 40
        val needleHeight = 20
        val needle = IntArray(needleWidth * needleHeight) { i ->
            val x = i % needleWidth
            val y = i / needleWidth
            argb(50 + x * 3, 50 + x * 3, 200 - y * 2)
        }
        val atX = 77
        val atY = 33
        for (dy in 0 until needleHeight) {
            for (dx in 0 until needleWidth) {
                hay[(atY + dy) * width + atX + dx] = needle[dy * needleWidth + dx]
            }
        }
        return Triple(hay, needle, atX to atY)
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun `exact needle is found at the planted position with score 1`() {
        val (hay, needle, at) = haystackWithNeedle()
        val match = TemplateMatcher.match(hay, 200, 120, needle, 40, 20, stride = 4, threshold = 0.92)
        assertTrue(match.found)
        assertEquals(1.0, match.score, 1e-6)
        assertEquals(at.first, match.left)
        assertEquals(at.second, match.top)
    }

    @Test fun `brightness-shifted needle still matches (NCC is brightness invariant)`() {
        val (hay, needle, at) = haystackWithNeedle()
        // Add +25 to every haystack pixel: NCC is affine-brightness invariant.
        val brightened = IntArray(hay.size) { i ->
            val r = ((hay[i] shr 16) and 0xFF) + 25
            val g = ((hay[i] shr 8) and 0xFF) + 25
            val b = (hay[i] and 0xFF) + 25
            argb(r.coerceAtMost(255), g.coerceAtMost(255), b.coerceAtMost(255))
        }
        val match = TemplateMatcher.match(brightened, 200, 120, needle, 40, 20, stride = 4, threshold = 0.92)
        assertTrue(match.found)
        assertEquals(at.first, match.left)
        assertEquals(at.second, match.top)
    }

    @Test fun `a plain gradient without the needle does not match`() {
        val width = 200
        val height = 120
        val hay = IntArray(width * height) { i ->
            val v = ((i % width) * 255 / width)
            argb(v, v, v)
        }
        val needle = IntArray(40 * 20) { i -> argb(50 + (i % 40) * 3, 50 + (i % 40) * 3, 200) }
        val match = TemplateMatcher.match(hay, width, height, needle, 40, 20, stride = 4, threshold = 0.92)
        assertFalse(match.found)
    }

    @Test fun `needle larger than haystack and tiny needles are refused`() {
        val (hay, needle, _) = haystackWithNeedle()
        val tooBig = TemplateMatcher.match(hay, 200, 120, needle, 300, 20, stride = 4, threshold = 0.9)
        assertFalse(tooBig.found)
        val tiny = IntArray(3) { argb(0, 0, 0) }
        val tinyMatch = TemplateMatcher.match(hay, 200, 120, tiny, 3, 1, stride = 4, threshold = 0.9)
        assertFalse(tinyMatch.found)
    }

    @Test fun `grayscale follows BT 601`() {
        assertEquals(0, TemplateMatcher.grayscale(argb(0, 0, 0)))
        assertEquals(255, TemplateMatcher.grayscale(argb(255, 255, 255)))
        val gray = TemplateMatcher.grayscale(argb(255, 0, 0)) // pure red → 0.299*255 ≈ 76
        assertTrue(abs(gray - 76) <= 1)
    }
}

/** Fake OCR engine returning fixed lines. */
private class FakeOcr : OcrEngine {
    override val engineId = "fake.ocr"
    override suspend fun recognize(bitmap: android.graphics.Bitmap) = listOf(
        OcrEngine.OcrLine("TOTAL 12.40", null, 10, 20, 100, 24),
    )
}

private class FakeQr(private val value: String?) : QrDecoder {
    override val engineId = "fake.qr"
    override fun decode(bitmap: android.graphics.Bitmap) = value
}

private class FakeImages(
    private val screenshot: VisionProvider.Images.Screenshot?,
    private val files: Map<String, VisionProvider.Images.Pixels> = emptyMap(),
) : VisionProvider.Images {
    override fun screenshotArgb() = screenshot
    override fun loadArgb(path: String) = files[path]
    override fun loadNeedleArgb(path: String) = files[path]
}

class VisionProviderTest {

    private val pixels = VisionProvider.Images.Pixels(IntArray(100 * 100) { 0xFF808080.toInt() }, 100, 100)

    private fun ctx(granted: Boolean = true) = app.autohost.core.broker.CallContext(
        "r1", "t1", "p1",
        if (granted) mapOf("vision.read" to kotlinx.serialization.json.JsonObject(emptyMap())) else emptyMap(),
        nowMs = 0,
    )

    @Test fun `ocr returns hinted lines`() = kotlinx.coroutines.runBlocking {
        val provider = VisionProvider(FakeOcr(), FakeQr(null), FakeImages(pixels.let {
            VisionProvider.Images.Screenshot(it, "projection")
        }), { 0 })
        val envelope = provider.invoke(ctx(), "ocr", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertTrue(envelope.ok)
        val result = envelope.result!!.jsonObject
        assertEquals(true, result["hint"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("fake.ocr", result["engine"]!!.jsonPrimitive.content)
        assertTrue(result.toString().contains("TOTAL 12.40"))
        assertTrue(result.toString().contains("\"box\""))
    }

    @Test fun `qr decode reports found and value`() = kotlinx.coroutines.runBlocking {
        val provider = VisionProvider(FakeOcr(), FakeQr("payload"), FakeImages(
            VisionProvider.Images.Screenshot(pixels, "projection"),
        ), { 0 })
        val envelope = provider.invoke(ctx(), "qr_decode", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertTrue(envelope.ok)
        val result = envelope.result!!.jsonObject
        assertTrue(result["found"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result.toString().contains("payload"))
        assertTrue(result["hint"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `missing engines surface as needs setup`() = kotlinx.coroutines.runBlocking {
        val provider = VisionProvider(null, null, FakeImages(null), { 0 })
        val availability = provider.availability(app.autohost.core.broker.DeviceEnv())
        assertEquals("VISION_ENGINES_ABSENT", (availability as app.autohost.core.model.Availability.Unsupported).reason)
        val envelope = provider.invoke(ctx(), "ocr", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(ErrorCode.NEEDS_SETUP, ErrorCode.valueOf(envelope.error!!.code))
    }

    @Test fun `no screenshot is screenshot blocked`() = kotlinx.coroutines.runBlocking {
        val provider = VisionProvider(FakeOcr(), FakeQr(null), FakeImages(null), { 0 })
        val envelope = provider.invoke(ctx(), "ocr", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(ErrorCode.SCREENSHOT_BLOCKED, ErrorCode.valueOf(envelope.error!!.code))
    }

    @Test fun `file source outside the run dirs is scope violation`() = kotlinx.coroutines.runBlocking {
        val provider = VisionProvider(FakeOcr(), FakeQr(null), FakeImages(pixels.let {
            VisionProvider.Images.Screenshot(it, "projection")
        }), { 0 })
        val envelope = provider.invoke(ctx(), "ocr", buildJsonObject {
            put("source", kotlinx.serialization.json.JsonPrimitive("file"))
            put("path", kotlinx.serialization.json.JsonPrimitive("/sdcard/secret.png"))
        })
        assertEquals(ErrorCode.SCOPE_VIOLATION, ErrorCode.valueOf(envelope.error!!.code))
    }

    @Test fun `template match validates threshold and requires a needle`() = kotlinx.coroutines.runBlocking {
        val provider = VisionProvider(FakeOcr(), FakeQr(null), FakeImages(
            VisionProvider.Images.Screenshot(pixels, "projection"),
            mapOf("needle.png" to VisionProvider.Images.Pixels(IntArray(40 * 20) { 0xFF202020.toInt() }, 40, 20)),
        ), { 0 })
        val badThreshold = provider.invoke(ctx(), "template_match", buildJsonObject {
            put("needle_path", kotlinx.serialization.json.JsonPrimitive("needle.png"))
            put("threshold", kotlinx.serialization.json.JsonPrimitive(0.1))
        })
        assertEquals(ErrorCode.INVALID_ARGS, ErrorCode.valueOf(badThreshold.error!!.code))
        val noNeedle = provider.invoke(ctx(), "template_match", buildJsonObject {
            put("needle_path", kotlinx.serialization.json.JsonPrimitive("missing.png"))
        })
        assertEquals(ErrorCode.SCOPE_VIOLATION, ErrorCode.valueOf(noNeedle.error!!.code))
        val ok = provider.invoke(ctx(), "template_match", buildJsonObject {
            put("needle_path", kotlinx.serialization.json.JsonPrimitive("needle.png"))
        })
        assertTrue(ok.ok)
        val result = ok.result!!.jsonObject
        assertTrue(result["hint"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(result["found"]!!.jsonPrimitive.content.toBoolean()) // flat gray vs flat dark: below 0.92
    }

    private fun buildJsonObject(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        kotlinx.serialization.json.buildJsonObject(block)
}
