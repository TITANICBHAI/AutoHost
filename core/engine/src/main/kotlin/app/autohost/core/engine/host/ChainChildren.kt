package app.autohost.core.engine.host

import java.util.concurrent.ConcurrentHashMap

/**
 * INT-E3: which runs a run started through `tasks.run`, so that stopping the parent can stop them too (the agent's
 * `run_task`, a script's `autohost.macros.run` and a flow's `runTask` all come through there). Kept in memory only: a
 * restart ends every run anyway. Thread-safe, because runs start on worker threads and the run store is observed on
 * another.
 */
class ChainChildren {
    private val byParent = ConcurrentHashMap<String, MutableSet<String>>()

    /** Remembers that [parentRunId] started [childRunId]. */
    fun started(parentRunId: String, childRunId: String) {
        byParent.computeIfAbsent(parentRunId) { ConcurrentHashMap.newKeySet<String>() }.add(childRunId)
    }

    /** The children [parentRunId] started, handed out once: the entry is gone afterwards. Empty when there are none. */
    fun take(parentRunId: String): Set<String> = byParent.remove(parentRunId)?.toSet() ?: emptySet()
}
