package app.autohost.ui2

/**
 * The first-run flow as a list of full-screen pages (owner feedback 2026-11-14: onboarding must be its own steps, not a
 * section of a tab). Pure: the pages come from the same [GetStarted] steps the Setup screen uses, so the two never disagree.
 */
object OnboardingPlan {

    sealed interface Page {
        data object Welcome : Page
        data class Step(val step: GetStartedStep) : Page
        data object Finish : Page
    }

    /** Welcome, then one page per step in [GetStarted] order, then the finish page. */
    fun pages(steps: List<GetStartedStep>): List<Page> =
        listOf<Page>(Page.Welcome) + steps.map { Page.Step(it) } + Page.Finish

    /** "Step 2 of 6" on a step page; empty on the welcome and finish pages. */
    fun label(index: Int, pages: List<Page>): String {
        if (pages.getOrNull(index) !is Page.Step) return ""
        val number = pages.take(index + 1).count { it is Page.Step }
        val total = pages.count { it is Page.Step }
        return "Step $number of $total"
    }

    /** Keeps an index (for example one remembered from before the phone's state changed) inside the pages. */
    fun clamp(index: Int, pages: List<Page>): Int = index.coerceIn(0, maxOf(pages.lastIndex, 0))

    /** The forward button on a step page: plain "Next" once it is done, an honest "Skip for now" before. */
    fun nextLabel(step: GetStartedStep): String = if (step.done) "Next" else "Skip for now"

    /** The sentence on the finish page. */
    fun summary(steps: List<GetStartedStep>): String {
        val (done, total) = GetStarted.counts(steps)
        val required = GetStarted.requiredLeft(steps)
        return when {
            required == 1 -> "$done of $total steps done. One required step is still open, so tasks that need it will not run yet. " +
                "You can finish it any time from Settings."
            required > 1 -> "$done of $total steps done. $required required steps are still open, so tasks that need them will not run yet. " +
                "You can finish them any time from Settings."
            else -> "$done of $total steps done. Everything AutoHost needs to run your tasks is on."
        }
    }
}

/**
 * Which page the person was on while they visited a system screen and came back. In memory on purpose: a restarted app
 * starts the welcome again, which is fine, and nothing here is worth a database row.
 */
object OnboardingMemory {
    @Volatile var page: Int = 0
}
