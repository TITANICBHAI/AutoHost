package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** SecureScreen (docs/FIXTURE_APP.md): FLAG_SECURE — screenshots must fail with a typed error. */
class SecureScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_secure)
        Screens.bind(this, "secure")
        findViewById<TextView>(R.id.fx_secure_note).text = "FLAG_SECURE is set: capture must be refused"
    }
}
