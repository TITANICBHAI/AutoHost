package app.autohost.fixture

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

/** ClipboardScreen (docs/FIXTURE_APP.md): copy/paste fields — clipboard via the AutoHost IME. */
class ClipboardScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clipboard)
        Screens.bind(this, "clipboard")

        val source = findViewById<EditText>(R.id.fx_clip_source)
        val target = findViewById<EditText>(R.id.fx_clip_target)
        findViewById<Button>(R.id.fx_clip_copy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("fixture", source.text.toString()))
            FixtureState.lastEvent = "clipboard:copied"
            Screens.refresh(this)
            Toast.makeText(this, "copied", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.fx_clip_paste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            target.setText(text)
            FixtureState.recordText("fx_clip_target", text)
            Screens.refresh(this)
        }
    }
}
