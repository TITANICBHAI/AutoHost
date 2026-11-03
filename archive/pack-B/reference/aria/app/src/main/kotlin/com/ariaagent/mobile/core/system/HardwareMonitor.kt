package com.ariaagent.mobile.core.system

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * HardwareMonitor — real-time CPU, RAM and GPU utilisation sampler.
 *
 * CPU  : /proc/stat two-sample delta (standard Linux; always readable)
 * RAM  : ActivityManager.MemoryInfo (Android API; always available)
 * GPU  : Mali-G72 MP3 sysfs paths — Samsung stock kernel exposes
 *        /sys/kernel/gpu/gpu_busy (0-100).  We try several known paths;
 *        if none are readable (root required on some OEM kernels) the GPU
 *        field is -1 and the UI shows "n/a" rather than a wrong number.
 *
 * All reads happen on Dispatchers.IO — never blocks the main thread.
 */
object HardwareMonitor {

    private const val TAG = "HardwareMonitor"

    /**
     * Snapshot of one hardware poll cycle.
     *
     * @param cpuPercent  0-100  (estimated user+sys CPU %)
     * @param ramUsedMb   RAM currently used in MB
     * @param ramTotalMb  Total device RAM in MB
     * @param gpuPercent  0-100 if readable from sysfs, -1 otherwise
     */
    data class HardwareStats(
        val cpuPercent:  Int = 0,
        val ramUsedMb:   Int = 0,
        val ramTotalMb:  Int = 0,
        val gpuPercent:  Int = -1,
    ) {
        val ramPercent: Int
            get() = if (ramTotalMb > 0) (ramUsedMb * 100 / ramTotalMb).coerceIn(0, 100) else 0
    }

    // Known sysfs paths for Mali-G72 GPU utilisation on Samsung Exynos devices.
    // Each file contains a plain integer 0-100.
    private val GPU_SYSFS_CANDIDATES = listOf(
        "/sys/kernel/gpu/gpu_busy",              // Samsung One UI / Android 10 stock
        "/sys/kernel/gpu/gpu_load",              // some Samsung variants
        "/sys/class/misc/mali0/device/utilization",  // mainline Mali driver
        "/sys/devices/platform/11400000.mali/utilization",   // Exynos 9611 address
        "/sys/devices/platform/13400000.mali/utilization",   // alternate Exynos addr
        "/sys/devices/platform/mali/utilization",            // generic fallback
    )

    // Cached path once found so we don't probe every cycle.
    @Volatile private var gpuSysfsPath: String? = null
    @Volatile private var gpuPathProbed = false

    // ── CPU helpers ──────────────────────────────────────────────────────────

    /**
     * Read the first (aggregate) line of /proc/stat and return
     * (idleJiffies, totalJiffies).  Returns null on any read failure.
     */
    private fun readProcStat(): Pair<Long, Long>? = runCatching {
        val line = File("/proc/stat").bufferedReader().readLine() ?: return null
        // Format: "cpu  user nice system idle iowait irq softirq steal guest guest_nice"
        val tokens = line.trim().split("\\s+".toRegex())
        if (tokens.size < 5 || tokens[0] != "cpu") return null
        val values = tokens.drop(1).map { it.toLong() }
        val total  = values.sum()
        val idle   = values[3] + (values.getOrElse(4) { 0L })   // idle + iowait
        total to idle
    }.getOrNull()

    private fun cpuPercent(s1: Pair<Long, Long>, s2: Pair<Long, Long>): Int {
        val totalDelta = s2.first - s1.first
        val idleDelta  = s2.second - s1.second
        if (totalDelta <= 0) return 0
        return ((totalDelta - idleDelta) * 100L / totalDelta).toInt().coerceIn(0, 100)
    }

    // ── RAM helpers ──────────────────────────────────────────────────────────

    private fun ramStats(context: Context): Pair<Int, Int> {
        val am   = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = (info.totalMem  / 1_048_576L).toInt()
        val availMb = (info.availMem  / 1_048_576L).toInt()
        val usedMb  = (totalMb - availMb).coerceAtLeast(0)
        return usedMb to totalMb
    }

    // ── GPU helpers ──────────────────────────────────────────────────────────

    private fun probeGpuPath(): String? {
        if (gpuPathProbed) return gpuSysfsPath
        gpuPathProbed = true
        for (path in GPU_SYSFS_CANDIDATES) {
            runCatching {
                val v = File(path).readText().trim().toIntOrNull()
                if (v != null && v in 0..100) {
                    Log.i(TAG, "GPU sysfs path found: $path (initial=$v)")
                    gpuSysfsPath = path
                    return path
                }
            }
        }
        Log.w(TAG, "No readable GPU sysfs path found on this device; GPU gauge will show n/a")
        return null
    }

    private fun gpuPercent(): Int = runCatching {
        val path = probeGpuPath() ?: return -1
        File(path).readText().trim().toIntOrNull()?.coerceIn(0, 100) ?: -1
    }.getOrDefault(-1)

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Emits [HardwareStats] every [intervalMs] milliseconds.
     * CPU is measured as a two-sample delta over ~500 ms (first half of interval)
     * then the caller receives the result at the full [intervalMs] cadence.
     *
     * Collect on any coroutine scope; the flow runs on Dispatchers.IO internally.
     */
    fun statsFlow(context: Context, intervalMs: Long = 1500L): Flow<HardwareStats> = flow {
        while (true) {
            val stat1 = readProcStat()
            delay(500L)                       // let CPU counter advance
            val stat2 = readProcStat()
            val cpu   = if (stat1 != null && stat2 != null) cpuPercent(stat1, stat2) else 0
            val (usedMb, totalMb) = ramStats(context)
            val gpu  = gpuPercent()
            emit(HardwareStats(
                cpuPercent  = cpu,
                ramUsedMb   = usedMb,
                ramTotalMb  = totalMb,
                gpuPercent  = gpu,
            ))
            delay((intervalMs - 500L).coerceAtLeast(100L))
        }
    }.flowOn(Dispatchers.IO)
}
