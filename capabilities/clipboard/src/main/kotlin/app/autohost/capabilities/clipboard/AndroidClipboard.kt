package app.autohost.capabilities.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** The Android implementation: ClipboardManager behind the pure provider. */
object AndroidClipboard : ClipboardProvider.System {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun manager(): ClipboardManager? =
        appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun read(): String? = try {
        val clip = manager()?.primaryClip ?: return null
        if (clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(appContext)?.toString()
    } catch (e: RuntimeException) {
        null
    }

    override fun write(text: String): Boolean = try {
        val manager = manager() ?: return false
        manager.setPrimaryClip(ClipData.newPlainText("autohost", text))
        true
    } catch (e: RuntimeException) {
        false
    }
}
