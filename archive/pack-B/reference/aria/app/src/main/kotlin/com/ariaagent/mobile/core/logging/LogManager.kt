package com.ariaagent.mobile.core.logging

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * LogManager — single object that owns the logging subsystem lifecycle.
 *
 * Use it from MainApplication.onCreate:
 *   LogManager.install(this, debugBuild = BuildConfig.DEBUG)
 *
 * It:
 *   1. Picks the on-device log directory.
 *   2. Spins up the file writer and attaches it to AriaLog.
 *   3. Installs the JVM crash handler.
 *   4. Installs the native crash handler (signal handlers in libllama-jni).
 *   5. Starts the ANR watchdog.
 *   6. Optionally installs StrictMode (debug only).
 *
 * Log layout on device (per-user app dir, no root or special perms needed):
 *   getExternalFilesDir(null)/logs/
 *     app.log            — current app log, rotated to app.log.1 .. app.log.5
 *     crashes/           — JVM uncaught exceptions
 *     anr/               — ANR snapshots
 *     native_crashes/    — native (SIGSEGV / SIGABRT) crash reports
 *     logcat/            — `logcat -d` snapshots
 *     build/             — symlinks to host build logs (filled by host scripts)
 *
 * The whole tree can be pulled in one shot:
 *   adb pull /sdcard/Android/data/com.ariaagent.mobile/files/logs ./aria-logs
 */
object LogManager {

    @Volatile private var installed = false

    lateinit var logDir: File
        private set
    lateinit var fileWriter: FileLogWriter
        private set
    lateinit var logcat: LogcatCollector
        private set
    lateinit var logcatStream: LogcatStreamer
        private set
    lateinit var anrWatchdog: AnrWatchdog
        private set

    fun install(context: Context, debugBuild: Boolean) {
        if (installed) return
        installed = true

        // External-files dir is per-app, world-readable to ADB without root,
        // and survives uninstall via the user's Files app if they need to share
        // a report after the fact. Falls back to internal storage if external
        // is unavailable (rare — emulators with no SD card slot).
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        logDir = File(base, "logs").apply { mkdirs() }

        fileWriter = FileLogWriter(logDir)
        AriaLog.attachFileSink(fileWriter)

        AriaLog.i(TAG, "===== ARIA log subsystem online =====")
        AriaLog.i(TAG, "logDir=${logDir.absolutePath}")

        CrashHandler.install(context, logDir)
        NativeCrashHandler.install(File(logDir, "native_crashes"))

        anrWatchdog = AnrWatchdog(logDir)
        anrWatchdog.start()

        logcat = LogcatCollector(logDir)
        // Initial snapshot — captures whatever was in the ring buffer before
        // we started writing app.log, so a "view logs" UI can show context.
        logcat.snapshot(tagFilter = "ARIA:V *:W")

        // Continuous tail of `logcat -b main,system,crash,events` to disk.
        // Captures EVERY line our process logs (and, on debug builds with
        // READ_LOGS granted via ADB, every line system-wide). Files rotate
        // at 8 MB × 5 = 40 MB total cap, so this never grows unboundedly.
        logcatStream = LogcatStreamer(logDir)
        logcatStream.start()

        if (debugBuild) StrictModeInstaller.install()
    }

    /** Bundle every on-device log into one zip for sharing. Returns the file. */
    fun bundleAll(context: Context): File {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(context.cacheDir, "aria-logs-$ts.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            addTree(zip, logDir, prefix = "logs/")
        }
        AriaLog.i(TAG, "bundled logs → ${out.absolutePath} (${out.length()} bytes)")
        return out
    }

    /**
     * Build a share intent for the bundled logs. The activity that calls this
     * must hold a FileProvider authority that matches the manifest entry —
     * see docs/08_LOGGING.md for the manifest snippet.
     */
    fun shareIntent(context: Context, fileProviderAuthority: String): Intent {
        val zip = bundleAll(context)
        val uri: Uri = FileProvider.getUriForFile(context, fileProviderAuthority, zip)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ARIA logs ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Best-effort flush before a planned shutdown. */
    fun flushAndShutdown() {
        if (!installed) return
        AriaLog.i(TAG, "===== ARIA log subsystem shutting down =====")
        try { logcatStream.stop() } catch (_: Exception) {}
        anrWatchdog.stop()
        fileWriter.shutdown()
        AriaLog.detachFileSink()
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun addTree(zip: ZipOutputStream, root: File, prefix: String) {
        if (!root.exists()) return
        root.walkTopDown().forEach { f ->
            if (f.isFile) {
                val rel = prefix + f.relativeTo(root).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private const val TAG = "LogManager"
}
