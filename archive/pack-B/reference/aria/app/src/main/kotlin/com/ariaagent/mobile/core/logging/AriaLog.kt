package com.ariaagent.mobile.core.logging

import android.util.Log

/**
 * AriaLog — unified entry point for every log line in the app.
 *
 * Why this exists:
 *   - Every direct call to `android.util.Log.x(...)` goes ONLY to logcat.
 *     Logcat is a ring buffer; on a busy device, lines older than ~2 minutes
 *     are gone before the user can `adb logcat -d` them.
 *   - We need persistent, post-mortem readable logs for crashes that happen
 *     when the device is not connected to Android Studio.
 *
 * What this does:
 *   1. Forwards every call to `android.util.Log` (so logcat still works exactly
 *      as before — no regression for `adb logcat`).
 *   2. Mirrors every line into a rolling on-device log file via [FileLogWriter]
 *      (see `getExternalFilesDir(null)/logs/app-*.log`).
 *   3. Tags every line with an "ARIA/" prefix so grep / logcat filtering is
 *      trivial:  `adb logcat | grep ARIA/`.
 *
 * Levels match `android.util.Log`:
 *   V (verbose) - chatty trace, off in release
 *   D (debug)   - dev-only state changes
 *   I (info)    - lifecycle, model load, agent loop ticks
 *   W (warn)    - recoverable problem
 *   E (error)   - operation failed, may be retried
 *   WTF         - invariant violated, app is in an undefined state
 *
 * Usage:
 *   AriaLog.i("LlamaEngine", "model loaded in ${ms}ms")
 *   AriaLog.e("LlamaEngine", "load failed", throwable)
 *
 * Thread-safe. Backed by a single-writer background thread (see FileLogWriter).
 */
object AriaLog {

    private const val TAG_PREFIX = "ARIA/"

    @Volatile
    private var fileSink: FileLogWriter? = null

    /**
     * Install the file sink. Called once from MainApplication.onCreate after
     * LogManager has resolved the log directory. Until this is called, log
     * lines are sent to logcat only (no file mirror).
     */
    fun attachFileSink(sink: FileLogWriter) {
        fileSink = sink
        // Mark the sink boundary so post-mortem readers can spot a fresh process.
        i("AriaLog", "===== file sink attached pid=${android.os.Process.myPid()} =====")
    }

    fun detachFileSink() {
        fileSink?.flushBlocking()
        fileSink = null
    }

    fun v(tag: String, msg: String) = log(Log.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = log(Log.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Log.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Log.ERROR, tag, msg, t)
    fun wtf(tag: String, msg: String, t: Throwable? = null) = log(Log.ASSERT, tag, msg, t)

    private fun log(level: Int, tag: String, msg: String, t: Throwable?) {
        val fullTag = TAG_PREFIX + tag
        // 1. Logcat — preserve standard Android Studio behavior.
        when (level) {
            Log.VERBOSE -> if (t == null) Log.v(fullTag, msg) else Log.v(fullTag, msg, t)
            Log.DEBUG   -> if (t == null) Log.d(fullTag, msg) else Log.d(fullTag, msg, t)
            Log.INFO    -> if (t == null) Log.i(fullTag, msg) else Log.i(fullTag, msg, t)
            Log.WARN    -> if (t == null) Log.w(fullTag, msg) else Log.w(fullTag, msg, t)
            Log.ERROR   -> if (t == null) Log.e(fullTag, msg) else Log.e(fullTag, msg, t)
            Log.ASSERT  -> if (t == null) Log.wtf(fullTag, msg) else Log.wtf(fullTag, msg, t)
        }
        // 2. File mirror — survives reboot and disconnection from Studio.
        fileSink?.append(level, fullTag, msg, t)
    }
}
