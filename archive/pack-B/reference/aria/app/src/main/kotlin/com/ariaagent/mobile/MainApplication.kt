package com.ariaagent.mobile

import android.app.Application
import com.ariaagent.mobile.core.logging.AriaLog
import com.ariaagent.mobile.core.logging.LogManager

/**
 * MainApplication — ARIA application entry point.
 *
 * React Native, Expo, and SoLoader have been fully removed.
 * The NDK llama.cpp JNI library (llama-jni) is loaded lazily by LlamaEngine
 * via System.loadLibrary("llama-jni") in its companion object init block.
 * No explicit load is needed here.
 *
 * All UI is handled by ComposeMainActivity (Jetpack Compose).
 * All native services (LLM, OCR, RL, screen capture) are started by
 * AgentViewModel through AgentForegroundService.
 *
 * Logging:
 *   LogManager.install is the FIRST thing we do in onCreate so that any
 *   subsequent crash (including in the rest of onCreate, in static initializers
 *   of components touched right after, or in the native llama-jni load) is
 *   captured. See core/logging/ and docs/08_LOGGING.md for the full picture.
 *
 * Migration phase: 8 — SoLoader / RN host fully removed.
 */
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Install logs / crash handlers FIRST. BuildConfig.DEBUG is referenced
        // via reflection so this file does not have a hard dep on build config
        // generation order — defaults to false if not present.
        LogManager.install(this, debugBuild = isDebugBuild())
        AriaLog.i(TAG, "MainApplication.onCreate")
    }

    override fun onTerminate() {
        // Note: onTerminate only fires on emulators. Real devices are killed
        // outright. Still useful for instrumented tests.
        LogManager.flushAndShutdown()
        super.onTerminate()
    }

    private fun isDebugBuild(): Boolean = try {
        val cls = Class.forName("$packageName.BuildConfig")
        cls.getField("DEBUG").getBoolean(null)
    } catch (_: Throwable) { false }

    private companion object { const val TAG = "MainApplication" }
}
