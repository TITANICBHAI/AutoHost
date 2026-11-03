package com.ariaagent.mobile.core.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ariaagent.mobile.core.ai.ModelManager
import com.ariaagent.mobile.core.rl.LoraTrainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * ConfigStore — DataStore<Preferences>-backed agent configuration.
 *
 * Replaces SharedPreferences for agent config in Phase 10.
 * SharedPreferences is synchronous and can block the main thread on first read;
 * DataStore is fully async (Flow-based) and crash-safe.
 *
 * Usage patterns:
 *   — Compose ViewModel: `ConfigStore.flow(context).collectAsState()` — reactive, zero-cost
 *   — AgentViewModel (blocking read): `ConfigStore.getBlocking(context)` — safe from coroutine scope
 *   — AgentLoop: reads model path from `ModelManager.modelPath()` directly (no config dependency)
 *
 * The `AriaConfig` data class is the canonical in-memory config type for the pure Kotlin build.
 *
 * Fully on-device: no network endpoints, no cloud config. All values stored in
 * Android DataStore within the app's private internal storage.
 *
 * Phase: 10 (JS Thinning) — config as async Flow rather than sync SharedPreferences read.
 */

private val Context.ariaDataStore: DataStore<Preferences> by preferencesDataStore(name = "aria_config_v2")

data class AriaConfig(
    val modelPath: String       = "",
    val quantization: String    = "Q4_K_M",
    val contextWindow: Int      = 2048,
    val maxTokensPerTurn: Int   = 512,
    val temperatureX100: Int    = 70,
    val nGpuLayers: Int         = 32,
    /** Which GPU backend to use for inference. "vulkan" | "opencl" | "cpu".
     *  Both are compiled into the same .so and selected at load time.
     *  OpenCL is the recommended backend for Mali-G72 MP3 — more stable than Vulkan
     *  on Samsung Exynos stock kernels; OpenCL kernels compile on-device at first run,
     *  ~8–15 tok/s vs Vulkan's 15–30 tok/s (but Vulkan can be unstable / crash on some builds). */
    val gpuBackend: String      = "opencl",
    /** Enable Flash Attention (LLAMA_FLASH_ATTN_TYPE_AUTO).
     *  Reduces KV-cache memory bandwidth. AUTO mode: llama.cpp falls back silently if
     *  the GPU driver doesn't support it. Default off — safe baseline. */
    val flashAttn: Boolean      = false,
    /** Quantize KV cache to Q8_0 (halves KV memory, ~1% quality loss).
     *  At ctx 2048 F16 KV = ~256 MB; Q8_0 cuts to ~128 MB — meaningful on 6 GB M31.
     *  Context-creation time setting — requires model reload. */
    val kvCacheQuantization: Boolean = false,
    /** GPU micro-batch size (n_ubatch) for OpenCL / Vulkan kernel dispatch.
     *  Larger batches fill the GPU pipeline better; smaller batches reduce VRAM pressure.
     *  Mali-G72 MP3 sweet spot: 512.  Tune lower (256/128) if you see GPU OOMs. */
    val gpuUbatch: Int          = 512,
    /** Memory mapping strategy for model weight loading.
     *  "auto" — auto-selects: ≤ 2 GB → heap (immune to eviction), > 2 GB → mmap + mlock.
     *  "heap" — always load into anonymous heap memory (safest; 5 s cold-start penalty).
     *  "mmap" — always mmap + mlock attempt (fastest cold-start; mlock may fail silently). */
    val memoryMapping: String   = "auto",
    val loraAdapterPath: String = "",
    val rlEnabled: Boolean      = true,
    val learningRate: Double    = 1e-4,
)

object ConfigStore {

    // ─── Preference keys ──────────────────────────────────────────────────────
    private val KEY_MODEL_PATH   = stringPreferencesKey("modelPath")
    private val KEY_QUANTIZATION = stringPreferencesKey("quantization")
    private val KEY_CTX_WINDOW   = intPreferencesKey("contextWindow")
    private val KEY_MAX_TOKENS   = intPreferencesKey("maxTokensPerTurn")
    private val KEY_TEMP_X100    = intPreferencesKey("temperatureX100")
    private val KEY_N_GPU_LAYERS = intPreferencesKey("nGpuLayers")
    private val KEY_GPU_BACKEND  = stringPreferencesKey("gpuBackend")
    private val KEY_LORA_PATH    = stringPreferencesKey("loraAdapterPath")
    private val KEY_RL_ENABLED   = booleanPreferencesKey("rlEnabled")
    private val KEY_LEARNING_RATE = doublePreferencesKey("learningRate")
    private val KEY_FLASH_ATTN   = booleanPreferencesKey("flashAttn")
    private val KEY_KV_QUANT     = booleanPreferencesKey("kvCacheQuantization")
    private val KEY_GPU_UBATCH   = intPreferencesKey("gpuUbatch")
    private val KEY_MEM_MAPPING  = stringPreferencesKey("memoryMapping")

    // ─── Read — reactive Flow ─────────────────────────────────────────────────

