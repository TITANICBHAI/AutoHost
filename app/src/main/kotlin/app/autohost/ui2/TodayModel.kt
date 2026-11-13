package app.autohost.ui2

import app.autohost.core.data.db.RunEntity
import app.autohost.core.model.RunStatus

/** One run as the Today tab shows it. */
data class TodayRun(
    val runId: String,
    val taskName: String,
    val status: RunStatus,
    val stage: String?,
    val progress: Float?,
    val startedAt: Long,
    val endedAt: Long?,
)

/** Today tab content: what needs the person, what is running, what finished lately. Pure: no Android, no I/O. */
data class TodayModel(val needsYou: List<TodayRun>, val active: List<TodayRun>, val recent: List<TodayRun>) {
    val isEmpty: Boolean get() = needsYou.isEmpty() && active.isEmpty() && recent.isEmpty()

    companion object {
        /** [names] maps task id to display name; a task without a name shows its id (never blank). Unknown statuses are ignored. */
        fun build(runs: List<RunEntity>, names: Map<String, String>, recentLimit: Int = 5): TodayModel {
            val rows = runs.mapNotNull { r ->
                val status = RunStatus.entries.firstOrNull { it.name == r.status } ?: return@mapNotNull null
                TodayRun(r.runId, names[r.taskId]?.takeIf { it.isNotBlank() } ?: r.taskId, status, r.currentStage, r.progress, r.startedAt, r.endedAt)
            }
            return TodayModel(
                needsYou = rows.filter { it.status == RunStatus.WAITING_USER }.sortedByDescending { it.startedAt },
                active = rows.filter { it.status.active && it.status != RunStatus.WAITING_USER }.sortedByDescending { it.startedAt },
                recent = rows.filter { it.status.terminal }.sortedByDescending { it.endedAt ?: it.startedAt }.take(recentLimit),
            )
        }
    }
}
