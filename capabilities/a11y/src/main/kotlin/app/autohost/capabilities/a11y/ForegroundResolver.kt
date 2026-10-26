package app.autohost.capabilities.a11y

import app.autohost.core.common.Clock

/**
 * Resolves the foreground app *authoritatively* (plan §5.7): the root package of the selected window wins;
 * window-state event metadata is only a fallback for the exact same window. Emits `APP_ENTERED` /
 * `APP_EXITED` transitions into [transitions] — the events capability consumes them in Phase 8.
 */
class ForegroundResolver(private val clock: Clock) {

    data class Input(
        /** Package of the root of the window the service considers active/focused (authoritative). */
        val activeWindowPkg: String?,
        /** Package from the last window-state event (fallback only). */
        val eventPkg: String?,
        /** Window id the event referred to. */
        val eventWindowId: Int?,
        /** The window the resolver considers active. */
        val activeWindowId: Int?,
    )

    data class Transition(val kind: Kind, val pkg: String, val atMs: Long) {
        enum class Kind { APP_ENTERED, APP_EXITED }
    }

    private val _transitions = ArrayDeque<Transition>()
    private var current: String? = null

    /** Resolve + record the transition if the foreground app changed. Returns the resolved package. */
    @Synchronized
    fun resolve(input: Input): String? {
        val pkg = when {
            input.activeWindowPkg != null -> input.activeWindowPkg // authoritative: root of the selected window
            // Event metadata is trusted only when it refers to the very window we consider active (plan §5.7).
            input.eventPkg != null && input.eventWindowId != null && input.eventWindowId == input.activeWindowId -> input.eventPkg
            else -> null
        }
        update(pkg)
        return pkg
    }

    /** Records an externally resolved package (used by the service event path). */
    @Synchronized
    fun update(pkg: String?) {
        val previous = current
        if (pkg == previous) return
        val now = clock.nowMs()
        previous?.let { _transitions.addLast(Transition(Transition.Kind.APP_EXITED, it, now)) }
        pkg?.let { _transitions.addLast(Transition(Transition.Kind.APP_ENTERED, it, now)) }
        current = pkg
        while (_transitions.size > 64) _transitions.removeFirst()
    }

    @Synchronized
    fun current(): String? = current

    /** Drains the buffered transitions (oldest first). */
    @Synchronized
    fun drain(): List<Transition> {
        val out = _transitions.toList()
        _transitions.clear()
        return out
    }
}
