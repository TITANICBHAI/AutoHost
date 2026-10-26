package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/** ButtonScreen: `fx_btn_continue` with a visible counter `fx_clicks` (docs/FIXTURE_APP.md).
 *  The `variant=es` extra switches to label "Continuar" under id `fx_btn_continue_es` (multi-label tests). */
class ButtonScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_button)
        FixtureState.load(this)
        FixtureState.screen = "button"

        val variant = Screens.variant(this)
        val button = findViewById<Button>(R.id.fx_btn_continue)
        if (variant == "es") {
            button.text = "Continuar"
            button.id = R.id.fx_btn_continue_es // ids.xml entry; resource ids are mutable before use
        }
        button.setOnClickListener {
            FixtureState.recordClick(if (variant == "es") "fx_btn_continue_es" else "fx_btn_continue")
            updateState()
        }
        updateState()
    }

    private fun updateState() {
        findViewById<TextView>(R.id.fx_clicks).text =
            (FixtureState.clicks["fx_btn_continue"] ?: FixtureState.clicks["fx_btn_continue_es"] ?: 0).toString()
        findViewById<TextView>(R.id.fx_state).text = FixtureState.toJson().toString()
    }
}
