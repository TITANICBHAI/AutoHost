package app.autohost.runtime.python

import app.autohost.broker.ICapabilityBroker
import app.autohost.core.common.AhJson
import app.autohost.core.model.RunSpec
import app.autohost.runtime.api.RunHandle
import app.autohost.runtime.api.RuntimeProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.autohost.broker.ITaskWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs Python tasks in worker processes (`:w0`…`:w3`, HANDOFF §3). Each start binds one TaskWorkerService,
 * hands it the RunSpec + the broker binder, and returns a handle for the cooperative controls. Workers are
 * disposable: they kill their own process ~0.5 s after emitting the `finished` event.
 */
class PythonRuntimeProvider(
    private val context: Context,
    private val brokerBinder: ICapabilityBroker,
    private val startTimeoutMs: Long = 15_000,
) : RuntimeProvider {

    override val type: String = "python"

    @Volatile
    private var deathListener: ((String) -> Unit)? = null

    private val nextSlot = AtomicInteger(0)

    override fun setDeathListener(listener: ((String) -> Unit)?) {
        deathListener = listener
    }

    private val serviceClasses = listOf(
        app.autohost.runtime.python.worker.TaskWorkerService0::class.java,
        app.autohost.runtime.python.worker.TaskWorkerService1::class.java,
        app.autohost.runtime.python.worker.TaskWorkerService2::class.java,
        app.autohost.runtime.python.worker.TaskWorkerService3::class.java,
    )

    override suspend fun start(spec: RunSpec): RunHandle {
        val serviceClass = serviceClasses[Math.floorMod(nextSlot.getAndIncrement(), serviceClasses.size)]
        val intent = Intent(context, serviceClass)
        val connection = WorkerConnection(spec.runId)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            deathListener?.invoke(spec.runId) // report as death so the run is INTERRUPTED, not assumed running
            throw IllegalStateException("could not bind worker service ${serviceClass.simpleName}")
        }
        val worker = withTimeoutOrNull(startTimeoutMs) { connection.awaitWorker() }
        if (worker == null) {
            runCatching { context.unbindService(connection) }
            deathListener?.invoke(spec.runId)
            throw IllegalStateException("worker ${serviceClass.simpleName} did not connect within ${startTimeoutMs}ms")
        }
        // start() returns immediately; the run executes on the worker's own thread (AIDL contract).
        worker.start(AhJson.json.encodeToString(RunSpec.serializer(), spec), brokerBinder)
        return WorkerRunHandle(spec.runId, worker, connection, context, deathListener)
    }

    private inner class WorkerConnection(private val runId: String) : ServiceConnection {
        @Volatile
        private var worker: ITaskWorker? = null

        private val waiter = kotlinx.coroutines.CompletableDeferred<ITaskWorker>()

        suspend fun awaitWorker(): ITaskWorker {
            worker?.let { return it }
            return waiter.await()
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val itaskWorker = ITaskWorker.Stub.asInterface(binder)
            worker = itaskWorker
            waiter.complete(itaskWorker)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            worker = null
            deathListener?.invoke(runId)
        }

        override fun onBindingDied(name: ComponentName?) {
            worker = null
            deathListener?.invoke(runId)
        }
    }

    private class WorkerRunHandle(
        override val runId: String,
        private val worker: ITaskWorker,
        private val connection: ServiceConnection,
        private val appContext: Context,
        private val deathListener: ((String) -> Unit)?,
    ) : RunHandle {

        private fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                // Controls after process death are no-ops.
            }
        }

        override fun requestCancel() = guarded { worker.requestCancel() }

        override fun requestPause() = guarded { worker.requestPause() }

        override fun requestResume() = guarded { worker.requestResume() }

        override fun kill() {
            // Forceful: unbind and ask the system to sweep the now-empty process. The worker also kills itself
            // after the finished event; kill() is for the hang case where no finished event will ever come.
            guarded {
                appContext.unbindService(connection)
                val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                activityManager.killBackgroundProcesses(appContext.packageName)
            }
        }

        override fun release() = guarded { appContext.unbindService(connection) }
    }
}
