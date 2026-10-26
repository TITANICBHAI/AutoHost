package app.autohost.capabilities.shell

/**
 * P9-08: the mutual-exclusivity rule (plan §5.4 — "a provider built on the UiAutomation connection
 * suppresses ordinary accessibility services; the broker never activates both"). Pure decisions,
 * exhaustively testable:
 *
 *  - a session may only start while the accessibility service is disconnected AND the user enabled
 *    the experimental toggle;
 *  - while a session is live, an accessibility connection event forces the session closed
 *    (the service wins — it is the default provider; the session is the exception);
 *  - the provider is only available with a live session (so the registry's first-available rule
 *    naturally falls through to the a11y provider whenever the service is connected).
 */
object UiAutomationExclusivity {

    sealed class StartDecision {
        /** Start the instrumentation runner. */
        data object Ok : StartDecision()

        data class Refused(val reason: String) : StartDecision()
    }

    fun canStart(a11yConnected: Boolean, experimentalEnabled: Boolean): StartDecision = when {
        !experimentalEnabled -> StartDecision.Refused("UI_AUTOMATION_EXPERIMENTAL_OFF")
        a11yConnected -> StartDecision.Refused("A11Y_SERVICE_ACTIVE")
        else -> StartDecision.Ok
    }

    sealed class A11yEventDecision {
        /** Nothing pending — keep the session. */
        data object Keep : A11yEventDecision()

        /** Close the live session; the accessibility service took over. */
        data class CloseSession(val reason: String) : A11yEventDecision()
    }

    fun onA11yEvent(sessionLive: Boolean, a11yConnected: Boolean): A11yEventDecision =
        if (sessionLive && a11yConnected) {
            A11yEventDecision.CloseSession("A11Y_RECONNECTED")
        } else {
            A11yEventDecision.Keep
        }
}
