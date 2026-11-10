package app.autohost.ui

import app.autohost.DevTask
import app.autohost.core.data.db.TaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * One row of "my automations" (U-01): the same task listed once, whether it was installed (template, import, example)
 * or pushed to the dev folder. [dev] is set only for dev-folder tasks, which carry what the Run button needs.
 */
data class TaskEntry(
    val taskId: String,
    val name: String,
    val source: Source,
    /** Runtime type for dev-folder tasks (`python`, `flow`, ...); null for installed tasks, whose manifest is read on the detail page. */
    val runtime: String?,
    val dev: DevTask?,
) {
    enum class Source { INSTALLED, DEV_FOLDER }
}

/** The single list every screen reads instead of reading the dev folder alone. Pure: no Android, no I/O. */
object TaskCatalog {

    /** Installed tasks plus dev-folder tasks; a task in both appears once and the dev-folder copy wins. Sorted by name. */
    fun merge(installed: List<TaskEntity>, dev: List<DevTask>): List<TaskEntry> {
        val byId = LinkedHashMap<String, TaskEntry>()
        for (task in installed) {
            byId[task.taskId] = TaskEntry(task.taskId, task.name, TaskEntry.Source.INSTALLED, null, null)
        }
        for (task in dev) {
            byId[task.taskId] = TaskEntry(task.taskId, task.name, TaskEntry.Source.DEV_FOLDER, task.manifest.runtime.type, task)
        }
        return byId.values.sortedWith(compareBy({ it.name.lowercase() }, { it.taskId }))
    }

    /** Live version: a task added or removed in either source shows up without a manual refresh. */
    fun observe(installed: Flow<List<TaskEntity>>, dev: Flow<List<DevTask>>): Flow<List<TaskEntry>> =
        combine(installed, dev) { i, d -> merge(i, d) }
}
