package app.autohost.taskui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.autohost.broker.ICapabilityBroker
import app.autohost.broker.IUiHandlerWorker
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import app.autohost.core.broker.RunGrants
import app.autohost.core.broker.RunTokenStore
import app.autohost.core.engine.taskui.UI_HANDLER_TIMEOUT_MS
import app.autohost.core.engine.taskui.UiHandlerGate
import app.autohost.core.engine.taskui.UiHandlerOutcome
import app.autohost.core.engine.taskui.UiHandlerSpec
import app.autohost.core.engine.tasks.GrantBuilder
import app.autohost.core.model.TaskManifest
import app.autohost.runtime.python.worker.UiHandlerWorkerService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * P7-03: the main-process half of the UI-handler path. Any surface that invokes a task handler —
 * Tier B buttons, the Tier C JS bridge, notification actions — goes through here. Per call:
 * mint a short-lived run token (the task's grants, so handler broker calls are scoped exactly like
 * run calls), bind the `:ui` worker, hand over the spec, enforce the 30 s cap, revoke the token.
 *
 * Single-flight per task (refuse, never queue — the surface can retry); the worker process is the
 * system's cache: we bind on demand and unbind after the call, so the `:ui` process stays warm while
 * it is being used and exits when the worker's own idle timer fires (§5.2).
 */
class UiHandlerController(
    private val context: Context,
    private val data: app.autohost.core.data.repo.DataGraph,
    private val grants: GrantBuilder,
    private val tokens: RunTokenStore,
    private val brokerBinder: ICapabilityBroker,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {

    private val gate = UiHandlerGate()

    suspend fun call(taskId: String, handler: String, args: JsonObject): UiHandlerOutcome {
        if (!gate.tryAcquire(taskId)) return UiHandlerOutcome.BUSY
        try {
            val prepared = prepare(taskId, handler, args) ?: return notFound(taskId, handler)
            val (spec, grantMap) = prepared
            val runId = "ui-" + taskId
            val token = tokens.issue(RunGrants(runId, taskId, spec.profileId, grantMap, clock.nowMs()))
            try {
                return dispatch(spec, token)
            } finally {
                tokens.revoke(token)
            }
        } finally {
            gate.release(taskId)
        }
    }

    private data class Prepared(
        val spec: UiHandlerSpec,
        val grantMap: Map<String, JsonObject>,
    )

    private suspend fun prepare(taskId: String, handler: String, args: JsonObject): Prepared? {
        val version = data.tasks.latestVersion(taskId) ?: return null
        val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), version.manifestJson)
        val config = data.tasks.configOf(taskId)
        // Handlers run against task config, never profile secrets: a UI callback must not need the
        // profile's secret material to render or react (§14.2). ctx.profile is an empty object here.
        val profile = JsonObject(emptyMap())
        val grantMap = grants.build(manifest, config, profile)
        val spec = UiHandlerSpec.build(
            taskId = taskId,
            taskPath = version.bundlePath,
            entry = manifest.runtime.entry,
            handler = handler,
            args = args,
            config = config,
            profile = profile,
            profileId = null,
        )
        return Prepared(spec, grantMap)
    }

    private suspend fun dispatch(spec: UiHandlerSpec, token: String): UiHandlerOutcome {
        val specJson = spec.copy(runToken = token).toJson().toString()
        val connection = WorkerConnection()
        val bound = runCatching {
            context.bindService(Intent(context, UiHandlerWorkerService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            AhLog.w(TAG, "could not bind ${UiHandlerWorkerService::class.java.simpleName}")
            return UiHandlerOutcome("FAILED", "START_FAILED", "the UI-handler worker did not start")
        }
        try {
            val worker = withTimeoutOrNull(START_TIMEOUT_MS) { connection.await() }
                ?: return UiHandlerOutcome("FAILED", "START_FAILED", "the UI-handler worker did not connect")
            val raw = withTimeoutOrNull(UI_HANDLER_TIMEOUT_MS) { runCatching { worker.callHandler(specJson, brokerBinder) }.getOrNull() }
                ?: return UiHandlerOutcome.TIMEOUT
            return UiHandlerOutcome.fromJson(raw)
                ?: UiHandlerOutcome("FAILED", "INTERNAL", "the UI-handler worker returned a malformed answer")
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun notFound(taskId: String, handler: String): UiHandlerOutcome =
        UiHandlerOutcome("FAILED", "HANDLER_NOT_FOUND", "task '$taskId' (handler '$handler') is not installed")

    private class WorkerConnection : ServiceConnection {
        private val waiter = CompletableDeferred<IUiHandlerWorker>()
        @Volatile private var worker: IUiHandlerWorker? = null

        suspend fun await(): IUiHandlerWorker {
            worker?.let { return it }
            return waiter.await()
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val stub = IUiHandlerWorker.Stub.asInterface(binder)
            worker = stub
            waiter.complete(stub)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            worker = null
        }
    }

    companion object {
        private const val TAG = "taskui"
        private const val START_TIMEOUT_MS = 10_000L
    }
}
