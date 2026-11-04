package app.autohost.core.designsystem.theme

/**
 * P14-01 (ADR-094): AutoHost's own design tokens — the 4 dp grid, radii, motion durations and the
 * `reducedMotion` contract. Structure follows the ClosePaw reference docs (`docs/REUSE_MATRIX.md`
 * RM-F1, reference-only); the numbers are AutoHost's own candidates from `docs/UI_SPEC.md` §10.
 * Pure data + pure decisions so the JVM tests in `TokensTest` hold without Compose.
 */
object Tokens {

    /** Every spacing value MUST be a multiple of 4 dp (the grid contract). */
    const val GRID_STEP_DP = 4

    /** Grid spacing steps (dp): 4/8/12/20/32. */
    val spacingDp: List<Int> = listOf(4, 8, 12, 20, 32)

    /** Corner radii (dp): small controls, sheets, capsules. */
    val radiiDp: List<Int> = listOf(8, 10, 16)

    /** Motion durations (ms): micro feedback, standard transition, expressive, hero. No springs. */
    val motionMs: List<Int> = listOf(120, 240, 480, 900)

    /** Standard and expressive durations are the ones most surfaces use. */
    const val MOTION_STANDARD_MS = 240
    const val MOTION_EXPRESSIVE_MS = 480

    /** Minimum touch target (dp) — accessibility contract (P14-09). */
    const val MIN_TOUCH_TARGET_DP = 48

    /** Auto-hide delay for the capsule's Done state (ms). */
    const val CAPSULE_DONE_AUTO_HIDE_MS = 2_000L

    /** Approval ask default timeout (ms) — deny-and-stop on expiry (AGENT_RUNTIME §15). */
    const val APPROVAL_TIMEOUT_MS = 60_000L

    /**
     * The grid contract, checked: every tokenised spacing value is a positive multiple of 4.
     * Tests pin this so a future token cannot quietly leave the grid.
     */
    fun onGrid(dp: Int): Boolean = dp > 0 && dp % GRID_STEP_DP == 0

    /**
     * The `reducedMotion` contract: every animation duration is scaled to a floor of 0 ms
     * (instant) when reduced motion is on — cross-fades stay, movement disappears.
     * Pure decision so composables can honour it without branching on their own.
     */
    fun motionDurationMs(preferredMs: Int, reducedMotion: Boolean): Int =
        if (reducedMotion) 0 else preferredMs.coerceIn(0, motionMs.max())
}
