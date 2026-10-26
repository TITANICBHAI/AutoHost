package app.autohost.runtime.api

import app.autohost.core.model.RunSpec

/**
 * A runtime executes runs of one task kind (`python`, `flow`, `javascript`). The orchestrator asks the registry
 * for the provider matching the task's runtime and calls [start]; the provider owns the process/service machinery.
 */
interface RuntimeProvider {
    val type: String

    /** Starts the run described by [spec]. Returns once the worker is launched, not when the run finishes. */
    suspend fun start(spec: RunSpec): RunHandle

    /**
     * Called by the engine: [listener] receives the runId of a worker whose process died unexpectedly
     * (onServiceDisconnected / onBindingDied). Death of an already-finished run is ignored upstream.
     */
    fun setDeathListener(listener: ((runId: String) -> Unit)?)
}
