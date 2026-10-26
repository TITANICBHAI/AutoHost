package app.autohost.capabilities.a11y

/**
 * Pure decision for the `set_text` strategy chain (plan §7.6):
 *
 *   ACTION_SET_TEXT → AutoHost IME commit → clipboard paste → (T3 `input text` arrives in Phase 9).
 *
 * The first strategy whose outcome is `VERIFIED` wins; unverifiable outcomes are reported, never assumed.
 * Secrets never travel through the clipboard (canary rule, AGENTS.md §3.6) — the chain skips paste for
 * secret text and the caller redacts any trace.
 */
object SetTextChain {

    /** Values from schemas/errors.json `textInputOutcomes`. */
    const val VERIFIED = "VERIFIED"
    const val REJECTED = "REJECTED"
    const val ACCEPTED_UNVERIFIED = "ACCEPTED_UNVERIFIED"
    const val COMMIT_OUTCOME_UNKNOWN = "COMMIT_OUTCOME_UNKNOWN"
    const val INPUT_SESSION_CHANGED = "INPUT_SESSION_CHANGED"

    enum class Strategy { SET_TEXT, IME_COMMIT, CLIPBOARD_PASTE }

    data class Attempt(val strategy: Strategy, val outcome: String)

    sealed class Decision {
        /** Run this strategy next. */
        data class Next(val strategy: Strategy) : Decision()

        /** A strategy verified the text: stop, report it. */
        data class Done(val attempt: Attempt) : Decision()

        /** No strategy verified the text: report the best (last) outcome honestly. */
        data class Unverified(val attempts: List<Attempt>) : Decision()

        /** The field refuses everything we are allowed to try (e.g. secret + no IME). */
        data class Rejected(val attempts: List<Attempt>) : Decision()
    }

    /**
     * @param secret when true the clipboard strategy is forbidden (canary rule).
     * @param imeAvailable whether the AutoHost IME is the selected input method right now.
     * @param remaining strategies still allowed, in order.
     */
    fun next(
        attempts: List<Attempt>,
        secret: Boolean,
        imeAvailable: Boolean,
        remaining: List<Strategy> = Strategy.entries.toList(),
    ): Decision {
        if (attempts.any { it.outcome == VERIFIED }) {
            return Decision.Done(attempts.last { it.outcome == VERIFIED })
        }
        val tried = attempts.map { it.strategy }.toSet()
        val nextStrategy = remaining
            .filter { it !in tried }
            .firstOrNull { s ->
                when (s) {
                    Strategy.SET_TEXT -> true
                    Strategy.IME_COMMIT -> imeAvailable
                    Strategy.CLIPBOARD_PASTE -> !secret // canary rule: secrets never touch the clipboard
                }
            }
        return when (nextStrategy) {
            null -> {
                val rejected = attempts.any { it.outcome == REJECTED }
                if (rejected) Decision.Rejected(attempts) else Decision.Unverified(attempts)
            }
            else -> Decision.Next(nextStrategy)
        }
    }
}
