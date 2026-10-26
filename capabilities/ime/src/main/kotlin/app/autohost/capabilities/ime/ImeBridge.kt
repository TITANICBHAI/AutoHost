package app.autohost.capabilities.ime

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Process-local link between [ImeProvider] (broker worker thread) and [AutoHostKeyboardService] (main
 * thread), same shape as `A11yBridge` in the a11y module. Requests queue while the service is busy; each
 * waits up to [REQUEST_TIMEOUT_MS] for the main thread to process it.
 */
object ImeBridge {

    private const val REQUEST_TIMEOUT_MS = 5_000L

    class Request(
        val text: String,
        val clearFirst: Boolean,
        val secret: Boolean,
        val onDone: (TextInputResult) -> Unit,
    )

    @Volatile
    var service: AutoHostKeyboardService? = null
        private set

    private val queue = LinkedBlockingQueue<Request>()
    private val handler = Handler(Looper.getMainLooper())

    val connected: Boolean get() = service != null

    internal fun connect(service: AutoHostKeyboardService) {
        ImeBridge.service = service
        drain()
    }

    internal fun disconnect(service: AutoHostKeyboardService) {
        if (ImeBridge.service === service) ImeBridge.service = null
    }

    /** Blocking commit from the provider thread. Honest timeout → COMMIT_OUTCOME_UNKNOWN. */
    fun commitText(text: String, clearFirst: Boolean, secret: Boolean): TextInputResult {
        val done = java.util.concurrent.CountDownLatch(1)
        var result = TextInputResult(TextInputResult.COMMIT_OUTCOME_UNKNOWN, detail = "IME service not connected")
        val accepted = queue.offer(Request(text, clearFirst, secret) { r ->
            result = r
            done.countDown()
        })
        if (!accepted) {
            return TextInputResult(TextInputResult.COMMIT_OUTCOME_UNKNOWN, detail = "commit queue is full")
        }
        handler.post { drain() }
        if (!done.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return TextInputResult(TextInputResult.COMMIT_OUTCOME_UNKNOWN, detail = "the IME never answered the commit")
        }
        return result
    }

    /** Main-thread pump: runs pending requests on the service. */
    fun drain() {
        while (true) {
            val request = queue.poll() ?: return
            val active = service
            val result = if (active == null) {
                TextInputResult(TextInputResult.COMMIT_OUTCOME_UNKNOWN, detail = "IME service not connected")
            } else {
                active.handleCommit(request.text, request.clearFirst, request.secret)
            }
            request.onDone(result)
        }
    }

    /** Clipboard read through the IME (allowed for the selected IME even on API 29+). */
    fun clipboardGet(): String? = service?.clipboardText()

    /** Clipboard write through the IME. */
    fun clipboardSet(text: String): Boolean = service?.clipboardSet(text) ?: false

    /** True when the AutoHost IME is the system's selected input method (read from secure settings). */
    fun isSelected(): Boolean = ImeSelector.isAutoHostSelected(service?.inputMethodId())
}

/** Pure mapping from a boolean "is selected" to the availability decision (unit-tested). */
object ImeSelector {
    const val SETUP_REASON = "IME_NOT_SELECTED"
    const val FIX_ACTION = "OPEN_IME_PICKER"

    fun isAutoHostSelected(ourId: String?): Boolean {
        val selected = ourId ?: return false
        return selected.endsWith("/app.autohost.capabilities.ime.AutoHostKeyboardService") || selected.endsWith(".AutoHostKeyboardService")
    }
}
