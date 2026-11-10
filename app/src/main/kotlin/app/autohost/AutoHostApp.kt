package app.autohost

import android.app.Application
import android.content.Context
import app.autohost.core.common.AhLog
import app.autohost.core.common.ApiGate
import app.autohost.diag.CrashLog

/**
 * Process entry. Android runs [onCreate] in EVERY process of the app (`main`, `:w0`…`:w3`, `:ui`). Only the main
 * process builds the [AppGraph]; worker processes own their setup in `BaseTaskWorkerService` and must never open the
 * DB, start controllers or run the crash reconciler (docs/ANDROID_MANIFEST.md §2).
 *
 * If the graph cannot be built, the exception is kept in [startupFailure] and recorded by [CrashLog];
 * `MainActivity` then shows it instead of the process dying before any screen exists.
 */
class AutoHostApp : Application() {

    lateinit var graph: AppGraph
        private set

    /** Non-null when building the [AppGraph] threw. Always null in worker processes. */
    @Volatile
    var startupFailure: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        ApiGate.install(android.os.Build.VERSION.SDK_INT)
        AhLog.install { entry ->
            android.util.Log.println(levelPriority(entry.level), "autohost", "[${entry.tag}] ${entry.message}")
        }
        if (!isMainProcess()) return
        enableStrictModeInDebugBuilds()
        try {
            graph = AppGraph(this)
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Throwable) {
            startupFailure = e
            AhLog.e("app", "AppGraph construction failed: ${e.javaClass.name}: ${e.message}")
            CrashLog.record("AppGraph construction failed", e)
        }
    }

    /**
     * Called by `AppGraph` as the first statement of its `init` block, so that code running while the graph is still
     * being constructed (background coroutines started by controllers) can already read [graph].
     */
    internal fun publishGraph(partial: AppGraph) {
        graph = partial
    }

    /**
     * S04-6: in debug builds only, report main-thread disk/network work and leaked closables to logcat so the phone session
     * can show them. Log only: never `penaltyDeath` (a violation must not crash a build someone is testing), and nothing
     * goes into the crash log (that would show the "recorded a problem" screen on the next launch).
     */
    private fun enableStrictModeInDebugBuilds() {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
        )
        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build(),
        )
    }

    private fun isMainProcess(): Boolean {
        val name = Application.getProcessName()
        return name == null || name == packageName
    }

    companion object {
        fun of(context: Context): AutoHostApp = context.applicationContext as AutoHostApp

        private fun levelPriority(level: app.autohost.core.common.LogLevel): Int = when (level) {
            app.autohost.core.common.LogLevel.DEBUG -> android.util.Log.DEBUG
            app.autohost.core.common.LogLevel.INFO -> android.util.Log.INFO
            app.autohost.core.common.LogLevel.WARN -> android.util.Log.WARN
            app.autohost.core.common.LogLevel.ERROR -> android.util.Log.ERROR
        }
    }
}
