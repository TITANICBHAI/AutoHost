package app.autohost.diag

import android.app.Application
import android.content.Context
import android.os.Build
import app.autohost.core.common.SystemClockImpl
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Last-resort crash recorder (crash-fix plan, step 1.1). Installed from `AutoHostApp.onCreate` in every process.
 *
 * - An uncaught exception on any thread is written to disk first and then handed to the previous handler, so Android
 *   still shows its crash dialog and logcat still prints "FATAL EXCEPTION".
 * - [record] stores failures the app survives on purpose (failed startup steps, uncaught coroutine failures).
 * - [pending] returns the newest report the user has not dismissed yet; `MainActivity` shows it on the next launch,
 *   so no adb and no file manager is needed to read it.
 *
 * Files (best effort; nothing in here may ever throw):
 * - `<filesDir>/crash/pending-crash.txt`: newest report only, deleted by [clearPending].
 * - `<externalFilesDir>/crash/last-crash.txt`: rolling history, newest first (readable with `adb pull`).
 */
object CrashLog {
    private const val DIR_NAME = "crash"
    private const val PENDING_FILE = "pending-crash.txt"
    private const val HISTORY_FILE = "last-crash.txt"
    private const val MAX_REPORT_CHARS = 30_000
    private const val MAX_HISTORY_CHARS = 60_000

    @Volatile
    private var appContext: Context? = null

    private val clock = SystemClockImpl()

    /** Safe to call more than once; only the first call installs the handler. */
    fun install(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record("uncaught exception on thread \"${thread.name}\"", error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** Stores one report. Never throws. */
    fun record(label: String, error: Throwable?) {
        try {
            val context = appContext ?: return
            val report = buildReport(context, label, error)
            writePending(context, report)
            appendHistory(context, report)
        } catch (e: Throwable) {
            // A crash recorder that crashes helps nobody.
        }
    }

    /** The newest report the user has not dismissed, or null. Tiny file, fine on the main thread. */
    fun pending(context: Context): String? =
        try {
            val file = File(File(context.filesDir, DIR_NAME), PENDING_FILE)
            if (file.isFile) file.readText().ifBlank { null } else null
        } catch (e: Throwable) {
            null
        }

    /** Deletes the pending report (the history file is kept). */
    fun clearPending(context: Context) {
        try {
            File(File(context.filesDir, DIR_NAME), PENDING_FILE).delete()
        } catch (e: Throwable) {
            // best effort
        }
    }

    /** Stack trace text including "Caused by" chains. */
    fun describe(error: Throwable?): String {
        if (error == null) return "(no exception object)"
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    private fun buildReport(context: Context, label: String, error: Throwable?): String {
        val text = StringBuilder()
        text.append("=== ").append(label).append(" ===\n")
        text.append("time: ").append(java.time.Instant.ofEpochMilli(clock.nowMs())).append('\n')
        text.append("process: ").append(processName(context)).append('\n')
        text.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        text.append(", Android ").append(Build.VERSION.RELEASE).append('\n')
        text.append(describe(error))
        return text.toString().take(MAX_REPORT_CHARS)
    }

    private fun processName(context: Context): String =
        try {
            Application.getProcessName() ?: context.packageName
        } catch (e: Throwable) {
            context.packageName
        }

    private fun writePending(context: Context, report: String) {
        val dir = File(context.filesDir, DIR_NAME)
        dir.mkdirs()
        File(dir, PENDING_FILE).writeText(report)
    }

    private fun appendHistory(context: Context, report: String) {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        dir.mkdirs()
        val file = File(dir, HISTORY_FILE)
        val previous = if (file.isFile) file.readText() else ""
        file.writeText((report + "\n" + previous).take(MAX_HISTORY_CHARS))
    }
}
