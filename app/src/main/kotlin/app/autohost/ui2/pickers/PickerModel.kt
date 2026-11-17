package app.autohost.ui2.pickers

import app.autohost.ui2.macro.MacroCompiler

/** One installed app a person can pick: they see [label]; the task stores [pkg]. */
data class AppChoice(val pkg: String, val label: String)

/** One task a person can pick to run from a macro: they see [name] and [kind]; the macro stores [taskId]. */
data class TaskChoice(val taskId: String, val name: String, val kind: String?)

/**
 * The list rules behind the app picker and the task picker (docs/INTEGRATION_V3.md §5 C2). Pure, so they are tested on
 * the JVM. The point of both pickers is that an ordinary person never types, reads or searches a package name or a task
 * id: they see names, and the id is only what the macro stores.
 */
object PickerModel {

    /** One row per package, sorted by name, [hide] left out, narrowed by what [query] matches in the name. */
    fun apps(all: List<AppChoice>, query: String, hide: String? = null): List<AppChoice> {
        val q = query.trim()
        return all
            .filter { it.pkg != hide }
            .distinctBy { it.pkg }
            .filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))
    }

    /** One row per task, sorted by name, [exclude] left out, narrowed by what [query] matches in the name (never the id). */
    fun tasks(all: List<TaskChoice>, query: String, exclude: String? = null): List<TaskChoice> {
        val q = query.trim()
        return all
            .filter { it.taskId != exclude }
            .distinctBy { it.taskId }
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.taskId }))
    }

    /** What the app button shows for the stored value: the app's name, or null when nothing is stored or the app is gone. */
    fun appLabel(all: List<AppChoice>, pkg: String): String? =
        if (pkg.isBlank()) null else all.firstOrNull { it.pkg == pkg }?.label

    /** What the task button shows for the stored value: the task's name, or null when nothing is stored or the task is gone. */
    fun taskName(all: List<TaskChoice>, taskId: String): String? =
        if (taskId.isBlank()) null else all.firstOrNull { it.taskId == taskId }?.name

    /** The short word under a task's name: what kind of task it is. [runtime] is the manifest's runtime type, if known. */
    fun taskKind(taskId: String, runtime: String?): String? = when {
        taskId.startsWith(MacroCompiler.ID_PREFIX) -> "Macro"
        runtime == "python" -> "Python script"
        runtime == "javascript" -> "JavaScript"
        runtime == "flow" -> "Flow"
        else -> null
    }
}
