package app.autohost.ui2

/** How the optional advanced (shell-level) access can be obtained. Order of [AccessPlan.routes] = least effort first. */
enum class AccessRoute(val title: String) {
    ROOT("Root"),
    SHIZUKU("Shizuku"),
    WIRELESS_DEBUGGING("Wireless debugging (inside AutoHost)"),
    USB_ADB("ADB from a computer"),
}

/**
 * One way to get advanced access, as the person is told about it.
 * [needsComputer]: a computer must be involved (even if only once per boot).
 * [implemented]: the route exists and works in this build. A route that is not implemented is shown as "not available yet",
 * never offered as a step to follow.
 */
data class RouteInfo(
    val route: AccessRoute,
    val needsComputer: Boolean,
    val implemented: Boolean,
    val note: String,
)

/**
 * The optional-ADB rule (owner, 2026-11-12): nothing in the normal flow may require a computer. Advanced access only
 * unlocks the [Need.ADVANCED] features and is offered with the on-phone routes first.
 *
 * Facts this encodes (code, verified by reading it): root and Shizuku are wired transports; the in-app Wireless-debugging
 * pairing is a DRAFT whose `exec` throws UNSUPPORTED; ADB-over-TCP on 127.0.0.1:5555 needs `adb tcpip 5555` from a computer.
 * Assumption (not verified in this build): Shizuku itself can be started from the phone on Android 11+ through Wireless
 * debugging, but on Android 10 and lower it needs a computer after every reboot.
 */
object AccessPlan {

    /** Working computer-free routes first, then working routes that need a computer, then routes that do not work yet. */
    fun routes(sdkInt: Int): List<RouteInfo> = all(sdkInt).sortedBy { r ->
        when {
            !r.implemented -> 2
            r.needsComputer -> 1
            else -> 0
        }
    }

    private fun all(sdkInt: Int): List<RouteInfo> = listOf(
        RouteInfo(AccessRoute.ROOT, needsComputer = false, implemented = true, note = "Only if the phone is rooted."),
        RouteInfo(
            AccessRoute.SHIZUKU,
            needsComputer = sdkInt < 30,
            implemented = true,
            note = if (sdkInt >= 30) "Install Shizuku, start it from its own screen with Wireless debugging. No computer."
            else "On Android 10 and lower Shizuku needs a computer to start it after every reboot.",
        ),
        RouteInfo(
            AccessRoute.WIRELESS_DEBUGGING,
            needsComputer = false,
            implemented = false,
            note = if (sdkInt >= 30) "Planned: pair from inside AutoHost, no computer. Not working yet."
            else "Needs Android 11 or newer.",
        ),
        RouteInfo(AccessRoute.USB_ADB, needsComputer = true, implemented = true, note = "A computer runs one command; repeat after each reboot on Android 10."),
    )

    /** The route to show first: implemented and no computer. Null when only a computer route exists (then say so plainly). */
    fun bestWithoutComputer(sdkInt: Int): RouteInfo? =
        routes(sdkInt).firstOrNull { it.implemented && !it.needsComputer }

    /** What to offer when nothing computer-free works: the first implemented route, flagged as needing a computer. */
    fun fallback(sdkInt: Int): RouteInfo? = routes(sdkInt).firstOrNull { it.implemented }
}

/** What the phone has right now. Filled from the live app state; all plain booleans so the rules stay testable. */
data class LiveAccess(
    val accessibilityOn: Boolean = false,
    val notificationAccessOn: Boolean = false,
    val keyboardSelected: Boolean = false,
    val advancedLive: Boolean = false,
    val secureSettingsGranted: Boolean = false,
    val unsafeSwitchesOn: Set<String> = emptySet(),
    val batteryExempt: Boolean = false,
)

/** The answer shown next to a feature: ready, will ask, needs one setup step, or needs the optional advanced access. */
enum class Verdict { READY, ASKS_ON_USE, NEEDS_SETUP, NEEDS_SWITCH, NEEDS_ADVANCED }

object Readiness {

    /** Which setup step a [Need.TOGGLE] capability waits for. */
    enum class Toggle { ACCESSIBILITY, NOTIFICATION_ACCESS, KEYBOARD }

    fun toggleFor(capabilityId: String): Toggle = when (capabilityId) {
        "device.notifications" -> Toggle.NOTIFICATION_ACCESS
        "device.ime" -> Toggle.KEYBOARD
        else -> Toggle.ACCESSIBILITY // device.ui, device.screenshot, ui.overlay, browser.a11y, vision (screenshot chain)
    }

    fun of(cap: CapabilityNeed, live: LiveAccess): Verdict = when (cap.need) {
        Need.READY, Need.PICK -> Verdict.READY
        Need.ASK_ON_USE -> Verdict.ASKS_ON_USE
        Need.TOGGLE -> {
            val on = when (toggleFor(cap.capabilityId)) {
                Toggle.ACCESSIBILITY -> live.accessibilityOn
                Toggle.NOTIFICATION_ACCESS -> live.notificationAccessOn
                Toggle.KEYBOARD -> live.keyboardSelected
            }
            if (on) Verdict.READY else Verdict.NEEDS_SETUP
        }
        Need.SWITCH -> if (cap.capabilityId in live.unsafeSwitchesOn) Verdict.READY else Verdict.NEEDS_SWITCH
        // device.settings can also be satisfied by the one-time secure-settings grant, which needs no shell afterwards.
        Need.ADVANCED -> when {
            live.advancedLive -> Verdict.READY
            cap.capabilityId == "device.settings" && live.secureSettingsGranted -> Verdict.READY
            else -> Verdict.NEEDS_ADVANCED
        }
    }

    /** Counts per verdict for the Home line "N things work now, M need one step". */
    fun summary(live: LiveAccess): Map<Verdict, Int> =
        FeatureCatalog.all.groupingBy { of(it, live) }.eachCount()
}
