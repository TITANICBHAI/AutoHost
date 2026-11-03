package com.ariaagent.mobile.core.logging

import java.io.File

/**
 * NativeCrashHandler — Kotlin facade over the C++ signal-handler installed in
 * libllama-jni's `aria_crash_handler.cpp`.
 *
 * What it catches:
 *   SIGSEGV / SIGBUS / SIGFPE / SIGILL / SIGABRT / SIGPIPE in any native
 *   thread (llama.cpp inference, OpenCL, Vulkan, the JNI bridge itself).
 *   Without this, a native crash produces a single "Fatal signal 11" tombstone
 *   in /data/tombstones/ — which is unreadable on production devices and
 *   missing the JNI / Java context that would explain who called us.
 *
 * What it produces:
 *   <nativeCrashDir>/native-crash-<timestamp>.txt
 *   …with signal name, signal code, fault address, register dump, native
 *   backtrace (mangled symbols — pipe through `ndk-stack` or `addr2line` to
 *   resolve), and the thread name.
 *
 * Lifecycle:
 *   The native side stores `nativeCrashDir` once at install time and writes
 *   into it without further JVM calls (the JVM may be in an unsafe state by
 *   the time the signal fires). The directory must exist and be writable
 *   before [install] is called.
 *
 * If libllama-jni has not been loaded (e.g. unit tests on the JVM), [install]
 * silently falls back to a no-op so it can still be called unconditionally
 * from MainApplication.
 */
object NativeCrashHandler {

    @Volatile private var installed = false

    fun install(nativeCrashDir: File) {
        if (installed) return
        nativeCrashDir.mkdirs()
        try {
            // libllama-jni statically links aria_crash_handler.cpp.
            // It is loaded lazily by LlamaEngine, so force a load here so the
            // signal handler is installed before any inference can crash.
            System.loadLibrary("llama-jni")
            nativeInstall(nativeCrashDir.absolutePath)
            installed = true
            AriaLog.i(TAG, "installed → ${nativeCrashDir.absolutePath}")
        } catch (e: UnsatisfiedLinkError) {
            // Either the .so is missing (unit tests) or the JNI symbol is not
            // present yet (older build of libllama-jni without the handler).
            // Surface as a warning, not a crash — the JVM handler still works.
            AriaLog.w(TAG, "native handler unavailable: ${e.message}")
        } catch (e: Throwable) {
            AriaLog.w(TAG, "native handler install failed", e)
        }
    }

    fun listCrashes(dir: File): List<File> =
        dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    @JvmStatic private external fun nativeInstall(crashDirAbsolutePath: String)

    private const val TAG = "NativeCrashHandler"
}
