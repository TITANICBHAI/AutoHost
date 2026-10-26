package app.autohost.capabilities.shell

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
import java.io.File

/**
 * P9-06: the `screencap` link of the `device.screenshot` chain (plan §3.5 — MediaProjection → T3
 * `screencap` → global action). Registered AFTER the projection provider so the broker only lands
 * here when projection is unavailable or failed. Output is written into the run's evidence dir
 * exactly like the other links (`{path, width, height, provider}`); width/height come from the
 * PNG IHDR header so no bitmap decode is needed on the happy path.
 *
 * `region`/`scale` need a bitmap decode (android.graphics) which the JVM tests cannot drive — on
 * device the decode path runs; when decoding is unavailable it fails honestly with
 * `SCREENSHOT_BLOCKED` rather than returning a wrong crop.
 */
class ShellScreenshotProvider(
    private val transports: ShellTransports,
    private val evidenceDir: File,
) : CapabilityProvider {

    override val capabilityId: String = "device.screenshot"
    override val providerId: String = "shell.screencap"
    override val tier: String = "T3"

    override fun availability(env: DeviceEnv): Availability = when (transports.firstLive()) {
        null -> Availability.NeedsSetup("SHELL_TIER_NOT_PAIRED", "OPEN_SHELL_SETUP")
        else -> Availability.Available
    }

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method == "capture") "device.screenshot" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (method != "capture") {
            return Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.screenshot method '$method'")
        }
        val region = (args["region"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.content?.toIntOrNull()
        }
        val scale = (args["scale"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 1.0
        val transport = transports.firstLive()
            ?: return Envelope.err(
                ErrorCode.NEEDS_SETUP,
                "no shell transport is connected",
                reason = "SHELL_TIER_NOT_PAIRED",
                fixAction = "OPEN_SHELL_SETUP",
            )
        val png = try {
            val result = transport.exec(ShellCommands.screencap(), CAPTURE_TIMEOUT_MS)
            result.stdoutBytes ?: result.stdout.encodeToByteArray()
        } catch (e: ShellTransportException) {
            return when (e.reason) {
                ShellTransportException.Reason.TIMEOUT -> Envelope.err(ErrorCode.TIMEOUT, "screencap timed out")
                ShellTransportException.Reason.NOT_CONNECTED -> Envelope.err(
                    ErrorCode.NEEDS_SETUP,
                    e.message ?: "no shell transport is connected",
                    reason = "SHELL_TIER_NOT_PAIRED",
                    fixAction = "OPEN_SHELL_SETUP",
                )
                else -> Envelope.err(ErrorCode.SCREENSHOT_BLOCKED, e.message ?: "screencap failed")
            }
        }
        if (png.size < PNG_IHDR_END || !png.startsWith(PNG_SIGNATURE)) {
            // screencap prints errors to stdout on some devices; a non-PNG answer is a refusal.
            val detail = png.decodeToString().take(120)
            return Envelope.err(
                ErrorCode.SCREENSHOT_BLOCKED,
                if (detail.isNotBlank()) "screencap did not return a PNG: $detail" else "screencap returned no data",
                reason = "SCREENSHOT_BLOCKED",
            )
        }
        val width = png.intAt(PNG_WIDTH_OFFSET)
        val height = png.intAt(PNG_HEIGHT_OFFSET)
        val target = File(evidenceDir, "shot-${System.currentTimeMillis()}-shell.png")
        return try {
            target.parentFile?.mkdirs()
            val finalBytes = if (region != null || scale != 1.0) {
                transform(png, region, scale) ?: return Envelope.err(
                    ErrorCode.SCREENSHOT_BLOCKED,
                    "region/scale transforms are unavailable in this provider on this device",
                    reason = "SCREENSHOT_TRANSFORM_UNAVAILABLE",
                )
            } else {
                png
            }
            java.io.FileOutputStream(target).use { it.write(finalBytes) }
            Envelope.ok(
                buildJsonObject {
                    put("path", target.absolutePath)
                    put("width", if (region == null && scale == 1.0) width else widthOf(finalBytes) ?: width)
                    put("height", if (region == null && scale == 1.0) height else heightOf(finalBytes) ?: height)
                    put("provider", "shell.screencap")
                },
            )
        } catch (e: Exception) {
            Envelope.err(ErrorCode.SCREENSHOT_BLOCKED, "screenshot could not be written: ${e.message}")
        }
    }

    /**
     * Region crop / scale through android.graphics when the class is available (device path).
     * Returns null when the transform cannot be performed — the caller reports honestly.
     */
    private fun transform(png: ByteArray, region: List<Int>?, scale: Double): ByteArray? = runCatching {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: return null
        val cropped = if (region != null && region.size == 4) {
            val (l, t, r, b) = region.map { it }
            android.graphics.Bitmap.createBitmap(
                bitmap,
                l.coerceIn(0, bitmap.width),
                t.coerceIn(0, bitmap.height),
                (r - l).coerceIn(1, bitmap.width - l.coerceIn(0, bitmap.width)),
                (b - t).coerceIn(1, bitmap.height - t.coerceIn(0, bitmap.height)),
            )
        } else {
            bitmap
        }
        val scaled = if (scale != 1.0) {
            android.graphics.Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).toInt().coerceAtLeast(1),
                (cropped.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            cropped
        }
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }.getOrNull()

    private fun widthOf(png: ByteArray): Int? = if (png.size >= PNG_IHDR_END && png.startsWith(PNG_SIGNATURE)) png.intAt(PNG_WIDTH_OFFSET) else null
    private fun heightOf(png: ByteArray): Int? = if (png.size >= PNG_IHDR_END && png.startsWith(PNG_SIGNATURE)) png.intAt(PNG_HEIGHT_OFFSET) else null

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.intAt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)

    companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        const val PNG_WIDTH_OFFSET = 16
        const val PNG_HEIGHT_OFFSET = 20
        const val PNG_IHDR_END = 24
        const val CAPTURE_TIMEOUT_MS = 20_000L
    }
}
