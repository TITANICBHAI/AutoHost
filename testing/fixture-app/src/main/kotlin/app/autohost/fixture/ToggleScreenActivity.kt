package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.Switch

/**
 * ToggleScreen (docs/FIXTURE_APP.md v4, ACTION_HARDENING §5 / A2): a switch whose checked flag is
 * the ONLY thing a click changes — `fx_toggle` with a click counter. A hardened `device.ui.click`
 * flips it exactly once (`effect=changed`, no retry); a double-fire would count two clicks.
 */
class ToggleScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toggle)
        Screens.bind(this, "toggle")

        val toggle = findViewById<Switch>(R.id.fx_toggle)
        toggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            FixtureState.recordClick("fx_toggle") // the counter: one click must mean one flip
            FixtureState.lastEvent = "toggle:$checked"
            FixtureState.persist(this)
            Screens.refresh(this)
        }
        Screens.refresh(this)
    }
}
