package app.autohost.capabilities.vision

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
import kotlinx.serialization.json.putJsonArray

/**
 * P10-05: the `vision` capability (plan §6.1 — T4, ADR-072): `ocr`, `template_match`, `qr_decode`
 * over a screenshot region or a run-scoped image file. Every result carries `hint: true` — the
 * tier contract ("hints, not proof") is on the wire, not just in the docs.
 *
 * Image access is behind [Images] (app-side): the screenshot chain (a11y → projection → shell)
 * for `source:"screen"`, or a path that must resolve inside the run's directories or the task
 * bundle (the host validates containment — arbitrary device paths are refused).
 *
 * Engines: OCR/QR sit behind [OcrEngine]/[QrDecoder] (ML Kit / ZXing on device, fakes in tests);
 * template match is the dependency-free [TemplateMatcher] (pure NCC, JVM-tested with golden
 * vectors). OCR text may contain secrets — results are never auto-evidenced.
 */
class VisionProvider(
    private val ocr: OcrEngine?,
    private val qr: QrDecoder?,
    private val images: Images,
    private val clockNowMs: () -> Long,
) : CapabilityProvider {

    /** App-side image source. Returns null when the path is outside the run's allowed dirs. */
    interface Images {
        /** The current screen as ARGB pixels + dimensions (region applied later). */
        fun screenshotArgb(): Screenshot?

        /** Decodes a PNG/JPEG file to ARGB pixels; null when missing/outside allowed dirs. */
        fun loadArgb(path: String): Pixels?

        /** Loads the needle image for template_match (same containment rules). */
        fun loadNeedleArgb(path: String): Pixels?

        data class Pixels(val argb: IntArray, val width: Int, val height: Int)

        data class Screenshot(val pixels: Pixels, val provider: String)
    }

    override val capabilityId: String = "vision"
    override val providerId: String = "vision.engines"
    override val tier: String = "T4"

    override fun availability(env: DeviceEnv): Availability = when {
        ocr != null && qr != null -> Availability.Available
        ocr == null && qr == null -> Availability.Unsupported(reason = "VISION_ENGINES_ABSENT")
        ocr == null -> Availability.NeedsSetup(reason = "OCR_ENGINE_ABSENT", fixAction = "OPEN_SETUP")
        else -> Availability.NeedsSetup(reason = "QR_ENGINE_ABSENT", fixAction = "OPEN_SETUP")
    }

    override fun permissionFor(method: String, args: JsonObject): String? = "vision.read"

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "ocr" -> ocr(ctx, args)
        "template_match" -> templateMatch(ctx, args)
        "qr_decode" -> qrDecode(ctx, args)
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown vision method '$method'")
    }

    // ---- helpers -------------------------------------------------------------

    private fun regionOf(args: JsonObject): VisionPolicy.Region? {
        val obj = args["region"] as? JsonObject ?: return null
        fun int(name: String): Int? = (obj[name] as? JsonPrimitive)?.content?.toIntOrNull()
        val left = int("left") ?: return null
        val top = int("top") ?: return null
        val width = int("width") ?: return null
        val height = int("height") ?: return null
        return VisionPolicy.Region(left, top, width, height)
    }

    private fun sourcePixels(ctx: CallContext, args: JsonObject): Pair<Images.Pixels, String> {
        val source = (args["source"] as? JsonPrimitive)?.content ?: "screen"
        return when (source) {
            "screen" -> {
                val shot = images.screenshotArgb()
                    ?: throw HostError(ErrorCode.SCREENSHOT_BLOCKED, "no screenshot provider could capture the screen")
                shot.pixels to "screen:${shot.provider}"
            }
            "file" -> {
                val path = (args["path"] as? JsonPrimitive)?.content
                    ?: throw HostError(ErrorCode.INVALID_ARGS, "\"path\" is required when source is \"file\"")
                val pixels = images.loadArgb(path)
                    ?: throw HostError(ErrorCode.SCOPE_VIOLATION, "image path is outside this run's allowed directories")
                pixels to "file:$path"
            }
            else -> throw HostError(ErrorCode.INVALID_ARGS, "source must be \"screen\" or \"file\"")
        }
    }

    private fun crop(pixels: Images.Pixels, region: VisionPolicy.Region?): Images.Pixels {
        val verdict = VisionPolicy.regionVerdict(region, pixels.width, pixels.height)
        val r = (verdict as? VisionPolicy.RegionVerdict.Ok)?.region
            ?: throw HostError(ErrorCode.INVALID_ARGS, (verdict as VisionPolicy.RegionVerdict.Bad).detail)
        if (r.left == 0 && r.top == 0 && r.width == pixels.width && r.height == pixels.height) return pixels
        val out = IntArray(r.width * r.height)
        for (dy in 0 until r.height) {
            System.arraycopy(pixels.argb, (r.top + dy) * pixels.width + r.left, out, dy * r.width, r.width)
        }
        return Images.Pixels(out, r.width, r.height)
    }

    private fun bitmapOf(pixels: Images.Pixels): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(pixels.width, pixels.height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels.argb, 0, pixels.width, 0, 0, pixels.width, pixels.height)
        return bitmap
    }

    // ---- methods -------------------------------------------------------------

    private suspend fun ocr(ctx: CallContext, args: JsonObject): Envelope {
        val engine = ocr ?: return Envelope.err(ErrorCode.NEEDS_SETUP, "no OCR engine is bundled in this build", reason = "OCR_ENGINE_ABSENT", fixAction = "OPEN_SETUP")
        val (pixels, source) = try {
            sourcePixels(ctx, args)
        } catch (e: HostError) {
            return e.envelope()
        }
        val cropped = try {
            crop(pixels, regionOf(args))
        } catch (e: HostError) {
            return e.envelope()
        }
        val started = clockNowMs()
        return try {
            val lines = engine.recognize(bitmapOf(cropped))
            Envelope.ok(
                buildJsonObject {
                    put("hint", true)
                    put("engine", engine.engineId)
                    put("source", source)
                    put("width", cropped.width)
                    put("height", cropped.height)
                    put("duration_ms", clockNowMs() - started)
                    putJsonArray("lines") {
                        lines.forEach { line ->
                            add(
                                buildJsonObject {
                                    put("text", line.text)
                                    if (line.confidence != null) put("confidence", line.confidence)
                                    if (line.left != null) {
                                        put("box", buildJsonObject {
                                            put("left", line.left); put("top", line.top)
                                            put("width", line.width); put("height", line.height)
                                        })
                                    }
                                },
                            )
                        }
                    }
                },
            )
        } catch (e: Exception) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "OCR failed: ${e.message?.take(200)}", reason = "OCR_ENGINE_FAILED")
        }
    }

    private fun templateMatch(ctx: CallContext, args: JsonObject): Envelope {
        val threshold = VisionPolicy.thresholdVerdict((args["threshold"] as? JsonPrimitive)?.content?.toDoubleOrNull())
        if (threshold < 0) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "threshold must be within ${VisionPolicy.MIN_THRESHOLD}..1.0")
        }
        val stride = VisionPolicy.strideVerdict((args["stride"] as? JsonPrimitive)?.content?.toIntOrNull())
        if (stride < 0) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "stride must be within 1..32")
        }
        val needlePath = (args["needle_path"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"needle_path\" is required (run-scoped or task-bundle path)")
        val (pixels, source) = try {
            sourcePixels(ctx, args)
        } catch (e: HostError) {
            return e.envelope()
        }
        val needle = images.loadNeedleArgb(needlePath)
            ?: return Envelope.err(ErrorCode.SCOPE_VIOLATION, "needle path is outside this run's allowed directories")
        val cropped = try {
            crop(pixels, regionOf(args))
        } catch (e: HostError) {
            return e.envelope()
        }
        if (needle.argb.size > VisionPolicy.MAX_NEEDLE_BYTES) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "needle image exceeds ${VisionPolicy.MAX_NEEDLE_BYTES} bytes")
        }
        val started = clockNowMs()
        val match = TemplateMatcher.match(
            haystack = cropped.argb,
            haystackWidth = cropped.width,
            haystackHeight = cropped.height,
            needle = needle.argb,
            needleWidth = needle.width,
            needleHeight = needle.height,
            stride = stride,
            threshold = threshold,
        )
        return Envelope.ok(
            buildJsonObject {
                put("hint", true)
                put("found", match.found)
                put("score", match.score)
                put("box", buildJsonObject {
                    put("left", match.left); put("top", match.top)
                    put("width", match.width); put("height", match.height)
                })
                put("threshold", threshold)
                put("stride", stride)
                put("source", source)
                put("duration_ms", clockNowMs() - started)
            },
        )
    }

    private suspend fun qrDecode(ctx: CallContext, args: JsonObject): Envelope {
        val decoder = qr ?: return Envelope.err(ErrorCode.NEEDS_SETUP, "no QR engine is bundled in this build", reason = "QR_ENGINE_ABSENT", fixAction = "OPEN_SETUP")
        val (pixels, source) = try {
            sourcePixels(ctx, args)
        } catch (e: HostError) {
            return e.envelope()
        }
        val cropped = try {
            crop(pixels, regionOf(args))
        } catch (e: HostError) {
            return e.envelope()
        }
        val started = clockNowMs()
        val value = runCatching { decoder.decode(bitmapOf(cropped)) }.getOrNull()
        return Envelope.ok(
            buildJsonObject {
                put("hint", true)
                put("engine", decoder.engineId)
                put("source", source)
                put("found", value != null)
                if (value != null) put("value", value)
                put("duration_ms", clockNowMs() - started)
            },
        )
    }

    /** Internal host-failure carrier with honest envelope mapping. */
    class HostError(val code: ErrorCode, message: String) : Exception(message) {
        fun envelope(): Envelope = Envelope.err(code, message ?: code.name)
    }
}
