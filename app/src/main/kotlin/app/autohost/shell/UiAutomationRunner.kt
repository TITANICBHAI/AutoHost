package app.autohost.shell

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.Base64
import javax.net.ServerSocketFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P9-08: the UiAutomation runner — an [Instrumentation] started through the shell tier with
 * `am instrument -e port <p> app.autohost/app.autohost.shell.UiAutomationRunner` (no `-w`: am
 * returns immediately, the runner serves until it receives `{"op":"quit"}` or the process dies).
 *
 * The runner opens a loopback [ServerSocket] and serves the line-JSON protocol documented in
 * `UiAutomationSessionClient` (dump / click / text / gesture / key / screenshot / ping / quit).
 * The accessibility tree is serialized in the uiautomator XML shape, in document order, so the
 * shell module's `UiautomatorDump` parser and the flat `index` addressing apply unchanged.
 *
 * EXCLUSIVITY (plan §5.4, ADR-068): this connection suppresses ordinary accessibility services,
 * so the controller refuses to start it while the a11y service is connected and closes it the
 * moment the service reconnects. The controller also enforces the experimental toggle; the runner
 * itself stays dumb (it serves whatever connection arrives on its port — loopback only).
 *
 * UNVERIFIED-on-hardware by design (plan S4): Gate-9 records the real behaviour (whether the a11y
 * service is indeed suppressed on the phone, dialog timing, screenshot dimensions).
 */
class UiAutomationRunner : Instrumentation() {

    override fun onStart() {
        val port = getArguments().getInt("port", DEFAULT_PORT)
        val thread = Thread { serve(port) }
        thread.name = "ui-automation-runner"
        thread.start()
        // The instrumentation thread may end; the server thread keeps the session alive.
    }

