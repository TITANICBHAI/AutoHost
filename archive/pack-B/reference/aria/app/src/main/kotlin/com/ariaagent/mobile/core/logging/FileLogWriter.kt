package com.ariaagent.mobile.core.logging

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FileLogWriter — single-writer background log file with rolling rotation.
 *
 * Design:
 *   - Caller threads enqueue formatted strings on a bounded queue (no blocking
 *     on disk I/O on the main / agent / inference threads).
 *   - A dedicated writer thread drains the queue and writes to disk.
 *   - When the active file passes [maxFileBytes], it is renamed with a `.1`
 *     suffix and a new file is opened. Up to [maxFiles] generations are kept;
 *     older files are deleted to bound disk usage.
 *
 * File layout:
 *   <logDir>/app.log         (current)
 *   <logDir>/app.log.1       (previous)
 *   <logDir>/app.log.2       (older)
 *   ...
 *
 * Format (one line per entry — multi-line throwables are indented):
 *   2026-04-30 10:42:11.234  I  ARIA/LlamaEngine  model loaded in 932ms
 *
 * Backpressure policy:
 *   If the queue is full (writer fell behind, e.g. SD card stall), new entries
 *   are dropped and a single "log queue overflow" line is emitted to logcat
 *   to make the data loss visible. We deliberately do NOT block the producer —
 *   logging must never deadlock the agent loop or the UI.
 */
class FileLogWriter(
    private val logDir: File,
    private val baseName: String = "app.log",
    private val maxFileBytes: Long = 2L * 1024 * 1024,   // 2 MB per file
    private val maxFiles: Int = 5,                        // 10 MB total cap
    private val queueCapacity: Int = 4096,
) {
    private val queue = LinkedBlockingQueue<String>(queueCapacity)
    private val running = AtomicBoolean(true)
    private val overflowReported = AtomicBoolean(false)
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val writerThread = Thread({ drainLoop() }, "AriaLog-FileWriter").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
        start()
    }

    init {
        if (!logDir.exists()) logDir.mkdirs()
    }

    /** Enqueue one log entry. Non-blocking. */
    fun append(level: Int, tag: String, msg: String, t: Throwable?) {
        if (!running.get()) return
        val line = format(level, tag, msg, t)
        if (!queue.offer(line)) {
            // Queue full — drop. Report once per overflow burst.
            if (overflowReported.compareAndSet(false, true)) {
                Log.w("ARIA/FileLogWriter", "log queue overflow — dropping entries")
            }
        } else {
            overflowReported.set(false)
        }
    }

    /** Block until the writer thread drains the current queue, then return. */
    fun flushBlocking(timeoutMs: Long = 2_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    /** Stop the writer thread. Drains pending entries first. */
    fun shutdown() {
        flushBlocking()
        running.set(false)
        writerThread.interrupt()
    }

    /** Path of the current (active) log file. */
    fun currentFile(): File = File(logDir, baseName)

    /** All log files (current + rotated), newest first. */
    fun listFiles(): List<File> {
        val files = mutableListOf<File>()
        files.add(currentFile())
        for (i in 1..maxFiles) {
            val f = File(logDir, "$baseName.$i")
            if (f.exists()) files.add(f)
        }
        return files.filter { it.exists() }
    }

    // ─── internals ──────────────────────────────────────────────────────────

    private fun drainLoop() {
        var writer: BufferedWriter? = null
        try {
            writer = openWriter()
            while (running.get() || queue.isNotEmpty()) {
                val line = try {
                    queue.poll(500, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { null } ?: continue
                try {
                    val w = writer ?: openWriter().also { writer = it }
                    w.write(line)
                    w.newLine()
                    w.flush()
                    if (currentFile().length() >= maxFileBytes) {
                        w.close()
                        rotate()
                        writer = openWriter()
                    }
                } catch (e: Exception) {
                    // Disk full / permission / SD card unmount — surface to logcat
                    // and keep draining (so the queue does not back up forever).
                    Log.e("ARIA/FileLogWriter", "write failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("ARIA/FileLogWriter", "writer thread crashed: ${e.message}", e)
        } finally {
            try { writer?.close() } catch (_: Exception) {}
        }
    }

    private fun openWriter(): BufferedWriter =
        BufferedWriter(FileWriter(currentFile(), /* append = */ true))

    private fun rotate() {
        // Drop the oldest, then shift everything up by one.
        val oldest = File(logDir, "$baseName.$maxFiles")
        if (oldest.exists()) oldest.delete()
        for (i in (maxFiles - 1) downTo 1) {
            val src = File(logDir, "$baseName.$i")
            if (src.exists()) src.renameTo(File(logDir, "$baseName.${i + 1}"))
        }
        currentFile().renameTo(File(logDir, "$baseName.1"))
    }

    private fun format(level: Int, tag: String, msg: String, t: Throwable?): String {
        val time = ts.format(Date())
        val lvl = when (level) {
            Log.VERBOSE -> "V"; Log.DEBUG -> "D"; Log.INFO -> "I"
            Log.WARN    -> "W"; Log.ERROR -> "E"; Log.ASSERT -> "A"
            else -> "?"
        }
        val sb = StringBuilder(64 + msg.length)
        sb.append(time).append("  ").append(lvl).append("  ")
            .append(tag).append("  ").append(msg)
        if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            // Indent throwable so post-mortem readers can fold it.
            sw.toString().lineSequence().forEach { line ->
                sb.append('\n').append("    ").append(line)
            }
        }
        return sb.toString()
    }
}
