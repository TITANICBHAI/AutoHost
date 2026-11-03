package com.ariaagent.mobile.core.logging

import android.os.Build
import android.os.StrictMode

/**
 * StrictModeInstaller — turn on every cheap runtime invariant check in debug.
 *
 * Why:
 *   The agent loop, llama.cpp inference, screen capture, and accessibility
 *   service all touch resources that are easy to leak (cursors, sockets,
 *   files, registered receivers). StrictMode catches those leaks the *first*
 *   time they happen, with a stack trace pointing at the actual offender —
 *   instead of an oblique OOM hours later.
 *
 * Policy:
 *   - Thread policy   : detect disk reads/writes/network on the main thread.
 *   - VM policy       : detect leaked closeables, leaked SQLite cursors,
 *                       leaked URL connections, leaked registration objects,
 *                       and (on API 31+) unsafe intent launches.
 *   - Penalty         : penaltyLog — write to logcat (and via AriaLog into
 *                       app.log). Deliberately NOT penaltyDeath — we don't
 *                       want a leak triage tool to crash the whole agent.
 *
 * Call once from MainApplication.onCreate, AFTER AriaLog.attachFileSink.
 * Wrap in `if (BuildConfig.DEBUG)` at the call site so the release build is
 * untouched.
 */
object StrictModeInstaller {

    fun install() {
        val thread = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .penaltyLog()
            .build()
        StrictMode.setThreadPolicy(thread)

        val vmBuilder = StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .detectLeakedSqlLiteObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .detectFileUriExposure()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vmBuilder.detectUnsafeIntentLaunch()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vmBuilder.detectContentUriWithoutPermission()
        }
        StrictMode.setVmPolicy(vmBuilder.build())

        AriaLog.i(TAG, "installed (debug)")
    }

    private const val TAG = "StrictMode"
}
