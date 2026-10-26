package app.autohost.fixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/** Fixture home (docs/FIXTURE_APP.md): navigation to every screen with stable fx_ ids. */
class HomeActivity : Activity() {

    private val routes = mapOf(
        R.id.fx_open_button to ButtonScreenActivity::class.java,
        R.id.fx_open_delayed to DelayedButtonScreenActivity::class.java,
        R.id.fx_open_text to TextFieldScreenActivity::class.java,
        R.id.fx_open_list to ListScreenActivity::class.java,
        R.id.fx_open_unbounded to UnboundedRangeScreenActivity::class.java,
        R.id.fx_open_dialog to DialogScreenActivity::class.java,
        R.id.fx_open_permission to PermissionDialogScreenActivity::class.java,
        R.id.fx_open_keyboard to KeyboardScreenActivity::class.java,
        R.id.fx_open_dynamic to DynamicScreenActivity::class.java,
        R.id.fx_open_secure to SecureScreenActivity::class.java,
        R.id.fx_open_canvas to CanvasScreenActivity::class.java,
        R.id.fx_open_web to WebViewScreenActivity::class.java,
        R.id.fx_open_toast to ToastScreenActivity::class.java,
        R.id.fx_open_clipboard to ClipboardScreenActivity::class.java,
        R.id.fx_open_notification to NotificationScreenActivity::class.java,
        R.id.fx_open_crash to CrashScreenActivity::class.java,
        R.id.fx_open_locale to LocaleScreenActivity::class.java,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        FixtureState.load(this)
        FixtureState.screen = "home"

        for ((id, target) in routes) {
            findViewById<Button>(id)?.setOnClickListener {
                startActivity(Intent(this, target).putExtras(intent)) // pass through requested extras
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateState()
    }

    private fun updateState() {
        FixtureState.load(this)
        findViewById<TextView>(R.id.fx_state).text = FixtureState.toJson().toString()
    }
}
