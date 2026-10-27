package app.autohost.capabilities.notifications

import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * P8-01: process-local access to the connected NotificationListenerService (the same pattern as
 * A11yBridge). The provider reads the active snapshot; the EventHub (P8-02) consumes the posted/
 * removed flow. Only what a task may see is stored: package, title, text, subtext/big text,
 * timestamp, ongoing flag and the action labels — never the extras bundle or content intents.
 */
object NotificationBridge {

    data class Action(val index: Int, val label: String)

    data class Active(
        val key: String,
        val pkg: String,
        val title: String,
        val text: String,
        val subtext: String,
        val bigText: String,
        val postedAtMs: Long,
        val ongoing: Boolean,
        val actions: List<Action>,
    )

    data class Event(val kind: String, val notification: Active, val atMs: Long)

    private val _active = MutableStateFlow<Map<String, Active>>(emptyMap())
    val active: StateFlow<Map<String, Active>> = _active.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile
    var connected: Boolean = false
        private set

    private var clock: app.autohost.core.common.Clock = app.autohost.core.common.SystemClockImpl()

    fun init(clock: app.autohost.core.common.Clock) {
        this.clock = clock
    }

    internal fun onConnected(current: List<StatusBarNotification>) {
        connected = true
        val snapshot = current.mapNotNull { extract(it) }.associateBy { it.key }
        _active.value = snapshot
    }

    internal fun onDisconnected() {
        connected = false
        _active.value = emptyMap()
    }

    internal fun posted(sbn: StatusBarNotification) {
        val item = extract(sbn) ?: return
        _active.value = _active.value + (item.key to item)
        _events.tryEmit(Event("notification.posted", item, clock.nowMs()))
    }

    internal fun removed(sbn: StatusBarNotification) {
        val item = extract(sbn) ?: return
        _active.value = _active.value - item.key
        _events.tryEmit(Event("notification.removed", item, clock.nowMs()))
    }

    /** Internal helper for the provider: records a dismissal we performed ourselves. */
    internal fun noteDismissed(key: String) {
        val item = _active.value[key] ?: return
        _active.value = _active.value - key
        _events.tryEmit(Event("notification.removed", item, clock.nowMs()))
    }

    /** Test seam: replace the snapshot outright (JVM tests only; never called by the app). */
    internal fun resetForTest(items: List<Active>) {
        _active.value = items.associateBy { it.key }
    }

    fun extract(sbn: StatusBarNotification): Active? {
        val pkg = sbn.packageName ?: return null
        val extras = sbn.notification?.extras ?: return null
        fun text(key: String): String = try {
            extras.getCharSequence(key)?.toString().orEmpty()
        } catch (e: RuntimeException) {
            ""
        }
        val actions = try {
            sbn.notification.actions.orEmpty().mapIndexedNotNull { index, action ->
                val label = action?.title?.toString() ?: return@mapIndexedNotNull null
                Action(index, label)
            }
        } catch (e: RuntimeException) {
            emptyList()
        }
        return Active(
            key = sbn.key ?: "${pkg}:${sbn.id}",
            pkg = pkg,
            title = text(android.app.Notification.EXTRA_TITLE),
            text = text(android.app.Notification.EXTRA_TEXT),
            subtext = text(android.app.Notification.EXTRA_SUB_TEXT),
            bigText = text(android.app.Notification.EXTRA_BIG_TEXT),
            postedAtMs = sbn.postTime,
            ongoing = sbn.isOngoing,
            actions = actions,
        )
    }
}
