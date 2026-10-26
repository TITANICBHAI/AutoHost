package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast

/** ToastScreen (docs/FIXTURE_APP.md): a button that shows a Toast — toast event extraction tests. */
class ToastScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toast)
        Screens.bind(this, "toast")

        findViewById<Button>(R.id.fx_btn_toast).setOnClickListener { view ->
            val text = "Fixture toast ${System.currentTimeMillis() / 1000 % 1000}"
            FixtureState.lastEvent = "toast:shown"
            Screens.refresh(this)
            Toast.makeText(view.context, text, Toast.LENGTH_SHORT).show()
        }
    }
}
