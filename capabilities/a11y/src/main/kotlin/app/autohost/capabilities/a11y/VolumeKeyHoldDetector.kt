package app.autohost.capabilities.a11y

import app.autohost.core.common.Clock

/**
 * P7-12: the hold gesture for the global stop (plan §10.3 "Global stop gesture — hold Volume-Down
 * to cancel all runs; off until the user enables it"). Pure state machine, unit-tested; the a11y
 * service feeds it key events from `onKeyEvent` (the XML config already declares
 * `canRequestFilterKeyEvents`).
 *
 * Contract with the volume keys: an armed DOWN is consumed (so the volume HUD does not pop while
 * the user holds), and if the key is released before the hold threshold the gesture simply ends —
 * the volume was suppressed for that press, which is the documented cost of the gesture.
 * Key-repeat DOWNs are ignored; a second key's events do not disturb an in-progress hold.
 */
class VolumeKeyHoldDetector(
    private val clock: Clock,
    private val holdMs: Long,
    private val keyCode: Int,
) {
    private var holding = false
    private var startedAtMs = 0L
    private var fired = false

    /** Key went down: `true` = consume the event (hold armed). Repeated downs keep it armed. */
    fun onKeyDown(code: Int, atMs: Long): Boolean {
        if (code != keyCode) return false
        if (!holding) {
            holding = true
            startedAtMs = atMs
            fired = false
        }
        return true
    }

    /**
     * Polled (service callback / listener tick): `true` exactly once when the hold threshold is
     * crossed while the key is still down.
     */
    fun poll(nowMs: Long): Boolean {
        if (!holding || fired) return false
        if (nowMs - startedAtMs >= holdMs) {
            fired = true
            return true
        }
        return false
    }

    /** Key went up: `true` if the gesture had already fired (the event stays consumed). */
    fun onKeyUp(code: Int, atMs: Long): Boolean {
        if (code != keyCode) return false
        if (!holding) return false
        holding = false
        return fired
    }

    /** Reset on filter disable / service reconnect. */
    fun reset() {
        holding = false
        fired = false
    }

    val isHolding: Boolean get() = holding
}
