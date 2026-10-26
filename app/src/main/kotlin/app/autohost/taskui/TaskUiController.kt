package app.autohost.taskui

import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.model.taskui.DeclarativeUi
import app.autohost.core.model.taskui.UiAction
import app.autohost.core.model.taskui.UiBindingScope
import app.autohost.core.model.taskui.UiBindings
import app.autohost.core.model.taskui.UiWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * P7-01: state + actions for the Tier B declarative task UI (plan §10.1). The Compose renderer
 * (:app) renders a parsed [DeclarativeUi]; this controller owns the binding scope (config/profile/
 * run/state/input), the action dispatch (run, run_stage, call, navigate, confirm) and the `state`
 * patch channel that lets handlers update the UI without a full run.
 *
 * Honest v1 limits (ADR-055): `state` starts empty and is only grown by handler results' "state"
 * patches in this process's lifetime (checkpoint-seeded state arrives with the surfaces pass);
 * the logview widget renders `ui.console` events from the task's most recent run, refreshed on
 * [refreshConsole] — live streaming during a run is the Run screen's job.
 */
class TaskUiController(
    private val data: app.autohost.core.data.repo.DataGraph,
    private val runStore: app.autohost.core.engine.orchestrator.RunStore,
    private val handlers: UiHandlerController,
    private val startRun: suspend (String, String?, String, JsonObject) -> String?,
    private val scope: CoroutineScope,
) {

    data class LoadResult(
        val doc: DeclarativeUi?,
        val taskName: String,
        val error: String? = null,
    )

    private val stateByTask = ConcurrentHashMap<String, MutableStateFlow<JsonObject>>()
    private val consoleByTask = ConcurrentHashMap<String, MutableStateFlow<List<String>>>()
    private val bindingScopeByTask = ConcurrentHashMap<String, MutableStateFlow<UiBindingScope>>()

    fun state(taskId: String): StateFlow<JsonObject> =
        stateByTask.getOrPut(taskId) { MutableStateFlow(JsonObject(emptyMap())) }.asStateFlow()

    fun console(taskId: String): StateFlow<List<String>> =
        consoleByTask.getOrPut(taskId) { MutableStateFlow(emptyList()) }.asStateFlow()

    fun bindingScope(taskId: String): StateFlow<UiBindingScope> =
        bindingScopeByTask.getOrPut(taskId) { MutableStateFlow(UiBindingScope()) }.asStateFlow()

    /** Loads and parses `ui/<file>` from the installed bundle; a bad document is an honest error. */
    suspend fun load(taskId: String): LoadResult {
        val version = data.tasks.latestVersion(taskId)
            ?: return LoadResult(null, "", "task '$taskId' is not installed")
        val manifest = AhJson.json.decodeFromString(app.autohost.core.model.TaskManifest.serializer(), version.manifestJson)
        val uiSpec = manifest.ui
            ?: return LoadResult(null, manifest.name, "this task has no declarative UI (manifest.ui is missing)")
        val file = File(version.bundlePath, uiSpec.file ?: "ui/main.json")
        if (!file.isFile) {
            return LoadResult(null, manifest.name, "declared UI file is missing: ${uiSpec.file ?: "ui/main.json"}")
        }
        return try {
            val element = AhJson.json.parseToJsonElement(file.readText())
            val doc = DeclarativeUi.parse(element as? JsonObject ?: JsonObject(emptyMap()))
            refreshScope(taskId)
            LoadResult(doc, manifest.name)
        } catch (e: Exception) {
            LoadResult(null, manifest.name, "the UI document is invalid: ${e.message}")
        }
    }

    /** Rebuilds the binding scope from current config, latest run and state (input comes per-call). */
    suspend fun refreshScope(taskId: String) {
        val config = data.tasks.configOf(taskId)
        val latest = runStore.runs.value.lastOrNull { it.taskId == taskId }
        val runInfo = if (latest != null) {
            mapOf(
                "status" to latest.status.name,
                "stage" to (latest.stage ?: ""),
                "reason" to (latest.reason ?: ""),
                "attempt" to latest.attempt.toString(),
            )
        } else {
            emptyMap()
        }
        val current = bindingScopeByTask.getOrPut(taskId) { MutableStateFlow(UiBindingScope()) }
        current.value = UiBindingScope(
            config = config,
            run = runInfo,
            state = state(taskId).value,
        )
    }

    /** Pulls `ui.console` events from the task's most recent run into the logview buffer. */
    fun refreshConsole(taskId: String) {
        scope.launch {
            val latest = runStore.runs.value.lastOrNull { it.taskId == taskId } ?: return@launch
            try {
                // Persisted events carry the type in `message` when no message was set (bridge rule),
                // and the payload in dataJson. ui.console lines live in data.text.
                val rows = data.runs.eventsAfter(latest.runId, sinceSeq = 0, limit = 500)
                val consoleLines = rows
                    .filter { it.message == "ui.console" }
                    .mapNotNull { row ->
                        row.dataJson?.let { text ->
                            runCatching {
                                val obj = AhJson.json.parseToJsonElement(text) as? JsonObject
                                (obj?.get("text") as? JsonPrimitive)?.content
                            }.getOrNull()
                        } ?: row.message
                    }
                consoleByTask.getOrPut(taskId) { MutableStateFlow(emptyList()) }.value = consoleLines
            } catch (e: Exception) {
                AhLog.w(TAG, "console refresh failed: ${e.message}")
            }
        }
    }

    /** Renders one widget-tree string through the current scope (renderer calls this per string). */
    fun bind(template: String, taskId: String): UiBindings.Bound =
        UiBindings.bind(template, bindingScope(taskId).value)

    sealed class ActionOutcome {
        data object Handled : ActionOutcome()
        data class Navigate(val route: String) : ActionOutcome()
        data class CallResult(val ok: Boolean, val reason: String?, val message: String?, val data: JsonObject?) : ActionOutcome()
        data class Failed(val message: String) : ActionOutcome()
    }

    /** Dispatches a UI action. Inputs are the renderer's current widget values ({{input.NAME}}). */
    suspend fun onAction(taskId: String, action: UiAction, inputs: JsonObject = JsonObject(emptyMap())): ActionOutcome {
        refreshScope(taskId)
        val scopeNow = bindingScope(taskId).value.copy(input = inputs)
        return when (action) {
            is UiAction.Run -> {
                val payload = if (action.stage != null) {
                    buildJsonObject { put("stage", action.stage) }
                } else {
                    JsonObject(emptyMap())
                }
                val runId = startRun(taskId, null, "manual", payload)
                if (runId != null) ActionOutcome.Handled
                else ActionOutcome.Failed("the run could not start (see Setup & Health)")
            }
            is UiAction.Call -> {
                // Bind {{...}} templates inside the declared args against the current scope.
                val boundArgs = bindObject(action.args, scopeNow)
                val outcome = handlers.call(taskId, action.handler, boundArgs)
                if (outcome.ok) {
                    (outcome.data?.get("state") as? JsonObject)?.let { patch ->
                        val stateFlow = stateByTask.getOrPut(taskId) { MutableStateFlow(JsonObject(emptyMap())) }
                        stateFlow.value = merge(stateFlow.value, patch)
                        refreshScope(taskId)
                    }
                    ActionOutcome.CallResult(true, null, null, outcome.data)
                } else {
                    ActionOutcome.CallResult(false, outcome.reason, outcome.message, null)
                }
            }
            is UiAction.Navigate -> ActionOutcome.Navigate(action.route)
            is UiAction.Confirm -> ActionOutcome.Handled // resolved by the renderer's dialog first
        }
    }

    private fun bindObject(args: JsonObject, scope: UiBindingScope): JsonObject = buildJsonObject {
        for ((key, value) in args) {
            when (value) {
                is JsonPrimitive -> if (value.isString) {
                    put(key, UiBindings.bind(value.content, scope).value)
                } else {
                    put(key, value)
                }
                is JsonObject -> put(key, bindObject(value, scope))
                else -> put(key, value)
            }
        }
    }

    private fun merge(base: JsonObject, patch: JsonObject): JsonObject {
        val mutable = base.toMutableMap()
        for ((key, value) in patch) mutable[key] = value
        return JsonObject(mutable)
    }

    companion object {
        private const val TAG = "taskui"
    }
}
