package com.ariaagent.mobile.core.logging

import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * AnrWatchdog — main-thread liveness monitor.
 *
 * What it catches:
 *   ANRs (Application Not Responding) — the main thread is blocked for longer
 *   than [thresholdMs]. This precedes Android's own ~5 s ANR dialog and gives
 *   us a stack snapshot of the main thread WHILE it is still stuck, which is
 *   what we actually want to debug. The system-level ANR trace lands in
 *   /data/anr/ which is unreadable on production devices.
 *
 * How it works:
 *   - A daemon thread posts a no-op runnable to the main looper every
 *     [pollMs] and bumps a counter when it runs.
 *   - If the counter does not advance for [thresholdMs], we capture the main
 *     thread's stack trace and write an `anr-*.txt` next to the crash files.
 *   - We do NOT kill the process. Android may or may not show its own dialog
 *     depending on whether the block clears in time; either way we already
 *     captured the stack.
 *
 * This is a best-effort tool: if the main thread is alive but the OS is
 * starved of CPU, the watchdog's own thread may also be starved and the report
 * will be late. That is acceptable — even a late stack is better than none.
 */
class AnrWatchdog(
    private val logDir: File,
    private val thresholdMs: Long = 5_000L,
    private val pollMs: Long = 1_000L,
) {

    private val anrDir = File(logDir, "anr").apply { mkdirs() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tick = AtomicLong(0)
    private val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "AriaLog-AnrWatchdog").apply {
            isDaemon = true
            start()
        }
        AriaLog.i(TAG, "started — threshold=${thresholdMs}ms poll=${pollMs}ms")
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        while (running) {
            val before = tick.get()
            mainHandler.post { tick.incrementAndGet() }

            try { Thread.sleep(thresholdMs) } catch (_: InterruptedException) { return }

            if (!running) return
            val after = tick.get()
            if (after == before) {
                // Main thread did not run our ping in `thresholdMs`. Capture.
                captureMainStack()
                // Wait for the main thread to recover before re-arming so we
                // don't spam the disk with one report per pollMs interval.
                while (running && tick.get() == before) {
                    try { Thread.sleep(pollMs) } catch (_: InterruptedException) { return }
                }
                if (running) AriaLog.w(TAG, "main thread recovered after ANR")
            }
        }
    }

    private fun captureMainStack() {
        try {
            val main = Looper.getMainLooper().thread
            val frames = main.stackTrace
            val sb = StringBuilder(2048)
            sb.append("===== ARIA ANR snapshot =====\n")
            sb.append("when:   ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}\n")
            sb.append("blocked: >= ${thresholdMs}ms\n")
            sb.append("thread: ${main.name} (state=${main.state})\n\n")
            sb.append("===== main thread stack =====\n")
            frames.forEach { sb.append("  at ").append(it).append('\n') }
            sb.append("\n===== all threads =====\n")
            Thread.getAllStackTraces().forEach { (th, fr) ->
                if (th == main) return@forEach
                sb.append("--- ${th.name} (state=${th.state}) ---\n")
                fr.forEach { sb.append("  at ").append(it).append('\n') }
            }
            val file = File(anrDir, "anr-${ts.format(Date())}.txt")
            file.writeText(sb.toString())
            AriaLog.e(TAG, "ANR captured → ${file.name}")
        } catch (e: Throwable) {
            AriaLog.e(TAG, "ANR capture failed", e)
        }
    }

    fun listAnrs(): List<File> =
        anrDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    private companion object { const val TAG = "AnrWatchdog" }
}
