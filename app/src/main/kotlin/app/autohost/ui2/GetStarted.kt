package app.autohost.ui2

/** How much a first-run step matters. REQUIRED = most tasks cannot work without it. */
enum class Level { REQUIRED, RECOMMENDED, OPTIONAL }

enum class StepId { ACCESSIBILITY, KEEP_ALIVE, NOTIFICATIONS, KEYBOARD, AI_MODEL, FIRST_TASK }

/**
 * One line of the first-run checklist (docs/UI_V2.md §2, owner feedback 2026-11-14: design for the first launch, not for a
 * phone that is already set up). [action] is a FixHub action, or "OPEN_AI_SETTINGS" / "ADD_TASK" which the screen handles itself.
 */
data class GetStartedStep(
    val id: StepId,
    val title: String,
    val why: String,
    val level: Level,
    val done: Boolean,
    val action: String,
    val button: String,
)

/** What the first-run checklist needs to know that is not in [LiveAccess]. */
data class SetupFacts(val aiConfigured: Boolean = false, val hasTasks: Boolean = false, val onboarded: Boolean = false)

object GetStarted {

    fun steps(live: LiveAccess, facts: SetupFacts): List<GetStartedStep> = listOf(
        GetStartedStep(
            StepId.ACCESSIBILITY, "Turn on the AutoHost accessibility service",
            "This is how tasks see and tap other apps. Without it most tasks cannot run. Android shows a warning; AutoHost only acts when you run a task.",
            Level.REQUIRED, live.accessibilityOn, "OPEN_ACCESSIBILITY_SETTINGS", "Open Accessibility",
        ),
        GetStartedStep(
            StepId.KEEP_ALIVE, "Let AutoHost run in the background",
            "Stops Android from putting scheduled and triggered tasks to sleep. Some phone brands need extra steps; Settings, then What this phone allows, lists them.",
            Level.RECOMMENDED, live.batteryExempt, "BATTERY_EXEMPTION", "Allow",
        ),
        GetStartedStep(
            StepId.NOTIFICATIONS, "Allow notification access",
            "Only needed for tasks that read or answer notifications.",
            Level.OPTIONAL, live.notificationAccessOn, "OPEN_NOTIFICATION_LISTENER_SETTINGS", "Open",
        ),
        GetStartedStep(
            StepId.KEYBOARD, "Choose the AutoHost keyboard",
            "Only needed for tasks that type text or use the clipboard in the background.",
            Level.OPTIONAL, live.keyboardSelected, "SELECT_IME", "Choose",
        ),
        GetStartedStep(
            StepId.AI_MODEL, "Add an AI model (your own key)",
            "Optional. Lets you ask AutoHost to do things in plain words. You bring the API key; nothing is sent until you turn it on.",
            Level.OPTIONAL, facts.aiConfigured, "OPEN_AI_SETTINGS", "Set up",
        ),
        GetStartedStep(
            StepId.FIRST_TASK, "Add your first task",
            "Start from a template, a quick macro, or a task file from your phone's storage. No computer needed.",
            Level.RECOMMENDED, facts.hasTasks, "ADD_TASK", "Add",
        ),
    )

    fun requiredLeft(steps: List<GetStartedStep>): Int = steps.count { it.level == Level.REQUIRED && !it.done }

    /** The step to highlight: the first open required one, else the first open recommended one, else null. */
    fun next(steps: List<GetStartedStep>): GetStartedStep? =
        steps.firstOrNull { it.level == Level.REQUIRED && !it.done }
            ?: steps.firstOrNull { it.level == Level.RECOMMENDED && !it.done }

    /** True while the Tasks tab should nudge toward Setup: a required step is open, or the person never finished the checklist. */
    fun needsAttention(steps: List<GetStartedStep>, facts: SetupFacts): Boolean =
        requiredLeft(steps) > 0 || !facts.onboarded

    fun counts(steps: List<GetStartedStep>): Pair<Int, Int> = steps.count { it.done } to steps.size
}
