package app.autohost.capabilities.a11y

/**
 * P12-08 (A4, RM-A7): the pure scroll plan. Replaces the hardcoded 1080×2400 defaults
 * (`A11yUiProvider.kt:67-68`) and the screen-centre swipe (521-529) with:
 *  - the REAL display size injected by the caller (`ApiGate`/`maximumWindowMetrics` on API 31,
 *    else `getRealMetrics` — the provider wiring passes it in);
 *  - the gesture placed INSIDE the target scrollable's bounds when one is given (a nested
 *    scrollable is scrolled where it lives, not where the screen centre happens to be);
 *  - the ACCESSIBILITY scroll is tried first by the caller (`ACTION_SCROLL_FORWARD/BACKWARD` on
 *    the scrollable node) — the gesture is the fallback for nodes that are not scrollable.
 */
object ScrollPlanner {

    data class Stroke(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    data class Plan(val accessibilityFirst: Boolean, val stroke: Stroke?)

    /**
     * Plans one scroll step.
     *
     * @param displayW/H      the REAL display size (injected — never a hardcoded default)
     * @param scrollable      the target scrollable's bounds, or null when scrolling the screen
     * @param scrollableNode  whether the target node itself is scrollable (accessibility scroll
     *                        applies); when true the plan says accessibility-first and no gesture
     * @param direction       up | down | left | right
     * @param fraction        the swipe distance as a fraction of the scrollable/screen (default 0.45)
     */
    fun plan(
        displayW: Int,
        displayH: Int,
        scrollable: Bounds?,
        scrollableNode: Boolean,
        direction: String,
        fraction: Double = 0.45,
    ): Plan {
        require(direction in setOf("up", "down", "left", "right")) { "unknown direction $direction" }
        if (scrollableNode) return Plan(accessibilityFirst = true, stroke = null)
        // The scroll region: the scrollable's bounds when known, else the full display.
        val l = scrollable?.l ?: 0
        val t = scrollable?.t ?: 0
        val r = scrollable?.r ?: displayW
        val b = scrollable?.b ?: displayH
        val w = r - l
        val h = b - t
        val cx = l + w / 2f
        val cy = t + h / 2f
        val distance = (if (direction == "up" || direction == "down") h else w) * fraction
        val stroke = when (direction) {
            "down" -> Stroke(cx, cy + distance / 2, cx, cy - distance / 2) // content moves up
            "up" -> Stroke(cx, cy - distance / 2, cx, cy + distance / 2)
            "right" -> Stroke(cx + distance / 2, cy, cx - distance / 2, cy)
            else -> Stroke(cx - distance / 2, cy, cx + distance / 2, cy)
        }
        // The gesture stays INSIDE the region: clamp to the bounds with a margin so the stroke
        // never starts on the system gesture areas at the screen edge (the A4 off-screen defect).
        val margin = 8f
        val clamped = Stroke(
            stroke.x1.coerceIn(l + margin, r - margin),
            stroke.y1.coerceIn(t + margin, b - margin),
            stroke.x2.coerceIn(l + margin, r - margin),
            stroke.y2.coerceIn(t + margin, b - margin),
        )
        return Plan(accessibilityFirst = false, stroke = clamped)
    }
}
