package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

/** WebViewScreen (docs/FIXTURE_APP.md): a local HTML form from assets — web content nodes, browser tests. */
class WebViewScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        Screens.bind(this, "web")
        val web = findViewById<WebView>(R.id.fx_web)
        web.settings.javaScriptEnabled = true
        web.loadUrl("file:///android_asset/fixture_form.html")
    }
}
