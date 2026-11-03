package com.ariaagent.mobile.core.logging

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashHandler — JVM-side uncaught-exception sink.
 *
 * What it catches:
 *   Every Kotlin / Java exception that escapes a thread without being caught,
 *   including the main thread (which would otherwise produce only a logcat
 *   "FATAL EXCEPTION" line that is gone after a few minutes).
 *
 * What it produces:
 *   <logDir>/crashes/crash-YYYYMMDD-HHMMSS-<thread>.txt
 *   …with build fingerprint, thread, full stack trace (with caused-by chain).
 *
 * Behavior:
 *   1. Write the crash file (best-effort, swallows IO errors so we never
 *      double-fault).
 *   2. Mirror the same payload through [AriaLog] so it ends up in app.log too.
 *   3. Delegate to the previous default handler (typically the Android system
 *      handler that shows the "Process has stopped" dialog and kills the
 *      process). We do not eat the exception — we only persist it.
 *
 * Native (SIGSEGV / SIGABRT) crashes are handled by `aria_crash_handler.cpp`.
 * That handler complements this one — neither sees the other's crashes.
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    private val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Volatile private var installed = false
    private lateinit var crashDir: File
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context, baseLogDir: File) {
        if (installed) return
        installed = true
        crashDir = File(baseLogDir, "crashes").apply { mkdirs() }

        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val payload = buildPayload(context, thread, throwable)
                writeFile(payload, thread)
                AriaLog.wtf(TAG, "uncaught on ${thread.name}", throwable)
                AriaLog.detachFileSink() // flush before the process is killed
            } catch (_: Throwable) {
                // Never fail in the crash handler.
            } finally {
                // Hand back to the system handler so the process actually dies
                // — letting the user see the "stopped" dialog and the OS reset
                // any half-open native resources.
                previous?.uncaughtException(thread, throwable)
            }
        }
        AriaLog.i(TAG, "installed — crashes will be written to ${crashDir.absolutePath}")
    }

    fun listCrashes(): List<File> =
        if (::crashDir.isInitialized && crashDir.exists())
            crashDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        else emptyList()

    // ─── internals ──────────────────────────────────────────────────────────

    private fun buildPayload(ctx: Context, thread: Thread, t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("===== ARIA crash report =====")
        pw.println("when:        ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        pw.println("thread:      ${thread.name} (id=${thread.id})")
        pw.println("process:     pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        pw.println("package:     ${ctx.packageName}")
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pw.println("app version: ${pi.versionName} (code=${pi.longVersionCode})")
        } catch (_: Exception) {}
        pw.println("device:      ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        pw.println("android:     ${Build.VERSION.RELEASE} (sdk=${Build.VERSION.SDK_INT})")
        pw.println("abi:         ${Build.SUPPORTED_ABIS.joinToString(",")}")
        pw.println("fingerprint: ${Build.FINGERPRINT}")
        pw.println()
        pw.println("===== stack trace =====")
        t.printStackTrace(pw)
        pw.println()
        pw.println("===== all threads =====")
        Thread.getAllStackTraces().forEach { (th, frames) ->
            pw.println("--- ${th.name} (state=${th.state}) ---")
            frames.forEach { pw.println("  at $it") }
        }
        return sw.toString()
    }

    private fun writeFile(payload: String, thread: Thread) {
        val safeName = thread.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        val file = File(crashDir, "crash-${ts.format(Date())}-$safeName.txt")
        file.writeText(payload)
    }
}
