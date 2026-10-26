package app.autohost.capabilities.clipboard

/**
 * P8-05: the clipboard read policy (plan §6.1 `device.clipboard` — "Background read limits").
 * On Android 10+ an app can only read the clipboard while it holds focus (or provides the selected
 * IME — our `device.ime.clipboard_get` path). Writing is always allowed. The policy is pure so the
 * honest refusals are unit-tested.
 */
object ClipboardPolicy {

    sealed class ReadVerdict {
        /** The app owns focus: read the system clipboard directly. */
        data object ReadDirect : ReadVerdict()

        /** No focus: refuse honestly and point at the IME path. */
        data object RefuseBackground : ReadVerdict()
    }

    fun readVerdict(focused: Boolean): ReadVerdict =
        if (focused) ReadVerdict.ReadDirect else ReadVerdict.RefuseBackground

    /** The honest error message for the background case (§6.1: document the limit, never fake). */
    const val BACKGROUND_HINT: String =
        "Reading the clipboard requires this app to be in focus on Android 10+. " +
            "Use device.ime.clipboard_get while the AutoHost keyboard is selected for background reads."
}
