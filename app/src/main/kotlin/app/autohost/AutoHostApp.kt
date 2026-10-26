package app.autohost

import android.app.Application
import android.content.Context
import app.autohost.core.common.AhLog
import app.autohost.core.common.ApiGate

/**
 * Process entry. The main process builds the [AppGraph]; worker processes (`:w0`…`:w3`, `:ui`) do nothing
 * here — their `BaseTaskWorkerService` owns its own setup and the DB is never opened in workers
 * (docs/ANDROID_MANIFEST.md §2).
 */
class AutoHostApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        ApiGate.install(android.os.Build.VERSION.SDK_INT)
        AhLog.install { entry ->
            android.util.Log.println(levelPriority(entry.level), "autohost", "[${entry.tag}] ${entry.message}")
        }
        graph = AppGraph(this)
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
