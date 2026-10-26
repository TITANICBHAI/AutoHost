package app.autohost.ui.taskui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.autohost.core.model.taskui.UiBindings
import app.autohost.taskui.TaskUiController
import app.autohost.taskui.UiHandlerController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File

/**
 * P7-02 (plan §10.1 Tier C, UI_SPEC S16): the sandboxed HTML task-UI host. The task ships
 * `ui/index.html` (+ assets); the host renders it in a WebView with a JS bridge
 * (`autohost.invoke(handler, args)` / `autohost.state()`), plus run-status pushes delivered to
 * `window.autohostEvent(topic, payload)`.
 *
 * Sandbox rules (§14, enforced here — never trusted to the page):
 *  - **No network:** every non-`file:` request is denied outright (403), and navigation off the
 *    bundle is refused. JavaScript on; content access, DOM storage and window opening off.
 *  - **No file escape:** only paths canonicalized under the task's own directory are served; any
 *    other `file://` read returns 404. The page cannot reach other tasks or app storage.
 *  - **Handler scoping:** the bridge is constructed for this task's id — `invoke` only ever reaches
 *    this task's own `@ui.handler`s through the `:ui` worker (30 s cap). There is no way to name
 *    another task or to mint a token.
 *  - The strict CSP `<meta>` is the author's defense-in-depth; the boundary above is the hard
 *    guarantee (ADR-055).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlUiHost(
    taskId: String,
    taskPath: String,
    file: String?,
    handlers: UiHandlerController,
    taskUi: TaskUiController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scopeState by taskUi.bindingScope(taskId).collectAsState()
    var view by remember { mutableStateOf<WebView?>(null) }
    Column(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                buildBridge(ctx, taskId, taskPath, file ?: "ui/index.html", handlers, taskUi)
                    .also { view = it }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Text(
            "This screen was provided by the task. No network, only this task's handlers.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
    }
    // Run-status pushes: whenever the binding scope changes (run/state updates), tell the page.
    LaunchedEffect(scopeState, view) {
        val status = scopeState.run["status"] ?: ""
        view?.evaluateJavascript(
            "window.autohostEvent && window.autohostEvent('run', {\"status\": ${jsString(status)}});",
            null,
        )
    }
}

private fun jsString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

/** Builds the configured WebView. Called once per composition (AndroidView factory). */
private fun buildBridge(
    context: android.content.Context,
    taskId: String,
    taskPath: String,
    file: String,
    handlers: UiHandlerController,
    taskUi: TaskUiController,
): WebView {
    val root = File(taskPath).canonicalFile
    val index = File(root, file)
    val webView = WebView(context)
    webView.layoutParams = android.view.ViewGroup.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
    )
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = false
    webView.settings.allowContentAccess = false
    webView.settings.allowFileAccess = true // file:// within the task dir only, policed below
    webView.settings.javaScriptCanOpenWindowsAutomatically = false
    webView.addJavascriptInterface(Bridge(taskId, handlers, taskUi), "autohost")
    webView.webViewClient = object : WebViewClient() {

        private fun underRoot(target: File): Boolean =
            target.path.startsWith(root.path + File.separator) || target == root

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme == "file") {
                val target = File(url.path ?: "").canonicalFile
                return !underRoot(target) // refusing returns true (we handle it: by not navigating)
            }
            return true // http/intent/content navigation is always refused
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.scheme == "file") {
                val target = File(url.path ?: "").canonicalFile
                if (!underRoot(target) || !target.isFile) return denied(404)
                return null // serve from the bundle
            }
            return denied(403) // no network, no content URIs — the hard sandbox boundary
        }
    }
    if (index.isFile) {
        webView.loadUrl("file://" + index.canonicalPath)
    } else {
        webView.loadDataWithBaseURL(
            null,
            "<html><body><p style=\"font-family:sans-serif\">Declared UI file is missing: " +
                file.replace("<", "&lt;") + "</p></body></html>",
            "text/html",
            "utf-8",
            null,
        )
    }
    return webView
}

private fun denied(status: Int): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    status,
    if (status == 403) "Forbidden" else "Not Found",
    mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0)),
)

/** The JS bridge object. Runs on a WebView JS thread — the AIDL call blocks it, by design (sync return). */
private class Bridge(
    private val taskId: String,
    private val handlers: UiHandlerController,
    private val taskUi: TaskUiController,
) {
    @android.webkit.JavascriptInterface
    fun invoke(handler: String, argsJson: String?): String {
        val args = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(argsJson ?: "{}") as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
        // runBlocking on the JS bridge thread: the interface is synchronous by contract (plan §10.1);
        // the 30 s cap bounds the block. Handler failures arrive as FAILED outcomes, never exceptions.
        return kotlinx.coroutines.runBlocking {
            val outcome = handlers.call(taskId, handler, args)
            buildJsonObject {
                put("status", outcome.status)
                put("reason", outcome.reason ?: "")
                put("message", outcome.message ?: "")
                outcome.data?.let { put("data", it) }
            }.toString()
        }
    }

    @android.webkit.JavascriptInterface
    fun state(): String = taskUi.state(taskId).value.toString()

    @android.webkit.JavascriptInterface
    fun binding(path: String): String {
        val resolved = UiBindings.bind("{{$path}}", taskUi.bindingScope(taskId).value).value
        return (resolved as? JsonPrimitive)?.content ?: resolved.toString()
    }
}
