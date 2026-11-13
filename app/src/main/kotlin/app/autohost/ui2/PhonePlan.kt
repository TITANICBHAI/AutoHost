package app.autohost.ui2

/** One thing the person can do on the Phone tab to unlock more. [fixAction] is a FixHub action name, or null when the app handles it. */
data class SetupStep(
    val toggle: Readiness.Toggle,
    val title: String,
    val why: String,
    val fixAction: String,
    val unlocks: Int,
)

/** One feature line on the Phone tab. */
data class FeatureRow(val capability: CapabilityNeed, val verdict: Verdict, val fixAction: String?)

/** One group card: counts for the collapsed header, rows for the expanded body. */
data class GroupCard(val group: FeatureGroup, val rows: List<FeatureRow>) {
    val ready: Int get() = rows.count { it.verdict == Verdict.READY || it.verdict == Verdict.ASKS_ON_USE }
    val total: Int get() = rows.size
    val allGood: Boolean get() = ready == total
}

/** Everything the Phone tab renders, computed from the live state. Pure, so the rules are unit-tested. */
data class PhonePlan(
    val steps: List<SetupStep>,
    val groups: List<GroupCard>,
    val routes: List<RouteInfo>,
    val advancedNeeded: Boolean,
) {
    val readyCount: Int get() = groups.sumOf { it.ready }
    val totalCount: Int get() = groups.sumOf { it.total }

    companion object {
        fun build(live: LiveAccess, sdkInt: Int): PhonePlan {
            val groups = FeatureCatalog.groups().map { (g, caps) ->
                GroupCard(g, caps.map { c -> FeatureRow(c, Readiness.of(c, live), fixActionFor(c, live)) })
            }
            val waiting = FeatureCatalog.all
                .filter { it.need == Need.TOGGLE && Readiness.of(it, live) == Verdict.NEEDS_SETUP }
                .groupingBy { Readiness.toggleFor(it.capabilityId) }
                .eachCount()
            val steps = Readiness.Toggle.entries.mapNotNull { t ->
                val n = waiting[t] ?: return@mapNotNull null
                when (t) {
                    Readiness.Toggle.ACCESSIBILITY -> SetupStep(
                        t, "Turn on the AutoHost accessibility service",
                        "Lets tasks see and tap other apps. Android shows a warning; AutoHost only acts for tasks you run.",
                        "OPEN_ACCESSIBILITY_SETTINGS", n,
                    )
                    Readiness.Toggle.NOTIFICATION_ACCESS -> SetupStep(
                        t, "Allow notification access",
                        "Lets tasks read and answer notifications.",
                        "OPEN_NOTIFICATION_LISTENER_SETTINGS", n,
                    )
                    Readiness.Toggle.KEYBOARD -> SetupStep(
                        t, "Choose the AutoHost keyboard",
                        "Lets tasks type text reliably and use the clipboard in the background.",
                        "SELECT_IME", n,
                    )
                }
            }
            val advanced = groups.flatMap { it.rows }.any { it.verdict == Verdict.NEEDS_ADVANCED }
            return PhonePlan(steps, groups, AccessPlan.routes(sdkInt), advanced)
        }

        /** The FixHub action behind a row's button; null = nothing to press (ready, asks on use, per-task folder, or handled by the sheet). */
        fun fixActionFor(cap: CapabilityNeed, live: LiveAccess): String? = when (Readiness.of(cap, live)) {
            Verdict.NEEDS_SETUP -> when (Readiness.toggleFor(cap.capabilityId)) {
                Readiness.Toggle.ACCESSIBILITY -> "OPEN_ACCESSIBILITY_SETTINGS"
                Readiness.Toggle.NOTIFICATION_ACCESS -> "OPEN_NOTIFICATION_LISTENER_SETTINGS"
                Readiness.Toggle.KEYBOARD -> "SELECT_IME"
            }
            Verdict.NEEDS_SWITCH -> "OPEN_INSECURE_SWITCH"
            else -> null
        }
    }
}

/** The one line per [Verdict] shown under a feature title. Plain words; no ids. */
fun Verdict.line(): String = when (this) {
    Verdict.READY -> "Works now"
    Verdict.ASKS_ON_USE -> "Android asks the first time it is used"
    Verdict.NEEDS_SETUP -> "Needs one switch on this phone"
    Verdict.NEEDS_SWITCH -> "Off by default for safety — turn on in Settings"
    Verdict.NEEDS_ADVANCED -> "Optional: needs advanced access"
}
