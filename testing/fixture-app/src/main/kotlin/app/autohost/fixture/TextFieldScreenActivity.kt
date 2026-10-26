package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.widget.EditText
import android.widget.TextView

/** TextFieldScreen: `fx_text`, `fx_password`, `fx_multiline` and a Unicode echo `fx_echo`. */
class TextFieldScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_field)
        FixtureState.load(this)
        FixtureState.screen = "text"

        val text = findViewById<EditText>(R.id.fx_text)
        val password = findViewById<EditText>(R.id.fx_password)
        val multiline = findViewById<EditText>(R.id.fx_multiline)
        val echo = findViewById<TextView>(R.id.fx_echo)
        password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        multiline.setSingleLine(false)
        multiline.minLines = 3

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                FixtureState.recordText("fx_text", text.text.toString())
                FixtureState.recordText("fx_password", password.text.toString())
                FixtureState.recordText("fx_multiline", multiline.text.toString())
                // Echo shows the text field content with a marker: proves text landed (password never echoed).
                echo.text = "echo:" + text.text.toString()
                updateState()
            }
        }
        text.addTextChangedListener(watcher)
        password.addTextChangedListener(watcher)
        multiline.addTextChangedListener(watcher)
        updateState()
    }

    private fun updateState() {
        findViewById<TextView>(R.id.fx_state).text = FixtureState.toJson().toString()
    }
}
