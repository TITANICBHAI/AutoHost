package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView

/**
 * SilentFieldScreen (docs/FIXTURE_APP.md v4, A1): `fx_silent` is an editable field that ACCEPTS
 * every `ACTION_SET_TEXT` but reverts its content — the NumberPicker-like silent failure. A
 * hardened `set_text` must read back, see the unchanged text, and report honestly
 * (`effect=unverifiable` / not verified), never `verified=true`.
 */
class SilentFieldScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_silent_field)
        Screens.bind(this, "silent")

        val silent = findViewById<EditText>(R.id.fx_silent)
        val echo = findViewById<TextView>(R.id.fx_silent_echo)
        // The silent-failure shape: the field reverts every change to the committed value
        // ("locked"), so the framework-level set "succeeds" while nothing renders.
        val committed = "locked"
        silent.setText(committed)
        silent.addTextChangedListener(object : TextWatcher {
            private var reverting = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (reverting) return
                if (s?.toString() != committed) {
                    reverting = true
                    silent.setText(committed)
                    silent.setSelection(committed.length)
                    reverting = false
                }
                FixtureState.recordText("fx_silent", silent.text.toString())
                echo.text = "echo:" + silent.text.toString()
                FixtureState.lastEvent = "silent_text_reverted"
                FixtureState.persist(this@SilentFieldScreenActivity)
                Screens.refresh(this@SilentFieldScreenActivity)
            }
        })
        Screens.refresh(this)
    }
}
