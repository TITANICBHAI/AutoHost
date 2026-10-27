package app.autohost.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.autohost.capabilities.browser.CdpClient
import app.autohost.capabilities.net.JvmWsSocketFactory
import app.autohost.capabilities.vision.VisionProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * P10-04 (ADR-071): the CDP socket layer — target discovery over plain HTTP on the forwarded
 * port, the session as a WebSocket built on the P8-08 [JvmWsSocketFactory] (socket + handshake)
 * plus this file's minimal RFC 6455 client frame codec (the net module keeps its framing
 * private to `WsSession`; CDP needs text frames only, so ~60 lines here, not a new module).
 * Lives in `:app` so `:capabilities:browser` keeps no net dependency (the AppGraph
 * lambda-injection pattern, ADR-035).
 */
class CdpTransportFactory : CdpClient.Transport {

    override fun listTargets(port: Int, timeoutMs: Long): List<CdpClient.Transport.Target> {
        val conn = (URI("http://127.0.0.1:$port/json/list").toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs.toInt().coerceAtMost(30_000)
            readTimeout = timeoutMs.toInt().coerceAtMost(30_000)
            requestMethod = "GET"
        }
        val body = try {
            conn.inputStream.use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            throw CdpClient.CdpException("devtools /json/list failed: ${e.message?.take(200)}")
        } finally {
            conn.disconnect()
        }
        val array = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonArray
        }.getOrElse { throw CdpClient.CdpException("devtools /json/list returned non-JSON") }
        return array.mapNotNull { element ->
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(name: String): String? = (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val ws = str("webSocketDebuggerUrl") ?: return@mapNotNull null
            CdpClient.Transport.Target(
                id = str("id") ?: return@mapNotNull null,
                url = str("url") ?: "",
                title = str("title"),
                type = str("type") ?: "page",
                wsPath = ws.substringAfter(":$port", ws),
            )
        }
    }

    override fun connect(port: Int, wsPath: String, timeoutMs: Long): CdpClient.Connection {
        val path = if (wsPath.startsWith("/")) wsPath else "/$wsPath"
        val connection = JvmWsSocketFactory().connect("ws://127.0.0.1:$port$path", emptyList(), timeoutMs)
        connection.setTimeoutMs(timeoutMs)
        return CdpConnectionAdapter(connection.input, connection.output)
    }

    /** RFC 6455 text frames over the raw socket streams (client → masked, server → unmasked). */
    class CdpConnectionAdapter(private val input: InputStream, private val output: OutputStream) : CdpClient.Connection {

        override fun send(text: String) {
            val payload = text.encodeToByteArray()
            output.write(0x81) // FIN + text
            when {
                payload.size < 126 -> output.write(0x80 or payload.size)
                payload.size < 65_536 -> {
                    output.write(0x80 or 126)
                    output.write((payload.size shr 8) and 0xFF)
                    output.write(payload.size and 0xFF)
                }
                else -> {
                    output.write(0x80 or 127)
                    for (shift in 56 downTo 0 step 8) output.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
            val mask = ByteArray(4) { (Math.random() * 256).toInt().toByte() } // client frames are masked (RFC 6455 §5.3)
            output.write(mask)
            for (i in payload.indices) output.write(payload[i].toInt() xor mask[i % 4].toInt())
            output.flush()
        }

        override fun receive(timeoutMs: Long): String? {
            val first = input.read()
            if (first < 0) return null
            val opcode = first and 0x0F
            val lengthFirst = input.read()
            if (lengthFirst < 0) return null
            var length = (lengthFirst and 0x7F).toLong()
            if (length == 126L) {
                length = ((input.read() and 0xFF) shl 8 or (input.read() and 0xFF)).toLong()
            } else if (length == 127L) {
                length = 0
                for (i in 0 until 8) length = (length shl 8) or (input.read() and 0xFF).toLong()
            }
            if (length < 0 || length > MAX_FRAME_BYTES) return null
            val payload = ByteArray(length.toInt())
            var read = 0
            while (read < payload.size) {
                val n = input.read(payload, read, payload.size - read)
                if (n < 0) return null
                read += n
            }
            // ping(9)/pong(10)/close(8) carry no interesting payload for CDP; text(1) is the session.
            if (opcode != 1) return receive(timeoutMs)
            return String(payload)
        }

        override fun close() {
            runCatching {
                output.write(byteArrayOf(0x88.toByte(), 0x80.toByte(), 0, 0, 0, 0))
                output.flush()
            } // masked empty close
            runCatching { input.close() }
            runCatching { output.close() }
        }

        companion object {
            const val MAX_FRAME_BYTES = 32 * 1024 * 1024
        }
    }
}

/**
 * P10-05 (ADR-072): the vision image source — the current screen through the existing
 * projection screenshotter, and file reads confined to the active run's directories plus the
 * cache dir (needles live in task bundles; the AppGraph adds the active task's bundle root).
 */
class VisionImages(
    private val captureScreen: (File) -> app.autohost.capabilities.projection.Screenshotter.Captured?,
    private val cacheDir: File,
    private val runDirs: (runId: String) -> List<File>,
) : VisionProvider.Images {

    /** The AppGraph collector keeps this on the newest active run; empty between runs. */
    @Volatile
    var activeRunId: String = ""

    private val seq = AtomicInteger(0)

    override fun screenshotArgb(): VisionProvider.Images.Screenshot? {
        val target = File(cacheDir, "vision-screen-${seq.incrementAndGet()}.png")
        val captured = captureScreen(target) ?: return null
        val pixels = decode(File(captured.path)) ?: return null
        target.delete()
        return VisionProvider.Images.Screenshot(pixels, captured.provider)
    }

    override fun loadArgb(path: String): VisionProvider.Images.Pixels? {
        val file = contained(path) ?: return null
        return decode(file)
    }

    override fun loadNeedleArgb(path: String): VisionProvider.Images.Pixels? = loadArgb(path)

    private fun contained(path: String): File? {
        val file = File(path)
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        val roots = runDirs(activeRunId) + listOf(cacheDir)
        return roots
            .map { root -> runCatching { root.canonicalFile }.getOrDefault(root) }
            .firstOrNull { root -> canonical.path.startsWith(root.path) }
            ?.let { if (canonical.isFile) canonical else null }
    }

    private fun decode(file: File): VisionProvider.Images.Pixels? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val w = bitmap.width
        val h = bitmap.height
        bitmap.recycle()
        return VisionProvider.Images.Pixels(argb, w, h)
    }
}
