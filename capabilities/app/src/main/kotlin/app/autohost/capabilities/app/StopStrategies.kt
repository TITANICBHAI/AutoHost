package app.autohost.capabilities.app

/**
 * Pure policy for the app stop chain (plan Phase 3 "App stop chain"): `auto` = best-effort background kill
 * first, then the App-info UI path (open app details → Force stop → confirm). Shell arrives in Phase 9 and
 * is refused here honestly.
 *
 * Verification is honest and narrow: `VERIFIED` means the target actually left the foreground (tracked via
 * [app.autohost.core.common.ForegroundTracker]); `BEST_EFFORT` means the kill was applied but cannot be
 * confirmed; `FAILED` means every allowed strategy was refused or changed nothing.
 */
object StopStrategies {

    /** Result values from schemas/errors.json `stopResults`. */
    const val VERIFIED = "VERIFIED"
    const val BEST_EFFORT = "BEST_EFFORT"
    const val FAILED = "FAILED"

    enum class Method { KILL_BACKGROUND, UI_FORCE_STOP }

    data class Attempt(val method: Method, val applied: Boolean)

    sealed class Decision {
        /** Perform this strategy next. */
        data class Next(val method: Method) : Decision()

        data class Done(val result: String, val method: Method?) : Decision()
    }

    /**
     * @param attempts        what has been tried so far
     * @param requested       `auto` (default) | `kill_background` | `ui` | `shell` (unsupported here)
     * @param uiAvailable     whether the App-info UI path is wired (a11y connected)
     * @param foregroundLeft  the target package is no longer the tracked foreground app
     */
    fun next(
        attempts: List<Attempt>,
        requested: String,
        uiAvailable: Boolean,
        foregroundLeft: Boolean,
    ): Decision {
        if (foregroundLeft) return Decision.Done(VERIFIED, attempts.lastOrNull()?.method)
        val tried = attempts.map { it.method }.toSet()
        return when (requested) {
            "shell" -> Decision.Done(BEST_EFFORT, null) // the shell tier owns this in Phase 9; caller refuses earlier
            "kill_background" -> when {
                attempts.any { it.method == Method.KILL_BACKGROUND } -> Decision.Done(BEST_EFFORT, Method.KILL_BACKGROUND)
                else -> Decision.Next(Method.KILL_BACKGROUND)
            }
            "ui" -> when {
                !uiAvailable -> Decision.Done(FAILED, null)
                attempts.any { it.method == Method.UI_FORCE_STOP } -> Decision.Done(FAILED, Method.UI_FORCE_STOP)
                else -> Decision.Next(Method.UI_FORCE_STOP)
            }
            else -> when { // "auto": kill → ui → done
                Method.KILL_BACKGROUND !in tried -> Decision.Next(Method.KILL_BACKGROUND)
                foregroundLeft -> Decision.Done(VERIFIED, Method.KILL_BACKGROUND)
                uiAvailable && Method.UI_FORCE_STOP !in tried -> Decision.Next(Method.UI_FORCE_STOP)
                else -> Decision.Done(BEST_EFFORT, attempts.lastOrNull()?.method)
            }
        }
    }
}