    private fun serve(port: Int) {
        val server = try {
            ServerSocketFactory.getDefault().createServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())
        } catch (_: Exception) {
            return // port taken or blocked: the controller's ping will report the session as down
        }
        try {
            server.soTimeout = 0
            val socket = server.accept() // exactly one client: the provider
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val uiAutomation = getUiAutomation()
            while (true) {
                val line = reader.readLine() ?: break
                val reply = handle(line, uiAutomation)
                writer.write(reply)
                writer.write("\n")
                writer.flush()
                if (reply.contains("\"quit\":true")) break
            }
            socket.close()
        } catch (_: Exception) {
            // any I/O failure ends the session honestly; the controller's probe notices
        } finally {
            runCatching { server.close() }
        }
    }

    private fun handle(line: String, uiAutomation: android.app.UiAutomation): String {
        val req = try {
            Json.decodeFromString(JsonObject.serializer(), line)
        } catch (_: Exception) {
            return "{\"ok\":false,\"error\":\"BAD_REQUEST\"}"
        }
        val op = (req["op"] as? JsonPrimitive)?.content ?: return err("BAD_REQUEST", "op missing")
        return try {
            when (op) {
                "ping" -> ok { put("pong", true) }
                "dump" -> ok { put("xml", serialize(uiAutomation.rootInActiveWindow)) }
                "click" -> opBool(ok = performAt(uiAutomation, req.index()) { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) })
                "text" -> opBool(ok = performAt(uiAutomation, req.index()) {
                    val arguments = Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, req.str("text"))
                    it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                })
                "gesture" -> {
                    val duration = req.int("ms").coerceIn(50, 60_000).toLong()
                    opBool(ok = injectSwipe(uiAutomation, req.int("x1"), req.int("y1"), req.int("x2"), req.int("y2"), duration))
                }
                "key" -> {
                    val now = SystemClock.uptimeMillis()
                    val down = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, req.int("code"), 0)
                    val up = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, req.int("code"), 0)
                    val sent = uiAutomation.injectInputEvent(down, true) && uiAutomation.injectInputEvent(up, true)
                    opBool(ok = sent)
                }
                "screenshot" -> {
                    val bitmap = uiAutomation.takeScreenshot()
                    if (bitmap == null) {
                        err("SCREENSHOT_BLOCKED", "takeScreenshot returned null")
                    } else {
                        val png = java.io.ByteArrayOutputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            out.toByteArray()
                        }
                        ok {
                            put("png", Base64.getEncoder().encodeToString(png))
                            put("width", bitmap.width)
                            put("height", bitmap.height)
                        }
                    }
                }
                "quit" -> ok { put("quit", true) }
                else -> err("BAD_REQUEST", "unknown op '$op'")
            }
        } catch (e: Exception) {
            err("RUNNER_ERROR", e.message ?: "runner op failed")
        }
    }

    /** Walks the document-order flat [index] and runs the action on that node. */
    private fun performAt(uiAutomation: android.app.UiAutomation, index: Int, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = uiAutomation.rootInActiveWindow ?: return false
        val node = flatten(root).getOrNull(index) ?: return false
        return action(node)
    }

    private fun injectSwipe(
        uiAutomation: android.app.UiAutomation,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float, at: Long) =
            MotionEvent.obtain(downTime, at, action, x, y, 0)

        val steps = 10
        if (!uiAutomation.injectInputEvent(event(MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), downTime), true)) {
            return false
        }
        for (step in 1..steps) {
            val fraction = step.toFloat() / steps
            val x = x1 + (x2 - x1) * fraction
            val y = y1 + (y2 - y1) * fraction
            val at = downTime + durationMs * step / steps
            val move = event(MotionEvent.ACTION_MOVE, x, y, at)
            val accepted = uiAutomation.injectInputEvent(move, true)
            move.recycle()
            if (!accepted) return false
            if (step < steps) Thread.sleep((durationMs / steps).coerceAtLeast(1))
        }
        val upAt = downTime + durationMs
        return uiAutomation.injectInputEvent(event(MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), upAt), true)
    }

    /** Depth-first, document order — the exact order UiautomatorDump assigns indexes. */
    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(256)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            out.add(node)
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
    }

    /** uiautomator-shaped XML; bounds "[l,t][r,b]"; document order so indexes match [flatten]. */
    private fun serialize(root: AccessibilityNodeInfo?): String {
        if (root == null) return "<?xml version='1.0' encoding='UTF-8'?><hierarchy rotation=\"0\"/>"
        val sb = StringBuilder(1 shl 16)
        sb.append("<?xml version='1.0' encoding='UTF-8'?><hierarchy rotation=\"0\"><node")
        appendNode(sb, root, 0)
        sb.append("</node></hierarchy>")
        return sb.toString()
    }

    private fun appendNode(sb: StringBuilder, node: AccessibilityNodeInfo, depth: Int) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val l = bounds.left
        val t = bounds.top
        val r = bounds.right
        val b = bounds.bottom
        sb.append(" index=\"${depth}\" text=\"${esc(node.text?.toString())}\"")
        sb.append(" resource-id=\"${esc(node.viewIdResourceName)}\" class=\"${esc(node.className?.toString())}\"")
        sb.append(" package=\"${esc(node.packageName?.toString())}\" content-desc=\"${esc(node.contentDescription?.toString())}\"")
        sb.append(" checkable=\"${node.isCheckable}\" checked=\"${node.isChecked}\" clickable=\"${node.isClickable}\"")
        sb.append(" enabled=\"${node.isEnabled}\" focusable=\"${node.isFocusable}\" focused=\"${node.isFocused}\"")
        sb.append(" scrollable=\"${node.isScrollable}\" password=\"${node.isPassword}\" editable=\"${node.isEditable}\"")
        sb.append(" bounds=\"[$l,$t][$r,$b]\"")
        if (node.childCount == 0) {
            sb.append(" />")
        } else {
            sb.append(">")
            for (i in 0 until node.childCount) {
                sb.append("<node")
                node.getChild(i)?.let { appendNode(sb, it, i) } ?: sb.append(" />")
            }
            sb.append("</node>")
        }
    }

    private fun esc(value: String?): String =
        value?.replace("&", "&amp;")?.replace("<", "&lt;")?.replace(">", "&gt;")?.replace("\"", "&quot;") ?: ""

    private fun ok(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("ok", true); block() },
        )

    private fun opBool(ok: Boolean): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("ok", ok); put("performed", ok); if (!ok) put("error", "NODE_NOT_FOUND") },
        )

    private fun err(code: String, detail: String): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject { put("ok", false); put("error", code); put("detail", detail) },
    )

    private fun JsonObject.index(): Int = (this["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1

    private fun JsonObject.int(name: String): Int = (this[name] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private fun JsonObject.str(name: String): String = (this[name] as? JsonPrimitive)?.content ?: ""

    companion object {
        const val DEFAULT_PORT = 43191

        /** The manifest <instrumentation> declaration (docs/ANDROID_MANIFEST.md §1). */
        const val ANDROID_NAME = "app.autohost.shell.UiAutomationRunner"

        /** The `am instrument` command (no -w: am returns, the session lives on). */
        fun startCommand(port: Int): String =
            "am instrument -e port $port $ANDROID_NAME"
    }
}
