package app.autohost.capabilities.a11y

/**
 * Toast extraction (plan Phase 3 "toast events"). The framework event carries a `Toast` parcelable whose
 * text is best-effort; this object keeps the extraction rules pure and testable. The service feeds events
 * in; consumers (events capability in Phase 8, run logs today) read [A11yBridge.lastToast].
 */
object ToastListener {

    data class ToastEvent(val pkg: String?, val text: String, val atMs: Long)

    /**
     * Extracts a toast payload. [parcelableClassName] is the event's parcelable data class name
     * (`android.widget.Toast`), [text] is the resolved toast text (setter/format may vary per API level,
     * so the service resolves it and hands the plain string here).
     */
    fun extract(pkg: String?, parcelableClassName: String?, text: String?, atMs: Long): ToastEvent? {
        if (parcelableClassName != "android.widget.Toast") return null
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return null
        return ToastEvent(pkg, body, atMs)
    }
}
