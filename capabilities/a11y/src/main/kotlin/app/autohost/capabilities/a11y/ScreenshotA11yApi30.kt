package app.autohost.capabilities.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The API 30+ accessibility screenshot path (`takeScreenshot`) — the first link of the screenshot chain
 * (a11y ≥30 → MediaProjection → T3 `screencap`, plan Phase 3). Typed failures come back as
 * [ScreenshotA11yApi30.Outcome.Failed] with the framework error code preserved; secure screens (FLAG_SECURE)
 * map to `SCREENSHOT_BLOCKED` upstream.
 *
 * Runs the callback through a latch so the (blocking) broker worker can await it; the service's main thread
 * delivers the result.
 */
object ScreenshotA11yApi30 {

    sealed class Outcome {
        data class Ok(val bitmap: Bitmap) : Outcome()

        /** [errorCode] is the framework constant name; never silently retried by callers. */
        data class Failed(val errorCode: String, val detail: String) : Outcome()
    }

    const val ERROR_SECURE_SCREEN = "ERROR_TAKE_SCREENSHOT_SECURE_SCREEN"
    const val ERROR_INTERNAL = "ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR"
    const val ERROR_NO_ACCESS = "ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS"
    const val ERROR_INVALID_DISPLAY = "ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY"

    fun errorCodeName(code: Int): String = when (code) {
        1 -> ERROR_INTERNAL
        2 -> ERROR_NO_ACCESS
        3 -> ERROR_INVALID_DISPLAY
        4 -> ERROR_SECURE_SCREEN
        else -> "ERROR_TAKE_SCREENSHOT_UNKNOWN_$code"
    }

    /** True when the error means "the screen refuses to be captured" (not a transient failure). */
    fun isBlockedError(errorCodeName: String): Boolean =
        errorCodeName == ERROR_SECURE_SCREEN || errorCodeName == ERROR_NO_ACCESS

    @RequiresApi(30)
    fun take(service: AccessibilityService, timeoutMs: Long = 5_000): Outcome {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Outcome.Failed(ERROR_INTERNAL, "take() must not run on the main thread")
        }
        val latch = CountDownLatch(1)
        var outcome: Outcome? = null
        val executor = Executor { r -> Handler(Looper.getMainLooper()).post(r) }
        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
            ) { screenshot ->
                val bitmap = screenshot.bitmap
                outcome = if (bitmap != null && !bitmap.isRecycled) {
                    Outcome.Ok(bitmap)
                } else {
                    Outcome.Failed(ERROR_INTERNAL, "framework returned an empty screenshot")
                }
                latch.countDown()
            }
        } catch (e: RuntimeException) {
            return Outcome.Failed(ERROR_INTERNAL, "takeScreenshot threw: ${e.message}")
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return Outcome.Failed(ERROR_INTERNAL, "screenshot callback never arrived within ${timeoutMs}ms")
        }
        return outcome ?: Outcome.Failed(ERROR_INTERNAL, "screenshot outcome missing")
    }
}
