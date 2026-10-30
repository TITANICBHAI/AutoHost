package app.autohost.surfaces

import android.os.Handler
import android.os.Looper
import app.autohost.core.common.AhLog
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Crash-fix plan, step 3.1. Overlay windows (`WindowManager.addView`) and view changes must run on the main thread,
 * but the controllers and the broker call into overlays from background threads, so every overlay entry point goes
 * through here.
 */
internal object MainThread {
    private const val TAG = "main-thread"
    private const val DEFAULT_TIMEOUT_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())

    fun isMain(): Boolean = Looper.myLooper() === Looper.getMainLooper()

    /** Fire and forget. Runs inline when already on the main thread. Never throws. */
    fun post(block: () -> Unit) {
        if (isMain()) {
            runGuarded(block)
        } else {
            handler.post { runGuarded(block) }
        }
    }

    /**
     * Runs [block] on the main thread and waits for its result for at most [timeoutMs].
     * Returns [fallback] when the main thread stays busy for longer than that, or when [block] throws. Never throws.
     */
    fun <T> call(fallback: T, timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: () -> T): T {
        if (isMain()) {
            return try {
                block()
            } catch (e: RuntimeException) {
                logFailure(e)
                fallback
            }
        }
        val task = FutureTask(Callable { block() })
        handler.post(task)
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            task.cancel(false)
            logFailure(e)
            fallback
        }
    }

    private fun runGuarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            logFailure(e)
        }
    }

    private fun logFailure(e: Exception) {
        AhLog.w(TAG, "overlay work failed: ${e.javaClass.simpleName}: ${e.message}")
    }
}
