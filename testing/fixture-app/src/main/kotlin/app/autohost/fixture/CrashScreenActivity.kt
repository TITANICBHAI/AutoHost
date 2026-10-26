package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.Button

/** CrashScreen (docs/FIXTURE_APP.md): throws on demand — app crash handling and evidence capture. */
class CrashScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)
        Screens.bind(this, "crash")

        val failAfter = intent.getLongExtra("fail_after", 0L)
        if (failAfter > 0) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ crash() }, failAfter)
        }
        findViewById<Button>(R.id.fx_btn_crash).setOnClickListener { crash() }
    }

    private fun crash() {
        FixtureState.lastEvent = "crash:throwing"
        Screens.refresh(this)
        throw IllegalStateException("fixture crash requested (CrashScreen)")
    }
}
