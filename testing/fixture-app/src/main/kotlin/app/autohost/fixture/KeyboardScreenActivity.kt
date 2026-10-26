package app.autohost.fixture

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText

/** KeyboardScreen (docs/FIXTURE_APP.md): focusable fields that raise the IME; hide button closes it. */
class KeyboardScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard)
        Screens.bind(this, "keyboard")

        val field = findViewById<EditText>(R.id.fx_kb_field)
        field.requestFocus()
        field.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
        findViewById<Button>(R.id.fx_kb_hide).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(field.windowToken, 0)
        }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) FixtureState.lastEvent = "focus:fx_kb_field"
        }
    }
}
