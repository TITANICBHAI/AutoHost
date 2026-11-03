package com.ariaagent.mobile.core.logging

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogcatCollector — dump the device's logcat ring buffer to a file on demand.
 *
 * Why:
 *   When something crashes on a real device that is NOT plugged into Studio,
 *   the logcat ring buffer keeps a few minutes of context after the event.
 *   This class snapshots that context to a file so it can be pulled later via
 *   `adb pull`, the in-app share sheet, or the Files app.
 *
 * What works without root / without USB:
 *   Each Android app process can read the lines it itself logged (uid filter
 *   is implicit via `--uid` on Android 7+). On older devices the same call
 *   may return only a subset; that's fine — partial > nothing.
 *
 * Typical usage:
 *   1. From the crash handler — capture the last few seconds before death.
 *   2. Periodically (e.g. once per minute when the agent loop is active).
 *   3. From a "Share logs" UI button.
 *
 * Note: this exec-spawns `logcat -d`. It does NOT keep a long-running process
 *       — that would compete with `adb logcat` and is fragile under app
 *       backgrounding restrictions on Android 12+.
 */
class LogcatCollector(
    private val logDir: File,
) {
    private val outDir = File(logDir, "logcat").apply { mkdirs() }
    private val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * Snapshot the current logcat ring buffer to a new file. Returns the file,
     * or null if the snapshot failed (e.g. SELinux denial on some OEM ROMs).
     *
     * @param tagFilter additional logcat filterspec, e.g. "ARIA:V *:E" to keep
     *        verbose ARIA lines plus errors from everything else.
     * @param maxLines  hard cap on the number of lines written (defends against
     *        runaway loops in noisy system services).
     */
    fun snapshot(
        tagFilter: String? = null,
        maxLines: Int = 20_000,
    ): File? {
        val outFile = File(outDir, "logcat-${ts.format(Date())}.txt")
        val cmd = mutableListOf("logcat", "-d", "-v", "threadtime")
        // -b all asks for main, system, crash, events, kernel buffers (whichever
        // are accessible to our uid).
        cmd += listOf("-b", "all")
        if (tagFilter != null) cmd += tagFilter.split(" ")
        return try {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            outFile.bufferedWriter().use { w ->
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    var n = 0
                    for (line in lines) {
                        w.write(line); w.newLine()
                        if (++n >= maxLines) {
                            w.write("... truncated at $maxLines lines"); w.newLine()
                            proc.destroy()
                            break
                        }
                    }
                }
            }
            proc.waitFor()
            AriaLog.i(TAG, "logcat snapshot → ${outFile.name} (${outFile.length()} bytes)")
            outFile
        } catch (e: Exception) {
            AriaLog.w(TAG, "logcat snapshot failed: ${e.message}")
            outFile.delete()
            null
        }
    }

    fun listSnapshots(): List<File> =
        outDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    private companion object { const val TAG = "LogcatCollector" }
}
