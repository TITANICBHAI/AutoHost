package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView

/** DelayedButtonScreen: `fx_btn_late` appears after `delay_ms` (default 2000) for wait_for/settle tests. */
class DelayedButtonScreenActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_delayed_button)
        FixtureState.load(this)
        FixtureState.screen = "delayed"

        val delayMs = intent.getLongExtra("delay_ms", 2_000L)
        val lateButton = findViewById<Button>(R.id.fx_btn_late)
        val status = findViewById<TextView>(R.id.fx_late_status)
        lateButton.visibility = android.view.View.GONE
        status.text = "waiting"

        handler.postDelayed({
            lateButton.visibility = android.view.View.VISIBLE
            status.text = "appeared"
            FixtureState.lastEvent = "late_button_appeared"
            FixtureState.persist(this)
            updateState()
        }, delayMs)

        lateButton.setOnClickListener {
            FixtureState.recordClick("fx_btn_late")
            updateState()
        }
        updateState()
    }

    private fun updateState() {
        findViewById<TextView>(R.id.fx_state).text = FixtureState.toJson().toString()
    }
}
