package app.autohost.runtime.python.worker

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import app.autohost.broker.ICapabilityBroker
import app.autohost.broker.ITaskWorker
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.common.LogLevel
import app.autohost.core.common.LogSink
import app.autohost.core.common.SystemClockImpl
import app.autohost.core.model.RunSpec
import app.autohost.runtime.python.PyBridge
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.atomic.AtomicReference

/**
 * One worker process, one run at a time (HANDOFF §3). Starts Chaquopy once per process, executes
 * `autohost._runtime.run(specJson, PyBridge)` on a background thread, emits a heartbeat every 2 s, and
 * forwards the cooperative controls into Python (`autohost._runtime.request_*`). The worker kills its own
 * process ~0.5 s after the Python call returns (the `finished` event has been delivered by then).
 */
abstract class BaseTaskWorkerService : Service() {

    private val runThread = AtomicReference<Thread?>(null)
    private var heartbeat: HeartbeatEmitter? = null
    private val clock = SystemClockImpl()

    private val binder = object : ITaskWorker.Stub() {
        override fun start(runSpecJson: String?, broker: ICapabilityBroker?) {
            val specJson = runSpecJson ?: return
            val capabilityBroker = broker ?: return
            val bridge = PyBridge(capabilityBroker, runToken = runSpecToken(specJson))
            launchRun(specJson, bridge)
        }

        override fun requestPause() = callPython("request_pause")

        override fun requestResume() = callPython("request_resume")

        override fun requestCancel() = callPython("request_cancel")

        override fun ping(): String = "pong"
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        heartbeat = HeartbeatEmitter(clock)
        AhLog.install(workerLogSink)
    }

    override fun onDestroy() {
        heartbeat?.stop()
        super.onDestroy()
    }

    private fun launchRun(specJson: String, bridge: PyBridge) {
        heartbeat?.start(bridge)
        val runtimeModule = Python.getInstance().getModule("autohost._runtime")
        val thread = Thread {
            try {
                val outcome = runtimeModule.callAttr("run", specJson, bridge).toString()
                AhLog.i(TAG, "worker run finished: " + outcome.take(200))
            } catch (e: Throwable) {
                // Python never lets script exceptions escape run(); reaching here means the interpreter died.
                val message = "interpreter error: " + e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                AhLog.e(TAG, message)
                runCatching {
                    bridge.emit(
                        "{\"type\":\"finished\",\"data\":{\"status\":\"FAILED\",\"reason\":\"WORKER_CRASH\"," +
                            "\"message\":\"" + message.replace("\"", "'") + "\"}}",
                    )
                }
            } finally {
                enqueueSelfDestruct()
            }
        }
        runThread.set(thread)
        thread.start()
    }

    private fun callPython(function: String) {
        try {
            Python.getInstance().getModule("autohost._runtime").callAttr(function)
        } catch (e: Throwable) {
            // No run installed (or interpreter gone): nothing to control.
        }
    }

    /** Worker processes are disposable: the run is over, so nothing must survive it. */
    private fun enqueueSelfDestruct() {
        Handler(Looper.getMainLooper()).postDelayed({
            AhLog.i(TAG, "worker self-recycling")
            Process.killProcess(Process.myPid())
        }, SELF_DESTRUCT_DELAY_MS)
    }

    private fun runSpecToken(specJson: String): String = try {
        AhJson.json.decodeFromString(RunSpec.serializer(), specJson).runToken
    } catch (e: Exception) {
        ""
    }

    private val workerLogSink = LogSink { entry ->
        android.util.Log.println(levelPriority(entry.level), "autohost.w", "[${entry.tag}] ${entry.message}")
    }

    private companion object {
        const val TAG = "worker"
        const val SELF_DESTRUCT_DELAY_MS = 500L

        fun levelPriority(level: LogLevel): Int = when (level) {
            LogLevel.DEBUG -> android.util.Log.DEBUG
            LogLevel.INFO -> android.util.Log.INFO
            LogLevel.WARN -> android.util.Log.WARN
            LogLevel.ERROR -> android.util.Log.ERROR
        }
    }
}

/** Emits `heartbeat` events through the bridge every 2 s while the run is alive (plan §5.2). */
class HeartbeatEmitter(private val clock: app.autohost.core.common.Clock) {
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false

    fun start(bridge: PyBridge) {
        stopped = false
        val task = object : Runnable {
            override fun run() {
                if (stopped) return
                runCatching {
                    bridge.emit("{\"type\":\"heartbeat\",\"ts\":" + clock.nowMs().toString() + "}")
                }
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        handler.post(task)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val INTERVAL_MS = 2_000L
    }
}
