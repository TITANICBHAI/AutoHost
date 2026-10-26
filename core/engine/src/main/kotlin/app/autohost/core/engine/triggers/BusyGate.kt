package app.autohost.core.engine.triggers

import kotlinx.serialization.json.JsonObject

/**
 * P8-03: the busy gate (plan §11.9 `busy_policy`) — what happens when the target task already has
 * an active run (or holds the UI lock) at launch time.
 *
 *  - `drop`    → the event is dropped (the default; quiet and honest).
 *  - `replace` → the controller cancels the active run(s) of the task, then starts the new one.
 *  - `queue`   → the payload is appended to a bounded per-rule queue; when the queue is full the
 *                OLDEST pending payload is dropped first (plan wording), the newest still waits.
 *
 * Pure function over the observable facts; the controller owns the actual queue and cancellation.
 */
object BusyGate {

    sealed class Decision {
        /** Task is free — launch immediately. */
        data object Fire : Decision()

        /** Cancel the active run(s), then launch. */
        data object Replace : Decision()

        /** Task busy, policy says drop. */
        data object Drop : Decision()

        /** Enqueue (possibly after dropping the oldest). */
        data class Enqueue(val droppedOldest: Int) : Decision()
    }

    fun decide(policy: String, taskBusy: Boolean, queueDepth: Int, queueMax: Int): Decision {
        if (!taskBusy) return Decision.Fire
        return when (policy) {
            "replace" -> Decision.Replace
            "queue" -> {
                if (queueDepth >= queueMax) Decision.Enqueue(droppedOldest = 1)
                else Decision.Enqueue(droppedOldest = 0)
            }
            else -> Decision.Drop
        }
    }
}