    /** Reactive config Flow — use with `collectAsState()` in Compose. */
    fun flow(context: Context): Flow<AriaConfig> =
        context.ariaDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> fromPrefs(context, prefs) }

    /** Blocking read for bridge/coroutine-scope callers. Must NOT be called from the main thread. */
    fun getBlocking(context: Context): AriaConfig {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "ConfigStore.getBlocking() must not be called on the main thread — use flow() + collectAsState() instead"
        }
        return runBlocking(kotlinx.coroutines.Dispatchers.IO) { flow(context).first() }
    }

    // ─── Write — suspend ─────────────────────────────────────────────────────

    suspend fun save(context: Context, config: AriaConfig) {
        context.ariaDataStore.edit { prefs ->
            prefs[KEY_MODEL_PATH]    = config.modelPath
            prefs[KEY_QUANTIZATION]  = config.quantization
            prefs[KEY_CTX_WINDOW]    = config.contextWindow
            prefs[KEY_MAX_TOKENS]    = config.maxTokensPerTurn
            prefs[KEY_TEMP_X100]     = config.temperatureX100
            prefs[KEY_N_GPU_LAYERS]  = config.nGpuLayers
            prefs[KEY_GPU_BACKEND]   = config.gpuBackend
            prefs[KEY_LORA_PATH]     = config.loraAdapterPath
            prefs[KEY_RL_ENABLED]    = config.rlEnabled
            prefs[KEY_LEARNING_RATE] = config.learningRate
            prefs[KEY_FLASH_ATTN]    = config.flashAttn
            prefs[KEY_KV_QUANT]      = config.kvCacheQuantization
            prefs[KEY_GPU_UBATCH]    = config.gpuUbatch
            prefs[KEY_MEM_MAPPING]   = config.memoryMapping
        }
    }

    /** Migrate existing SharedPreferences values into DataStore on first run. */
    suspend fun migrateFromSharedPrefs(context: Context) {
        val legacy = context.getSharedPreferences("aria_config", Context.MODE_PRIVATE)
        if (!legacy.contains("modelPath")) return  // nothing to migrate
        val existing = flow(context).first()
        // Only migrate if DataStore is still at default values
        if (existing.modelPath.isNotEmpty()) return
        save(context, AriaConfig(
            modelPath        = legacy.getString("modelPath", ModelManager.modelPath(context).absolutePath) ?: "",
            quantization     = legacy.getString("quantization", "Q4_K_M") ?: "Q4_K_M",
            contextWindow    = legacy.getInt("contextWindow", 2048),
            maxTokensPerTurn = legacy.getInt("maxTokensPerTurn", 512),
            temperatureX100  = legacy.getInt("temperatureX100", 70),
            nGpuLayers       = legacy.getInt("nGpuLayers", 32),
            gpuBackend       = legacy.getString("gpuBackend", "opencl") ?: "opencl",
            gpuUbatch        = legacy.getInt("gpuUbatch", 512),
            memoryMapping    = legacy.getString("memoryMapping", "auto") ?: "auto",
            loraAdapterPath  = legacy.getString("loraAdapterPath", LoraTrainer.latestAdapterPath(context) ?: "") ?: "",
            rlEnabled        = legacy.getBoolean("rlEnabled", true),
            learningRate     = legacy.getFloat("learningRate", 1e-4.toFloat()).toDouble(),
        ))
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun fromPrefs(context: Context, prefs: Preferences) = AriaConfig(
        modelPath        = prefs[KEY_MODEL_PATH]    ?: ModelManager.modelPath(context).absolutePath,
        quantization     = prefs[KEY_QUANTIZATION]  ?: "Q4_K_M",
        contextWindow    = prefs[KEY_CTX_WINDOW]    ?: 2048,
        maxTokensPerTurn = prefs[KEY_MAX_TOKENS]    ?: 512,
        temperatureX100  = prefs[KEY_TEMP_X100]     ?: 70,
        nGpuLayers       = prefs[KEY_N_GPU_LAYERS]  ?: 32,
        gpuBackend       = prefs[KEY_GPU_BACKEND]   ?: "vulkan",
        // Prefer the adapter trained for the currently saved model.
        // Falls back to the globally latest adapter (highest version across all model dirs)
        // only when no model path is saved yet (e.g. very first launch).
        loraAdapterPath  = prefs[KEY_LORA_PATH] ?: run {
            val savedModelPath = prefs[KEY_MODEL_PATH] ?: ""
            if (savedModelPath.isNotEmpty()) {
                val mId = LoraTrainer.modelId(savedModelPath)
                LoraTrainer.latestAdapterPath(context, mId) ?: ""
            } else {
                LoraTrainer.latestAdapterPath(context) ?: ""
            }
        },
        rlEnabled            = prefs[KEY_RL_ENABLED]    ?: true,
        learningRate         = prefs[KEY_LEARNING_RATE] ?: 1e-4,
        flashAttn            = prefs[KEY_FLASH_ATTN]    ?: false,
        kvCacheQuantization  = prefs[KEY_KV_QUANT]      ?: false,
        gpuUbatch            = prefs[KEY_GPU_UBATCH]    ?: 512,
        memoryMapping        = prefs[KEY_MEM_MAPPING]   ?: "auto",
    )
}
