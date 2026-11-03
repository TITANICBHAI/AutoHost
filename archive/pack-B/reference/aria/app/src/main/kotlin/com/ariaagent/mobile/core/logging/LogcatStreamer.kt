package com.ariaagent.mobile.core.logging

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LogcatStreamer — continuously tail the device logcat ring buffer and mirror
 * every line to a rolling file on disk.
 *
 * Why this exists in addition to [LogcatCollector]:
 *   - LogcatCollector takes a one-shot snapshot (`logcat -d`) which is fine for
 *     post-mortem dumps but loses everything that scrolled out of the ring
 *     buffer between snapshots. On a busy device that can be ~30 seconds.
 *   - LogcatStreamer runs `logcat` (no -d) as a long-lived child process and
 *     mirrors stdout into a rolling file so EVERY line the app and its peers
 *     emit is preserved. After a crash, the surviving file contains the last
 *     N MB of context, not just the last 30 s.
 *
 * What works without root / without USB:
 *   On Android 7+, an app process may read logcat lines from its own UID
 *   without any special permission. Lines from other UIDs (system_server,
 *   surfaceflinger, kernel) are usually filtered out by selinux unless the
 *   READ_LOGS permission is granted (debug builds via `adb shell pm grant`).
 *   We still get everything our own process logs, which is what 95% of
 *   debugging requires.
 *
 * Files on disk:
 *   logs/logcat-stream/current.log         — actively written
 *   logs/logcat-stream/current.log.1 .. .5 — rotated
 *
 * Lifecycle:
 *   start()   — fork `logcat` and the reader thread.
 *   stop()    — destroy the child and join the reader.
 *   isAlive() — true if the child process is still running.
 *
 * Thread-safety: start() / stop() are guarded by AtomicBoolean. The actual
 * I/O happens on a single dedicated thread, so no locks needed on the writer.
 */
class LogcatStreamer(
    logDir: File,
    private val maxFileBytes: Long = 8L * 1024 * 1024,   // 8 MB per file
    private val maxFiles: Int = 5,                        // keep 5 → 40 MB total
    private val format: String = "threadtime",
    private val buffers: List<String> = listOf("main", "system", "crash", "events"),
    private val tagFilter: String? = null,                // e.g. "ARIA:V *:W"
) {
    private val outDir = File(logDir, "logcat-stream").apply { mkdirs() }
    private val current = File(outDir, "current.log")

    private val running = AtomicBoolean(false)
    private var process: Process? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val cmd = buildCmd()
        thread = Thread({ runLoop(cmd) }, "ARIA-LogcatStreamer").apply {
            isDaemon = true
            start()
        }
        AriaLog.i(TAG, "started: ${cmd.joinToString(" ")}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { process?.destroy() } catch (_: Exception) {}
        try { thread?.join(2_000) } catch (_: Exception) {}
        process = null
        thread = null
        AriaLog.i(TAG, "stopped")
    }

    fun isAlive(): Boolean = running.get() && (process?.isAlive == true)

    fun currentFile(): File = current

    fun listFiles(): List<File> {
        val files = mutableListOf<File>()
        if (current.exists()) files += current
        for (i in 1..maxFiles) {
            val f = File(outDir, "current.log.$i")
            if (f.exists()) files += f
        }
        return files
    }

    // ─── internals ──────────────────────────────────────────────────────────

    private fun buildCmd(): List<String> {
        val cmd = mutableListOf("logcat", "-v", format)
        // -b multiple buffers via repeated -b flags
        for (b in buffers) cmd += listOf("-b", b)
        if (tagFilter != null) cmd += tagFilter.split(" ")
        return cmd
    }

    private fun runLoop(cmd: List<String>) {
        // Outer retry loop: if logcat dies (OOM kill, selinux denial after a
        // permission change, ROM-specific quirk), wait 5 s and respawn rather
        // than silently losing the stream.
        while (running.get()) {
            var writer: BufferedWriter? = null
            try {
                process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                writer = openWriter()
                BufferedReader(InputStreamReader(process!!.inputStream)).use { r ->
                    var line = r.readLine()
                    while (running.get() && line != null) {
                        writer!!.write(line); writer!!.newLine()
                        // Flush every line — at <50 KB/s this is fine and means
                        // a crash never loses the last few lines (the most
                        // valuable ones for debugging).
                        writer!!.flush()
                        if (current.length() >= maxFileBytes) {
                            try { writer!!.close() } catch (_: Exception) {}
                            rotate()
                            writer = openWriter()
                        }
                        line = r.readLine()
                    }
                }
            } catch (e: Exception) {
                AriaLog.w(TAG, "stream crashed: ${e.message}")
            } finally {
                try { writer?.close() } catch (_: Exception) {}
                try { process?.destroy() } catch (_: Exception) {}
                process = null
            }
            if (!running.get()) break
            // Backoff before retry so we don't spin the CPU on a permanent
            // failure (e.g. selinux denial that won't recover this boot).
            try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
        }
    }

    private fun openWriter(): BufferedWriter =
        BufferedWriter(FileWriter(current, /* append = */ true))

    private fun rotate() {
        val oldest = File(outDir, "current.log.$maxFiles")
        if (oldest.exists()) oldest.delete()
        for (i in (maxFiles - 1) downTo 1) {
            val src = File(outDir, "current.log.$i")
            if (src.exists()) src.renameTo(File(outDir, "current.log.${i + 1}"))
        }
        current.renameTo(File(outDir, "current.log.1"))
    }

    private companion object { const val TAG = "LogcatStreamer" }
}
