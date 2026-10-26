package app.autohost.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * P10-02 (ADR-070): the *visible* browser surface. One WebView per page, created under the
 * page profile's data-directory suffix (S26 isolation), with a plain Android chrome strip
 * (page indicator + Close) so the automation is always visible and user-stoppable — plan §6.1:
 * "No stealth features".
 *
 * Input honesty (S25 measures both paths at the phone):
 *  - clicks resolve the selector's box via `getBoundingClientRect` and inject a **real**
 *    MotionEvent stream (DOWN → 2 MOVE frames → UP) through the view hierarchy;
 *  - typing first clicks the field (real), then injects **real** key events derived from
 *    [android.view.KeyCharacterMap] for ASCII; characters the key map cannot express (or any
 *    non-ASCII text) fall back to a JS value-set with `input_mode:"synthetic"` — never claimed
 *    as trusted input.
 *
 * The activity reports every settled load back to the controller (URL, title, visible-text
 * sample for the challenge scan) and finishes itself when the controller closes the page.
 */
class BrowserActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var pageId: String = ""
    private var statusLine: TextView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageId = intent.getStringExtra(EXTRA_PAGE_ID) ?: ""
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: "default"
        val controller = (application as app.autohost.AutoHostApp).graph.browserController
        if (pageId.isEmpty()) {
            finish() // stale restart: no page behind this activity
            return
        }
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chrome = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setGravity(Gravity.CENTER_VERTICAL); setBackgroundColor(Color.DKGRAY); setPadding(pad / 2, pad / 4, pad / 2, pad / 4) }
        statusLine = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; setPadding(pad / 2, 0, pad / 2, 0); weightSum = 1f
        }
        chrome.addView(statusLine, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        chrome.addView(Button(this).apply {
            text = "Close"
            setOnClickListener {
                controller.closeByPage(pageId)
                Toast.makeText(this@BrowserActivity, "Page closed", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(chrome, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Per-profile data directory: must be set BEFORE the WebView exists (S26).
        runCatching { WebView.setDataDirectorySuffix("autohost-" + profile.hashCode().toUInt().toString(16)) }
            .onFailure { runCatching { Toast.makeText(this, "profile isolation unavailable: ${it.message}", Toast.LENGTH_SHORT).show() } }

        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true // ONLY used for task:-resolved bundle files; no file: URLs accepted from tasks
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val u = url ?: return
                    val title = view?.title
                    controller.attachActivity(pageId, this@BrowserActivity)
                    main.post {
                        sampleBody { sample ->
                            controller.onLoadSettled(pageId, u, title, "loaded", sample, inputMode())
                            statusLine?.text = title ?: u
                        }
                    }
                }
            }
            setWebContentsDebuggingEnabledIfEnabled(controller)
        }
        webView = view
        root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        controller.attachActivity(pageId, this)

        val url = intent.getStringExtra(EXTRA_URL)
        val taskFile = intent.getStringExtra(EXTRA_TASK_FILE)
        view.loadUrl(url ?: (taskFile?.let { "file://" + it } ?: "about:blank"))
        statusLine?.text = "loading…"
    }

    @Volatile
    private var lastInputMode: String = "real"

    private fun inputMode(): String = lastInputMode

    private fun notOpen(): app.autohost.capabilities.browser.HostAct =
        app.autohost.capabilities.browser.HostAct(false, false, "synthetic")

    private fun setWebContentsDebuggingEnabledIfEnabled(controller: BrowserController) {
        // P10-04: the CDP route needs WebView debugging (S27). Kept off unless the user enabled it.
        runCatching { WebView.setWebContentsDebuggingEnabled(controller.webViewDebuggingEnabled) }
    }

    // ---- host operations (called from broker threads; marshalled to main) -----------------

    fun click(selector: String, timeoutMs: Long): app.autohost.capabilities.browser.HostAct {
        val view = webView ?: return notOpen()
        val result = AtomicReference<Pair<Boolean, Boolean>?>(null)
        val done = CountDownLatch(1)
        main.post {
            val js = """
                (function(){
                  var e=document.querySelector(${jsString(selector)});
                  if(!e) return null;
                  var r=e.getBoundingClientRect();
                  e.scrollIntoView({block:'center'});
                  var r2=e.getBoundingClientRect();
                  return JSON.stringify({x:(r2.left+r2.width/2),y:(r2.top+r2.height/2),w:window.innerWidth});
                })()
            """.trimIndent()
            view.evaluateJavascript(js) { ret ->
                val box = parseBox(ret)
                if (box == null) {
                    result.set(false to false)
                    done.countDown()
                } else {
                    val scale = if (box.w > 0) view.width.toDouble() / box.w else 1.0
                    val x = (box.x * scale).toFloat().coerceIn(0f, view.width.toFloat() - 1f)
                    val y = (box.y * scale).toFloat().coerceIn(0f, view.height.toFloat() - 1f)
                    val ok = injectTap(view, x, y)
                    lastInputMode = "real"
                    result.set(ok to true)
                    done.countDown()
                }
            }
        }
        if (!done.awaitMs(timeoutMs)) return app.autohost.capabilities.browser.HostAct(false, false, "synthetic")
        val pair = result.get() ?: return app.autohost.capabilities.browser.HostAct(false, false, "synthetic")
        return app.autohost.capabilities.browser.HostAct(pair.first, pair.second, "real")
    }

    fun type(selector: String, text: String, timeoutMs: Long): app.autohost.capabilities.browser.HostAct {
        val clicked = click(selector, timeoutMs)
        if (!clicked.found) return app.autohost.capabilities.browser.HostAct(false, false, clicked.inputMode)
        val view = webView ?: return app.autohost.capabilities.browser.HostAct(false, true, "synthetic")
        val realChars = runCatching { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) }.getOrNull()
        return if (realChars != null && text.all { it.code in 32..126 }) {
            val ok = AtomicReference(false)
            val done = CountDownLatch(1)
            main.post {
                view.requestFocus()
                var delivered = true
                for (event in realChars) {
                    delivered = delivered && view.dispatchKeyEvent(event)
                }
                lastInputMode = "real"
                ok.set(delivered)
                done.countDown()
            }
            if (!done.awaitMs(timeoutMs)) return app.autohost.capabilities.browser.HostAct(false, true, "synthetic")
            app.autohost.capabilities.browser.HostAct(ok.get(), true, "real")
        } else {
            // Non-ASCII (or key-map failure): JS value-set + input/change events — honest synthetic.
            val ok = AtomicReference(false)
            val done = CountDownLatch(1)
            main.post {
                val js = """
                    (function(){
                      var e=document.activeElement;
                      if(!e||e.value===undefined) return 'no-input';
                      e.value=${jsString(text)};
                      e.dispatchEvent(new Event('input',{bubbles:true}));
                      e.dispatchEvent(new Event('change',{bubbles:true}));
                      return 'ok';
                    })()
                """.trimIndent()
                view.evaluateJavascript(js) { ret -> ok.set(ret?.contains("ok") == true); done.countDown() }
            }
            if (!done.awaitMs(timeoutMs)) return app.autohost.capabilities.browser.HostAct(false, true, "synthetic")
            lastInputMode = "synthetic"
            app.autohost.capabilities.browser.HostAct(ok.get(), true, "synthetic")
        }
    }

    fun extract(selector: String, attribute: String): app.autohost.capabilities.browser.HostExtract {
        val view = webView ?: return app.autohost.capabilities.browser.HostExtract(emptyList(), false)
        val values = AtomicReference<List<String?>>(emptyList())
        val truncated = AtomicReference(false)
        val done = CountDownLatch(1)
        main.post {
            val getter = when (attribute) {
                "text" -> "e.textContent.trim()"
                "html" -> "e.innerHTML"
                else -> "e.getAttribute(${jsString(attribute)})"
            }
            val js = """
                (function(){
                  var list=Array.from(document.querySelectorAll(${jsString(selector)})).slice(0,${MAX_EXTRACT_ITEMS});
                  return JSON.stringify(list.map(e=>$getter));
                })()
            """.trimIndent()
            view.evaluateJavascript(js) { ret ->
                values.set(parseStringList(ret))
                truncated.set(false)
                done.countDown()
            }
        }
        if (!done.awaitMs(DEFAULT_ACT_TIMEOUT_MS)) return app.autohost.capabilities.browser.HostExtract(emptyList(), true)
        val cleaned = values.get().map { it?.take(MAX_VALUE_CHARS) }
        return app.autohost.capabilities.browser.HostExtract(cleaned, truncated.get())
    }

    fun cookies(): List<app.autohost.capabilities.browser.HostCookie> {
        val raw = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        main.post {
            val url = webView?.url ?: ""
            val cookie = CookieManager.getInstance().getCookie(url)
            raw.set(cookie)
            done.countDown()
        }
        if (!done.awaitMs(DEFAULT_ACT_TIMEOUT_MS)) return emptyList()
        return (raw.get() ?: return emptyList()).split("; ").filter { it.contains('=') }.map { pair ->
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
            app.autohost.capabilities.browser.HostCookie(name, value, null, null, null)
        }
    }

    fun clearCookies() {
        main.post { CookieManager.getInstance().removeAllCookies(null) }
    }

    fun finishFromHost() {
        main.post { finish() }
    }

    // ---- real input injection (S25) -------------------------------------------------------

    /** DOWN → two MOVE frames → UP through the real touch pipeline. */
    private fun injectTap(view: View, x: Float, y: Float): Boolean {
        val now = SystemClockUptime()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val move1 = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, x, y, 0)
        val move2 = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_MOVE, x + 0.5f, y + 0.5f, 0)
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x + 0.5f, y + 0.5f, 0)
        return try {
            view.dispatchTouchEvent(down) && view.dispatchTouchEvent(move1) &&
                view.dispatchTouchEvent(move2) && view.dispatchTouchEvent(up)
        } finally {
            down.recycle(); move1.recycle(); move2.recycle(); up.recycle()
        }
    }

    private fun sampleBody(callback: (String?) -> Unit) {
        val view = webView ?: run { callback(null); return }
        val js = "(document.body?document.body.innerText:'').slice(0,${BODY_SAMPLE_CHARS})"
        view.evaluateJavascript(js) { ret -> callback(ret?.removeSurrounding("\"")?.take(BODY_SAMPLE_CHARS)) }
    }

    private fun CountDownLatch.awaitMs(ms: Long): Boolean = try { await(ms, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { false }

    private fun parseBox(ret: String?): Box? {
        val json = ret?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\\"", "\"") ?: return null
        if (json == "null" || json.isEmpty()) return null
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
            Box(
                obj["x"]!!.jsonPrimitive.content.toDouble(),
                obj["y"]!!.jsonPrimitive.content.toDouble(),
                (obj["w"]?.jsonPrimitive?.content?.toDouble()) ?: 0.0,
            )
        }.getOrNull()
    }

    private fun parseStringList(ret: String?): List<String?> {
        if (ret == null || ret == "null") return emptyList()
        val json = ret.trim().removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"").replace("\\\\", "\\")
        val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        return (element as? kotlinx.serialization.json.JsonArray)?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: emptyList()
    }

    private fun jsString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

    private data class Box(val x: Double, val y: Double, val w: Double)

    override fun onDestroy() {
        (application as app.autohost.AutoHostApp).graph.browserController.detachActivity(pageId)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Back is the user's Stop affordance: it closes the page through the controller, it
        // never silently navigates away from an automated run's surface.
        (application as app.autohost.AutoHostApp).graph.browserController.closeByPage(pageId)
        finish()
    }

    object SystemClockUptime {
        fun now(): Long = android.os.SystemClock.uptimeMillis()
    }

    companion object {
        const val EXTRA_PAGE_ID = "autohost.browser.PAGE_ID"
        const val EXTRA_PROFILE = "autohost.browser.PROFILE"
        const val EXTRA_URL = "autohost.browser.URL"
        const val EXTRA_TASK_FILE = "autohost.browser.TASK_FILE"
        const val MAX_EXTRACT_ITEMS = 200
        const val MAX_VALUE_CHARS = 4096
        const val BODY_SAMPLE_CHARS = 4096
        const val DEFAULT_ACT_TIMEOUT_MS = 20_000L
    }
}
