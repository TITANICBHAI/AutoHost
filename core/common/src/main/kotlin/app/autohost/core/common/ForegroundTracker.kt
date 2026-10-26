package app.autohost.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What is on screen right now, written by the accessibility service on window-state events and read by
 * `device.app` foreground methods (HANDOFF §3). Lives in `:core:common` so provider and app code share it
 * without an Android dependency.
 */
object ForegroundTracker {
    data class Foreground(val pkg: String, val activity: String?, val tsMs: Long)

    private val _state = MutableStateFlow<Foreground?>(null)
    val state: StateFlow<Foreground?> = _state.asStateFlow()

    fun update(pkg: String, activity: String?, nowMs: Long) {
        if (pkg.isBlank()) return
        _state.value = Foreground(pkg, activity, nowMs)
    }

    /** True when [pkg] was observed foreground no longer than [maxAgeMs] ago. */
    fun isForeground(pkg: String, nowMs: Long, maxAgeMs: Long = 3_000): Boolean {
        val current = _state.value ?: return false
        return current.pkg == pkg && nowMs - current.tsMs <= maxAgeMs
    }

    fun reset() {
        _state.value = null
    }
}

/** Injectable read side (fakes in tests; the default reads [ForegroundTracker]). */
fun interface ForegroundSource {
    fun current(): ForegroundTracker.Foreground?

    companion object {
        val live: ForegroundSource = ForegroundSource { ForegroundTracker.state.value }
    }
}
