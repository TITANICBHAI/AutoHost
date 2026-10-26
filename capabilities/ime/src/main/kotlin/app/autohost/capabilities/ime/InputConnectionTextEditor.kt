package app.autohost.capabilities.ime

import android.view.inputmethod.InputConnection

/**
 * The commit + verification logic over an [InputConnection], kept separate from the service so every
 * outcome branch is testable (the service supplies the live connection; tests supply a fake).
 *
 * Verification rule (plan §7.6): a commit is `VERIFIED` only when the text around the cursor actually
 * contains what was committed. Password fields never read their text back → `ACCEPTED_UNVERIFIED`.
 */
object InputConnectionTextEditor {

    /** How much surrounding text the verification read may pull (bounded — huge fields must not stall us). */
    const val VERIFY_WINDOW = 4_096

    /**
     * Commits [text] and verifies. [clearFirst] deletes the existing content around the cursor first.
     * [sessionToken] identifies the focused editor; when the caller's notion of the focused editor changed
     * between calls, the outcome is `INPUT_SESSION_CHANGED` (decided by the service, which sees focus moves).
     */
    fun commit(connection: InputConnection?, text: String, clearFirst: Boolean, sessionToken: String?): TextInputResult {
        if (connection == null) {
            return TextInputResult(TextInputResult.COMMIT_OUTCOME_UNKNOWN, detail = "no input connection (IME not focused)")
        }
        if (sessionToken == null) {
            return TextInputResult(TextInputResult.INPUT_SESSION_CHANGED, detail = "no focused editor when the commit arrived")
        }
        if (clearFirst && text.isNotEmpty()) {
            val before = readBefore(connection)
            val after = readAfter(connection)
            connection.deleteSurroundingText(before.length, after.length)
        }
        val applied = connection.commitText(text, 1)
        if (!applied) {
            return TextInputResult(TextInputResult.REJECTED, detail = "the editor refused commitText")
        }
        return verify(connection, text, sessionToken)
    }

    private fun verify(connection: InputConnection, text: String, sessionToken: String): TextInputResult {
        val before = readBefore(connection)
        val selected = try {
            connection.getSelectedText(0)?.toString() ?: ""
        } catch (e: RuntimeException) {
            ""
        }
        val visible = before + selected
        return when {
            visible.contains(text) -> TextInputResult(TextInputResult.VERIFIED, visibleChars = visible.length)
            // Password fields (and some canvases) never read back: the commit was applied, say so honestly.
            visible.isEmpty() -> TextInputResult(TextInputResult.ACCEPTED_UNVERIFIED, visibleChars = 0)
            else -> TextInputResult(
                TextInputResult.REJECTED,
                visibleChars = visible.length,
                detail = "field shows ${visible.length} chars but not the committed text",
            )
        }
    }

    private fun readBefore(connection: InputConnection): String = try {
        connection.getTextBeforeCursor(VERIFY_WINDOW, 0)?.toString() ?: ""
    } catch (e: RuntimeException) {
        ""
    }

    private fun readAfter(connection: InputConnection): String = try {
        connection.getTextAfterCursor(VERIFY_WINDOW, 0)?.toString() ?: ""
    } catch (e: RuntimeException) {
        ""
    }
}
