package app.autohost.capabilities.intents

import android.content.Context
import android.content.Intent

/**
 * The Android side of `device.intent` (P8-06): starts activities with FLAG_ACTIVITY_NEW_TASK and
 * sends broadcasts through the app context. Results are honest about being unverified — actual
 * foreground verification is `device.app.launch`'s job (S2 chain), this capability just dispatches.
 */
object AndroidIntents {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun startActivity(builder: (Intent) -> Intent): Boolean = try {
        val context = appContext ?: return false
        context.startActivity(builder(Intent()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        false
    }

    fun broadcast(builder: (Intent) -> Intent): Boolean = try {
        val context = appContext ?: return false
        context.sendBroadcast(builder(Intent()))
        true
    } catch (e: RuntimeException) {
        false
    }
}
