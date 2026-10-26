package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * LocaleScreen (docs/FIXTURE_APP.md): labels in `en`/`es` per the `locale` extra — multi-label selectors
 * (`sel.text("Continue", "Continuar")`) against one screen.
 */
class LocaleScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_locale)
        Screens.bind(this, "locale")

        val locale = intent.getStringExtra("locale") ?: "en"
        val label = if (locale == "es") "Continuar" else "Continue"
        FixtureState.lastEvent = "locale:$locale"
        findViewById<TextView>(R.id.fx_locale_label).text = label
        Screens.refresh(this)
    }
}
