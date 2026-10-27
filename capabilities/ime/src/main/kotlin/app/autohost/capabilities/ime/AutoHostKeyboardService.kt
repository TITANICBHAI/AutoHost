package app.autohost.capabilities.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The AutoHost keyboard: the reliable text-entry surface for the `set_text` chain (plan §7.6). It draws
 * nothing (a 1 px view keeps the session alive); its job is to be the *selected* IME so
 * `InputConnection.commitText` works from the background, and to own clipboard reads on API 29+ where
 * only the focused app and the default IME may read the clipboard.
 */
class AutoHostKeyboardService : InputMethodService() {

    private var sessionToken: String? = null
    private var editorInfo: EditorInfo? = null

    override fun onCreateInputView(): View =
        android.view.View(this).apply { minimumWidth = 1; minimumHeight = 1 }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editorInfo = attribute
        sessionToken = attribute?.let { "${it.fieldId}:${it.inputType}:${System.identityHashCode(it)}" }
        ImeBridge.connect(this)
    }

    override fun onFinishInput() {
        sessionToken = null
        super.onFinishInput()
    }

    override fun onDestroy() {
        ImeBridge.disconnect(this)
        super.onDestroy()
    }

    /** Called by [ImeBridge] on the main thread. Never throws across the bridge. */
    fun handleCommit(text: String, clearFirst: Boolean, secret: Boolean): TextInputResult {
        val connection: InputConnection = currentInputConnection
            ?: return TextInputResult(TextInputResult.COMMIT_OUTCOME_UNKNOWN, detail = "no input connection")
        val session = sessionToken
            ?: return TextInputResult(TextInputResult.INPUT_SESSION_CHANGED, detail = "no focused editor")
        return try {
            InputConnectionTextEditor.commit(connection, text, clearFirst, session)
        } catch (e: RuntimeException) {
            TextInputResult(TextInputResult.COMMIT_OUTCOME_UNKNOWN, detail = "commit threw: ${e.message}")
        }
    }

    /** Clipboard read — allowed for the selected IME on API 29+; null when we are not focused. */
    fun clipboardText(): String? = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (e: RuntimeException) {
        null
    }

    fun clipboardSet(text: String): Boolean = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("autohost", text))
        true
    } catch (e: RuntimeException) {
        false
    }

    /** Our input-method id as the system records it (`<pkg>/<cls>`), for ImeSelector. */
    fun inputMethodId(): String? = try {
        val info = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        // The canonical id is "<package>/<class>"; build it directly — the service knows its own identity.
        "${packageName}/${javaClass.name}".takeIf { info != null }
    } catch (e: RuntimeException) {
        null
    }

    override fun onEvaluateFullscreenMode(): Boolean = false // never steal the screen; typing is host-driven

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = super.onKeyDown(keyCode, event)
}
