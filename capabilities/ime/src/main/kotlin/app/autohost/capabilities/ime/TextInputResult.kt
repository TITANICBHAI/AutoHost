package app.autohost.capabilities.ime

import kotlinx.serialization.Serializable

/**
 * Typed result of one text-commit attempt (plan Phase 3 "AutoHost IME + TextInputResult"). Outcome values
 * come from schemas/errors.json `textInputOutcomes` — the wire never invents new ones:
 *
 * - `VERIFIED` — the commit was applied and the focused field now shows the text;
 * - `REJECTED` — the field actively refused (read-only, session gone before the commit);
 * - `ACCEPTED_UNVERIFIED` — the commit was applied but the field will not read its text back
 *   (password fields) — reported, never assumed;
 * - `COMMIT_OUTCOME_UNKNOWN` — there was no input connection (IME not selected / no focused editor);
 * - `INPUT_SESSION_CHANGED` — the focused editor changed between the commit and the verification read.
 */
@Serializable
data class TextInputResult(
    val outcome: String,
    /** The strategy that produced the result (set_text chain reporting; lowercase). */
    val strategy: String = "ime_commit",
    /** Length of text the verification read could actually see, when known. */
    val visibleChars: Int? = null,
    val detail: String? = null,
) {
    companion object {
        const val VERIFIED = "VERIFIED"
        const val REJECTED = "REJECTED"
        const val ACCEPTED_UNVERIFIED = "ACCEPTED_UNVERIFIED"
        const val COMMIT_OUTCOME_UNKNOWN = "COMMIT_OUTCOME_UNKNOWN"
        const val INPUT_SESSION_CHANGED = "INPUT_SESSION_CHANGED"
    }
}
