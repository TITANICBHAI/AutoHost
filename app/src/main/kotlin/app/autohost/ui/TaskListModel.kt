package app.autohost.ui

import app.autohost.core.data.db.RunEntity
import app.autohost.core.model.RunStatus

/** What the Library shows on a card's status chip. "Needs setup" is not computed yet (it needs the permission review). */
enum class TaskStatus { RUNNING, READY }

/** The outcome of the most recent FINISHED run of a task, as the person sees it. */
enum class LastResult { NONE, SUCCEEDED, FAILED, STOPPED }

/** One Library card: the task plus what its runs say. [lastRunAt] is the end time (or start time) of the last finished run. */
data class TaskRow(
    val entry: TaskEntry,
    val status: TaskStatus,
    val lastResult: LastResult,
    val lastRunAt: Long?,
)

/** Pure list logic for the Library screen (S04): status from the runs, and search. No Android, no I/O. */
object TaskListModel {

    /**
     * [runs] may be in any order. A task is RUNNING while any of its runs is still active (queued, starting,
     * running, paused or waiting for the person). The last result comes from the newest FINISHED run.
     */
    fun rows(entries: List<TaskEntry>, runs: List<RunEntity>): List<TaskRow> {
        val byTask = runs.groupBy { it.taskId }
        return entries.map { entry ->
            val taskRuns = byTask[entry.taskId].orEmpty()
            val running = taskRuns.any { statusOf(it)?.active == true }
            val lastFinished = taskRuns
                .filter { statusOf(it)?.terminal == true }
                .maxByOrNull { it.endedAt ?: it.startedAt }
            TaskRow(
                entry = entry,
                status = if (running) TaskStatus.RUNNING else TaskStatus.READY,
                lastResult = lastFinished?.let { resultOf(statusOf(it)) } ?: LastResult.NONE,
                lastRunAt = lastFinished?.let { it.endedAt ?: it.startedAt },
            )
        }
    }

    /** Case-insensitive match on the task NAME (ids are developer detail and are not searched). Blank query keeps everything. */
    fun search(rows: List<TaskRow>, query: String): List<TaskRow> {
        val q = query.trim()
        if (q.isEmpty()) return rows
        return rows.filter { it.entry.name.contains(q, ignoreCase = true) }
    }

    enum class AgeUnit { NOW, MINUTES, HOURS, DAYS }

    /** How long ago [thenMs] was, in the coarsest unit that fits ("just now", 5 min, 3 h, 2 d). A future time counts as now. */
    fun age(nowMs: Long, thenMs: Long): Pair<AgeUnit, Long> {
        val delta = (nowMs - thenMs).coerceAtLeast(0L)
        val minutes = delta / 60_000L
        return when {
            minutes < 1 -> AgeUnit.NOW to 0L
            minutes < 60 -> AgeUnit.MINUTES to minutes
            minutes < 60 * 24 -> AgeUnit.HOURS to minutes / 60
            else -> AgeUnit.DAYS to minutes / (60 * 24)
        }
    }

    private fun statusOf(run: RunEntity): RunStatus? = RunStatus.entries.firstOrNull { it.name == run.status }

    private fun resultOf(status: RunStatus?): LastResult = when (status) {
        RunStatus.SUCCEEDED -> LastResult.SUCCEEDED
        RunStatus.FAILED, RunStatus.INTERRUPTED -> LastResult.FAILED
        RunStatus.CANCELLED, RunStatus.SKIPPED -> LastResult.STOPPED
        else -> LastResult.NONE
    }
}
