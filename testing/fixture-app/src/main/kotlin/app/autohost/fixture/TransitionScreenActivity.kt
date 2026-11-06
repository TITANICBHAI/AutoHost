package app.autohost.fixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView

/**
 * TransitionScreen (docs/FIXTURE_APP.md v4, A2): the button starts a screen TRANSITION after
 * `delay_ms` (default 700). Unlike [DelayedButtonScreenActivity] (a widget appears), the whole
 * screen changes — `settle` must see the new window before the effect verdict.
 */
class TransitionScreenActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transition)
        Screens.bind(this, "transition")

        val delayMs = Screens.delayMs(this, default = 700)
        val status = findViewById<TextView>(R.id.fx_transition_status)
        status.text = "waiting"
        findViewById<Button>(R.id.fx_btn_transition).setOnClickListener {
            FixtureState.recordClick("fx_btn_transition")
            FixtureState.lastEvent = "transition_scheduled:${delayMs}ms"
            status.text = "navigating in ${delayMs}ms"
            FixtureState.persist(this)
            Screens.refresh(this)
            handler.postDelayed({
                FixtureState.lastEvent = "transition_done"
                FixtureState.persist(this)
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            }, delayMs)
        }
        Screens.refresh(this)
    }
}
