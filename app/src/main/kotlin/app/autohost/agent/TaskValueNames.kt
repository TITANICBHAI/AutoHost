package app.autohost.agent

import app.autohost.DevTask
import app.autohost.runtime.flow.FlowSources
import app.autohost.ui2.pickers.TaskChoice

/**
 * INT-E4b (docs/INTEGRATION_V3.md §5 E): which values a saved task takes, so the agent can be told what it may hand over.
 *
 * A macro lists the values it can be given in the `env` block of its own flow, and that block is exactly what a caller is
 * allowed to override, so its names are what the agent may pass. Only the names are read, never the defaults behind them.
 * A script or an installed task declares none yet, so for those the agent is told nothing and cannot pass a value.
 *
 * Reading a name opens the task's file: call [declared] and [allowed] off the main thread.
 */
object TaskValueNames {

    /** The most names listed for one task: the same as the most values one `run_task` call may carry. */
    const val MAX_NAMES = 8

    /** The longest name the agent can write (the argument check refuses a longer one before the `=`). */
    private const val MAX_NAME_CHARS = 40

    private const val FLOW = "flow"

    /** The value names this dev-folder task takes, in the order its flow lists them. Empty when it takes none or cannot be read. */
    fun declared(task: DevTask?): List<String> {
        if (task == null || task.manifest.runtime.type != FLOW) return emptyList()
        val file = FlowSources.resolveFlowFile(task.dir, task.manifest.runtime.entry) ?: return emptyList()
        val document = try {
            FlowSources.loadFlowDocument(file)
        } catch (e: Exception) {
            return emptyList()
        }
        return document.env.keys.filter { usable(it) }.take(MAX_NAMES)
    }

    /**
     * The ticked tasks as the agent's starter wants them: each with the names it takes. A task that is not in the dev folder
     * (an installed one) takes none, so the agent may start it but not hand it values.
     */
    fun allowed(chosen: List<TaskChoice>, dev: List<DevTask>): List<AgentRunStarter.AllowedTask> =
        chosen.map { choice ->
            AgentRunStarter.AllowedTask(choice.taskId, choice.name, declared(dev.firstOrNull { it.taskId == choice.taskId }))
        }

    /** A name the agent can write as `name=value`: not empty, not padded, no `=`, no control character, not too long. */
    private fun usable(name: String): Boolean =
        name.isNotEmpty() && name == name.trim() && name.length <= MAX_NAME_CHARS && '=' !in name && name.none { it.isISOControl() }
}
