package app.autohost.core.engine.events

import app.autohost.core.common.Clock
import kotlinx.serialization.json.JsonObject

/**
 * P8-02: one normalized device event (plan §11.8). [kind] is a `TriggerSource.EVENT_ONLY` member
 * (the schema enum is the source of truth); [payload] is minimal and privacy-scoped — an
 * app-foreground event carries only the package name, a notification event carries title/text,
 * never secrets or password-field text (plan §11.9 payload rule; run-visible copies additionally
 * pass the Redactor).
 */
data class TriggerEvent(
    val kind: String,
    val pkg: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
    val atMs: Long,
)

/**
 * The event backbone (plan §11.8): sources pump normalized [TriggerEvent]s in through [dispatch];
 * subscribers (the TriggerController for trigger rules, `device.events.recent` readers) receive
 * fan-out by kind filter. Pure JVM — the Android sources live in the app layer and are wired in
 * AppGraph, so the whole backbone is unit-testable with the fake clock.
 *
 * Delivery notes:
 *  - dispatch never throws and never blocks: subscriber callbacks run inline and must be cheap
 *    (the controller's matching is pure; anything slow it does on its own scope).
 *  - a bounded ring buffer keeps the last [bufferLimit] events for `device.events.recent`,
 *    triggers.status debugging and the health view; it is not a delivery mechanism.
 */
class EventHub(private val clock: Clock, private val bufferLimit: Int = DEFAULT_BUFFER) {

    /** Returns false from the callback to unsubscribe (a self-removing one-shot). */
    fun interface Subscriber {
        fun onEvent(event: TriggerEvent): Boolean
    }

    data class Subscription(val kinds: Set<String>?, internal val id: Long, private val hub: EventHub) {
        fun dispose() = hub.unsubscribe(id)
    }

    private val lock = Any()
    private val subscribers = LinkedHashMap<Long, Entry>()
    private val buffer = ArrayDeque<TriggerEvent>()
    private var nextId = 1L

    private class Entry(val sub: Subscriber, val kinds: Set<String>?)

    fun subscribe(kinds: Set<String>? = null, sub: Subscriber): Subscription = synchronized(lock) {
        val id = nextId++
        subscribers[id] = Entry(sub, kinds)
        Subscription(kinds, id, this)
    }

    private fun unsubscribe(id: Long) = synchronized(lock) { subscribers.remove(id) }

    /** Fan-out to matching subscribers (in registration order) and buffer the event. */
    fun dispatch(event: TriggerEvent) {
        val targets = synchronized(lock) {
            buffer.addLast(event)
            while (buffer.size > bufferLimit) buffer.removeFirst()
            subscribers.values.filter { it.kinds == null || event.kind in it.kinds }.map { it.sub }
        }
        for (sub in targets) {
            try {
                if (!sub.onEvent(event)) removeSubscriber(sub)
            } catch (e: RuntimeException) {
                // A broken subscriber must not starve the others; the controller logs its own errors.
            }
        }
    }

    private fun removeSubscriber(target: Subscriber) = synchronized(lock) {
        subscribers.entries.removeIf { it.value.sub === target }
    }

    /** The buffered tail, newest last; [kinds] filters, [limit] trims to the most recent. */
    fun recent(kinds: Set<String>? = null, limit: Int = 50): List<TriggerEvent> = synchronized(lock) {
        val filtered = if (kinds == null) buffer.toList() else buffer.filter { it.kind in kinds }
        filtered.takeLast(limit.coerceIn(1, bufferLimit))
    }

    fun bufferedCount(): Int = synchronized(lock) { buffer.size }

    fun clear() = synchronized(lock) {
        buffer.clear()
        subscribers.clear()
    }

    companion object {
        const val DEFAULT_BUFFER = 200
    }
}
