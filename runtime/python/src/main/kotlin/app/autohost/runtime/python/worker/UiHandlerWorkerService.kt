package app.autohost.runtime.python.worker

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import app.autohost.broker.ICapabilityBroker
import app.autohost.broker.IUiHandlerWorker
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.common.LogSink
import app.autohost.core.common.LogLevel
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P7-03 (plan §5.2): the warm UI-handler worker (`:ui`). Task-UI callbacks run here — one call at a
 * time, 30 s cap — so a slow handler never queues behind (or inside) a long run. The process starts
 * Chaquopy on first use, serves `callHandler` requests by delegating to
 * `autohost._runtime.run_ui_handler`, and stops itself after [IDLE_STOP_MS] without work (low-RAM
 * rule: the warm worker exits when idle). The controller additionally enforces the 30 s cap from the
 * main-process side; the two caps are belt and suspenders.
 *
 * The runToken arrives inside the handler spec (minted per call by `UiHandlerController` and revoked
 * right after), so broker calls from a handler are scoped exactly like run calls.
 */
class UiHandlerWorkerService : Service() {

    private val inFlight = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    private val idleHandler = Handler(Looper.getMainLooper())
    private var pythonStarted = false

    private val binder = object : IUiHandlerWorker.Stub() {

        override fun callHandler(handlerSpecJson: String?, broker: ICapabilityBroker?): String {
            val specJson = handlerSpecJson ?: return outcomeJson("FAILED", "INVALID_ARGS", "missing handler spec")
            val capabilityBroker = broker ?: return outcomeJson("FAILED", "INVALID_ARGS", "missing broker")
            if (!inFlight.compareAndSet(false, true)) {
                return outcomeJson("FAILED", "HANDLER_BUSY", "a handler call is already running")
            }
            pushIdleStopBack()
            return try {
                startPythonIfNeeded()
                val runToken = specField(specJson, "runToken")
                val bridge = app.autohost.runtime.python.PyBridge(capabilityBroker, runToken)
                val module = Python.getInstance().getModule("autohost._runtime")
                val future = executor?.submit<String> { module.callAttr("run_ui_handler", specJson, bridge).toString() }
                awaitOutcome(future)
            } catch (e: Throwable) {
                AhLog.e(TAG, "ui handler crashed: " + e.javaClass.simpleName + ": " + e.message)
                outcomeJson("FAILED", "WORKER_CRASH", "interpreter error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                inFlight.set(false)
                scheduleIdleStop()
            }
        }

        override fun ping(): String = "pong"
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { r -> Thread(r, "autohost-ui-handler") }
        AhLog.install(workerLogSink)
        scheduleIdleStop()
    }

    override fun onDestroy() {
        idleHandler.removeCallbacksAndMessages(null)
        executor?.shutdownNow()
        super.onDestroy()
    }

    private fun startPythonIfNeeded() {
        if (pythonStarted) return
        synchronized(this) {
            if (!pythonStarted) {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this))
                }
                pythonStarted = true
            }
        }
    }

    /** Host-side cap first (controller), then our own join with grace — a hung handler loses the race. */
    private fun awaitOutcome(future: Future<String>?): String {
        if (future == null) return outcomeJson("FAILED", "WORKER_CRASH", "no executor")
        return try {
            future.get(HANDLER_DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Cooperative cancel first (run_ui_handler installs a Runtime; request_cancel reaches it
            // at the next SDK call), then report the timeout honestly. The thread may still finish
            // in the background; the next call is BUSY until it does.
            runCatching {
                Python.getInstance().getModule("autohost._runtime").callAttr("request_cancel")
            }
            try {
                future.get(CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (inner: Throwable) {
                future.cancel(true)
                outcomeJson("FAILED", "HANDLER_TIMEOUT", "the handler did not finish within ${HANDLER_DEADLINE_MS / 1000} s")
            }
        } catch (e: Exception) {
            outcomeJson("FAILED", "WORKER_CRASH", "interpreter error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun pushIdleStopBack() {
        idleHandler.removeCallbacksAndMessages(null)
    }

    private fun scheduleIdleStop() {
        idleHandler.removeCallbacksAndMessages(null)
        idleHandler.postDelayed({
            if (!inFlight.get()) {
                AhLog.i(TAG, "ui-handler worker idle, stopping")
                stopSelf()
            }
        }, IDLE_STOP_MS)
    }

    private fun specField(specJson: String, key: String): String = try {
        (AhJson.json.parseToJsonElement(specJson) as? kotlinx.serialization.json.JsonObject)
            ?.get(key)?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun outcomeJson(status: String, reason: String, message: String): String =
        "{\"status\":\"$status\",\"reason\":\"$reason\",\"message\":\"" +
            message.replace("\"", "'").replace("\n", " ") + "\"}"

    private val workerLogSink = LogSink { entry ->
        android.util.Log.println(levelPriority(entry.level), "autohost.ui", "[${entry.tag}] ${entry.message}")
    }

    companion object {
        private const val TAG = "ui-worker"
        /** Matches the controller cap plus the cooperative-cancel grace. */
        const val HANDLER_DEADLINE_MS: Long = 32_000L
        const val CANCEL_GRACE_MS: Long = 3_000L
        /** Idle self-stop (low-RAM rule §5.2): the warm worker exits when not needed. */
        const val IDLE_STOP_MS: Long = 30_000L

        fun levelPriority(level: LogLevel): Int = when (level) {
            LogLevel.DEBUG -> android.util.Log.DEBUG
            LogLevel.INFO -> android.util.Log.INFO
            LogLevel.WARN -> android.util.Log.WARN
            LogLevel.ERROR -> android.util.Log.ERROR
        }
    }
}
