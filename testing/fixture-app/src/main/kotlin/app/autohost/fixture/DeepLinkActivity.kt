package app.autohost.fixture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView

/**
 * DeepLinkActivity (docs/FIXTURE_APP.md): `fixture://screen/<name>?delay_ms=…&variant=…&locale=…` routes to
 * any screen with its extras, so `device.app.launch(uri=…)` can drive every scenario directly.
 */
class DeepLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.R.layout.simple_list_item_1) // invisible hop; shows the target name
        val uri: Uri = intent.data ?: run {
            finish()
            return
        }
        val name = uri.lastPathSegment ?: ""
        findViewById<TextView>(android.R.id.text1).text = "route:$name"
        val target = Intent(this, screenFor(name))
        target.putExtras(intent) // delay_ms / variant / locale / fail_after pass through
        startActivity(target)
        finish()
    }

    private fun screenFor(name: String): Class<out Activity> = when (name) {
        "button" -> ButtonScreenActivity::class.java
        "delayed" -> DelayedButtonScreenActivity::class.java
        "text" -> TextFieldScreenActivity::class.java
        "list" -> ListScreenActivity::class.java
        "unbounded" -> UnboundedRangeScreenActivity::class.java
        "dialog" -> DialogScreenActivity::class.java
        "permission" -> PermissionDialogScreenActivity::class.java
        "keyboard" -> KeyboardScreenActivity::class.java
        "dynamic" -> DynamicScreenActivity::class.java
        "secure" -> SecureScreenActivity::class.java
        "canvas" -> CanvasScreenActivity::class.java
        "web" -> WebViewScreenActivity::class.java
        "toast" -> ToastScreenActivity::class.java
        "clipboard" -> ClipboardScreenActivity::class.java
        "notification" -> NotificationScreenActivity::class.java
        "crash" -> CrashScreenActivity::class.java
        "locale" -> LocaleScreenActivity::class.java
        else -> HomeActivity::class.java
    }
}
