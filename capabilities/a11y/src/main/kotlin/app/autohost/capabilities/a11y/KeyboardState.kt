package app.autohost.capabilities.a11y

/**
 * Keyboard state for `device.ui` reads (plan §7.6): whether the IME is visible, and what the focused field
 * looks like. Pure data + decision; the reader extracts the IME window from the service's window list.
 */
object KeyboardState {

    data class State(
        val visible: Boolean,
        /** Resource id / class of the focused editable node, when known. */
        val focusedId: String? = null,
        val focusedEditable: Boolean = false,
    )

    /** An input-method window at any layer means the keyboard is up. */
    fun evaluate(windows: List<RootResolver.WindowMeta>, focusedId: String?, focusedEditable: Boolean): State =
        State(
            visible = windows.any { it.type == "input_method" },
            focusedId = focusedId,
            focusedEditable = focusedEditable,
        )
}
