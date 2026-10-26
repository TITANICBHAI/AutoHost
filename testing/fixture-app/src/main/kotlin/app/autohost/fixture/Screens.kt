package app.autohost.fixture

import android.app.Activity
import android.widget.TextView

/**
 * Shared plumbing for every fixture screen (docs/FIXTURE_APP.md): registers the screen name in the state
 * readout and keeps the visible `fx_state` mirror current. Screens call [bindState] after inflating.
 */
object Screens {

    /** Sets the screen name and binds the fx_state mirror; call after setContentView. */
    fun bind(activity: Activity, screen: String) {
        FixtureState.load(activity)
        FixtureState.screen = screen
        activity.findViewById<TextView>(R.id.fx_state)?.text = FixtureState.toJson().toString()
    }

    /** Refreshes the fx_state mirror after a state change. */
    fun refresh(activity: Activity) {
        activity.findViewById<TextView>(R.id.fx_state)?.text = FixtureState.toJson().toString()
    }

    /** `delay_ms` extra shared by several screens (docs/FIXTURE_APP.md §launch behaviours). */
    fun delayMs(activity: Activity, default: Long = 1_000): Long =
        activity.intent.getLongExtra("delay_ms", default)

    /** `variant` extra: selects alternative ids/labels (e.g. the Spanish Continue). */
    fun variant(activity: Activity): String = activity.intent.getStringExtra("variant") ?: ""
}
