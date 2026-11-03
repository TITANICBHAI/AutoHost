package com.ariaagent.mobile.core.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LlamaEngine — JNI wrapper around llama.cpp for on-device inference.
 *
 * Phase 1 status: Full JNI declarations wired. CMakeLists.txt written.
 * To activate: add llama.cpp as a submodule in android/app/src/main/cpp/
 *
 *   cd android/app/src/main/cpp
 *   git submodule add https://github.com/ggerganov/llama.cpp llama.cpp
 *   git submodule update --init --recursive
 *   Then run: ./gradlew assembleDebug (from android/)
 *
 * Hardware target: Samsung Galaxy M31 (Exynos 9611)
 *   - Mali-G72 MP3 → Vulkan/OpenCL → n_gpu_layers = 32 (all layers offloaded when GPU active)
 *   - Cortex-A73 big cores + A53 LITTLE → n_threads = 6 (decode), n_threads_batch = 8 (prefill)
 *   - n_batch = n_ctx (2048) → prefill processes full prompt in one pass (no redundant sweeps)
 *   - Models ≤ 2 GB: mmap = false → loaded fully into heap; Android cannot page-evict heap
 *     memory without OOM-killing the process, so inference speed is stable at all times.
 *     mlock is NOT used (requires CAP_IPC_LOCK; fails silently with EPERM on non-rooted Android).
 *   - Models > 2 GB: mmap = true + mlock attempt (necessary to fit 6 GB device RAM budget)
 *
 * Model: Llama-3.2-1B-Instruct-Q4_K_M.gguf
 *   - Disk: ~870MB → loaded entirely into heap on first launch (~5 s one-time cost)
 *   - RAM (RSS): ~1400MB (heap) + 2048 ctx KV-cache overhead
 *   - Speed (CPU-only): ~2–5 tok/s; ~8–15 tok/s with Vulkan GPU offload
 *
 * Token callback interface (streamed to ChatScreen / AgentViewModel):
 *   interface TokenCallback { fun onToken(token: String) }
 */
object LlamaEngine {

    private val engineMutex = Mutex()

    private var modelHandle: Long = 0L
    private var contextHandle: Long = 0L

    // Track last-used load params so GGUF hot-reload can reload with same settings
    private var lastModelPath: String = ""
    private var lastContextSize: Int = 2048
    private var lastNGpuLayers: Int = 32
    private var lastGpuBackend: String = "opencl"
    private var lastFlashAttn: Boolean = false
    private var lastKvCacheQuant: Boolean = false
    private var lastMemoryMapping: String = "auto"
    private var lastGpuUbatch: Int = 512

    var lastToksPerSec: Double = 0.0
        private set

    var memoryMb: Double = 0.0
        private set

    private var jniAvailable = false

    fun isLoaded(): Boolean = modelHandle != 0L

    /**
     * Load the GGUF model from disk.
     * Calls nativeLoadModel() → nativeCreateContext() via JNI.
     *
     * @param path        Absolute path to the .gguf file in internal storage
     * @param contextSize Token context window (2048 default — halves KV-cache RAM vs 4096,
     *                    reducing swap pressure. Raise to 3072 only if long sessions truncate.)
     * @param nGpuLayers  Layers to offload to GPU (32 = all layers for Q4_K_M 1B)
     */
    fun load(
        path: String,
        contextSize: Int = 2048,
        // FLAWS.md #2 — defaults are deliberately conservative. The Settings
        // screen should override these once the user opts into a GPU backend
        // and confirms it works on their device. Shipping `gpu_backend=opencl`
        // + `n_gpu_layers=32` as the default would silently OOM-fault on
        // devices whose Mali OpenCL driver cannot allocate the full model
        // footprint, with no fallback path. CPU-only is slow but always works.
        nGpuLayers: Int = 0,
        gpuBackend: String = "cpu",
        flashAttn: Boolean = false,
        kvCacheQuant: Boolean = false,
        memoryMapping: String = "auto",
        gpuUbatch: Int = 512
    ) {
        lastModelPath    = path
        lastContextSize  = contextSize
        lastNGpuLayers   = nGpuLayers
        lastGpuBackend   = gpuBackend
        lastFlashAttn    = flashAttn
        lastKvCacheQuant = kvCacheQuant
        lastMemoryMapping = memoryMapping
        lastGpuUbatch    = gpuUbatch
        if (jniAvailable) {
            modelHandle   = nativeLoadModel(path, contextSize, nGpuLayers, gpuBackend, memoryMapping)
            contextHandle = if (modelHandle != 0L)
                nativeCreateContext(modelHandle, contextSize, flashAttn, kvCacheQuant, gpuUbatch) else 0L
            memoryMb      = if (isLoaded()) nativeGetMemoryMb() else 0.0
        } else {
            // Stub active when llama.cpp submodule not yet compiled
            modelHandle   = 1L
            contextHandle = 1L
            memoryMb      = 1700.0
        }
    }

    /**
     * Run a single inference turn with streaming token callback.
     *
     * @param prompt    Full formatted prompt (built by PromptBuilder)
     * @param maxTokens Maximum tokens to generate (200–512 for action responses)
     * @param onToken   Streaming callback — called once per token as it generates
     * @return          Full generated text (complete before returning)
     */
    suspend fun infer(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        onToken: ((String) -> Unit)? = null
    ): String = engineMutex.withLock {
        if (!isLoaded()) throw IllegalStateException("Model not loaded. Call load() first.")

        if (jniAvailable) {
            val callback = onToken?.let { cb ->
                object : TokenCallback { override fun onToken(token: String) { cb(token) } }
            }
            val result = nativeRunInference(contextHandle, prompt, maxTokens, temperature, callback)
            lastToksPerSec = nativeGetToksPerSec()
            result
        } else {
            // Stub response for architecture testing without llama.cpp compiled
            val stub = """{"tool":"Click","node_id":"#1","reason":"stub inference — llama.cpp not compiled"}"""
            lastToksPerSec = 11.5
            onToken?.invoke(stub)
            stub
        }
    }

    fun unload() {
        // Wait for any in-progress inference to finish before freeing JNI memory.
        // Called from Dispatchers.IO so blocking is acceptable, BUT we cap the wait
        // at 10 seconds via withTimeoutOrNull so this can never cause an ANR.
        // If inference has not released the lock within 10 s, we log a warning and
        // skip the native free entirely — the model stays in RAM but the app stays
        // alive. The OS will reclaim memory when the process exits.
        kotlinx.coroutines.runBlocking {
            val completed = kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                engineMutex.withLock {
                    if (unifiedMode) {
                        // In unified mode text handles are aliases to vision handles.
                        // Zero them out WITHOUT calling nativeFree — unloadVision() owns the memory.
                        modelHandle    = 0L
                        contextHandle  = 0L
                        unifiedMode    = false
                    } else {
                        val ctxSnap   = contextHandle
                        val mdlSnap   = modelHandle
                        modelHandle   = 0L
                        contextHandle = 0L
                        if (jniAvailable) {
                            if (ctxSnap != 0L) runCatching { nativeFreeContext(ctxSnap) }
                            if (mdlSnap != 0L) runCatching { nativeFreeModel(mdlSnap) }
                        }
                    }
                    lastToksPerSec = 0.0
                    memoryMb       = 0.0
                }
            }
            if (completed == null) {
                android.util.Log.w("LlamaEngine",
                    "unload(): inference lock not released within 10 s — skipping native free to prevent ANR. " +
                    "Memory will be reclaimed when the process exits.")
            }
        }
    }

    // ─── JNI declarations ────────────────────────────────────────────────────
    // Implemented in llama_jni.cpp — compiled by CMakeLists.txt via NDK.
    // System.loadLibrary() is called in companion object init.

    private external fun nativeLoadModel(
        path: String, ctxSize: Int, nGpuLayers: Int,
        gpuBackend: String, memoryMapping: String
    ): Long
    private external fun nativeCreateContext(
        modelHandle: Long, contextSize: Int,
        flashAttn: Boolean, kvCacheQuant: Boolean, gpuUbatch: Int
    ): Long
    private external fun nativeRunInference(
        ctxHandle:     Long,
        prompt:        String,
        maxTokens:     Int,
        temperature:   Float,
        tokenCallback: TokenCallback?
    ): String
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeFreeContext(handle: Long)
    private external fun nativeGetToksPerSec(): Double
    private external fun nativeGetMemoryMb(): Double

    // ─── Token streaming callback interface ───────────────────────────────────
    // Passed through JNI to C++ — each generated token calls onToken().

    interface TokenCallback {
        fun onToken(token: String)
    }

    // ─── Multimodal / Vision ──────────────────────────────────────────────────
    //
    // Vision requires a multimodal base model (e.g. moondream2, SmolVLM) plus a
    // matching CLIP projection file (mmproj). These are separate from the Llama 3.2-1B
    // text model — they are loaded into completely independent handles so they
    // can coexist in memory without interfering.
    //
    // Handle layout:
    //   visionModelHandle  — llama_model* for the vision base (e.g. moondream2-Q4)
    //   visionCtxHandle    — llama_context* for the vision base
    //   visionHandle       — mtmd_context* (CLIP encoder + projection layer)

    private var visionModelHandle: Long = 0L
    private var visionCtxHandle: Long = 0L
    private var visionHandle: Long = 0L

    /**
     * True when the active model was loaded in unified mode via [loadUnified].
     *
     * In unified mode the primary text handles (modelHandle/contextHandle) are
     * aliased to the vision handles — a single model instance handles BOTH
     * visual understanding and text reasoning.  The agent loop skips the
     * separate VisionEngine.describe() step and calls [inferWithVision]
     * directly with the raw screenshot bytes + full reasoning prompt.
     *
     * In non-unified mode the primary model is text-only and VisionEngine
     * auto-loads SmolVLM-256M as a separate screen-reading helper.
     */
    @Volatile private var unifiedMode: Boolean = false

    fun isUnifiedMode(): Boolean = unifiedMode
    fun isVisionLoaded(): Boolean = visionHandle != 0L

    /**
     * Load a multimodal vision model + its CLIP mmproj file.
     *
     * Must be called BEFORE [inferWithVision].
     * Safe to call even when the main text model (Llama 3.2-1B) is loaded —
     * they use independent handles and do not share state.
     *
     * @param visionModelPath  Absolute path to vision base GGUF (e.g. moondream2-Q4_K_M.gguf)
     * @param mmProjPath       Absolute path to the mmproj GGUF (e.g. moondream2-mmproj.gguf)
     * @param contextSize      KV-cache size for vision model (2048 is enough for moondream2)
     * @param nGpuLayers       GPU layers to offload via Vulkan (32 = all layers; 0 = CPU-only fallback)
     * @return true if both model and mmproj loaded successfully
     */
    fun loadVision(
        visionModelPath: String,
        mmProjPath: String,
        contextSize: Int = 2048,
        nGpuLayers: Int = 32,
        gpuBackend: String = "opencl",
        flashAttn: Boolean = false,
        kvCacheQuant: Boolean = false,
        memoryMapping: String = "auto",
        gpuUbatch: Int = 512
    ): Boolean {
        if (!jniAvailable) {
            android.util.Log.w("LlamaEngine", "loadVision: stub mode — JNI not compiled")
            visionModelHandle = 2L   // non-zero sentinel for stub
            visionCtxHandle   = 2L
            visionHandle      = 2L
            return true
        }
        unloadVision()
        visionModelHandle = nativeLoadModel(visionModelPath, contextSize, nGpuLayers, gpuBackend, memoryMapping)
        if (visionModelHandle == 0L) {
            android.util.Log.e("LlamaEngine", "loadVision: failed to load vision base model")
            return false
        }
        visionCtxHandle = nativeCreateContext(visionModelHandle, contextSize, flashAttn, kvCacheQuant, gpuUbatch)
        if (visionCtxHandle == 0L) {
            android.util.Log.e("LlamaEngine", "loadVision: failed to create vision context")
            nativeFreeModel(visionModelHandle); visionModelHandle = 0L
            return false
        }
        visionHandle = nativeInitVision(mmProjPath, visionModelHandle, flashAttn)
        if (visionHandle == 0L) {
            android.util.Log.e("LlamaEngine", "loadVision: mmproj init failed")
            nativeFreeContext(visionCtxHandle); visionCtxHandle = 0L
            nativeFreeModel(visionModelHandle); visionModelHandle = 0L
            return false
        }
        android.util.Log.i("LlamaEngine", "Vision loaded: model=$visionModelPath mmproj=$mmProjPath")
        return true
    }

    /**
     * Free vision model, context, and mmproj handles.
     * The main text model is NOT affected unless running in unified mode,
     * in which case [unload] must be called first to clear the aliases.
     */
    fun unloadVision() {
        if (jniAvailable) {
            if (visionHandle      != 0L) { nativeFreeVision(visionHandle);      visionHandle      = 0L }
            if (visionCtxHandle   != 0L) { nativeFreeContext(visionCtxHandle);  visionCtxHandle   = 0L }
            if (visionModelHandle != 0L) { nativeFreeModel(visionModelHandle);  visionModelHandle = 0L }
        } else {
            visionHandle = 0L; visionCtxHandle = 0L; visionModelHandle = 0L
        }
    }

    /**
     * Load a multimodal VLM as the SOLE model instance for both vision and reasoning.
     *
     * The model is loaded ONCE into the vision handles.  The primary text handles
     * (modelHandle / contextHandle) are then aliased to the same values so that
     * [isLoaded] returns true and callers that check the primary handles still work.
     * No second copy of the model is held in RAM — critical on M31 (6 GB).
     *
     * After this call:
     *   • [isUnifiedMode] == true
     *   • [isLoaded]      == true  (text handles aliased to vision handles)
     *   • [isVisionLoaded]== true
     *
     * The agent loop detects [isUnifiedMode] and calls [inferWithVision] directly
     * with the raw screenshot + full reasoning prompt, skipping the separate
     * VisionEngine.describe() step entirely.
     *
     * @param modelPath   Absolute path to the VLM base GGUF
     * @param mmProjPath  Absolute path to the matching mmproj GGUF (F16)
     * @param contextSize KV-cache size (2048 default — sufficient for agent action outputs;
     *                    raise to 3072 only if long reasoning chains are being truncated)
     * @param nGpuLayers  GPU offload layers via Vulkan; 32 = all layers for Mali-G72
     * @return true if both model + mmproj loaded successfully
     */
    fun loadUnified(
        modelPath:     String,
        mmProjPath:    String,
        contextSize:   Int = 2048,
        nGpuLayers:    Int = 32,
        gpuBackend:    String = "opencl",
        flashAttn:     Boolean = false,
        kvCacheQuant:  Boolean = false,
        memoryMapping: String = "auto",
        gpuUbatch:     Int = 512
    ): Boolean {
        unload()
        unloadVision()
        val ok = loadVision(modelPath, mmProjPath, contextSize, nGpuLayers, gpuBackend, flashAttn, kvCacheQuant, memoryMapping, gpuUbatch)
        if (ok) {
            // Alias — no extra RAM cost, no extra JNI calls
            modelHandle   = visionModelHandle
            contextHandle = visionCtxHandle
            unifiedMode   = true
            memoryMb      = if (jniAvailable) nativeGetMemoryMb() else 1700.0
            android.util.Log.i("LlamaEngine",
                "Unified VLM mode active: $modelPath + $mmProjPath")
        }
        return ok
    }

    /**
     * Run vision inference: encode [imageBytes] (JPEG/PNG) through CLIP, then
     * generate a response to [prompt] grounded in the image.
     *
     * Requires [loadVision] to have been called successfully.
     * Falls back to a stub description when JNI is not compiled.
     *
     * @param imageBytes  Raw JPEG or PNG bytes — produced by Bitmap.compress(JPEG, 85)
     * @param prompt      Text question about the image (image marker is prepended automatically)
     * @param maxTokens   Generation cap — 128 is enough for screen descriptions
     * @param onToken     Optional streaming callback
     * @return Full generated text response
     */
    suspend fun inferWithVision(
        imageBytes: ByteArray,
        prompt: String,
        maxTokens: Int = 128,
        onToken: ((String) -> Unit)? = null
    ): String {
        if (!isVisionLoaded()) throw IllegalStateException("Vision not loaded. Call loadVision() first.")
        return if (jniAvailable) {
            val callback = onToken?.let { cb ->
                object : TokenCallback { override fun onToken(token: String) { cb(token) } }
            }
            val result = nativeRunVisionInference(
                visionCtxHandle, visionHandle, imageBytes, prompt, maxTokens, callback
            )
            lastToksPerSec = nativeGetToksPerSec()
            result
        } else {
            val stub = "[vision stub] Screen shows a mobile UI. Text visible via OCR. " +
                "No mmproj compiled yet — install libllama-jni.so to enable real vision."
            onToken?.invoke(stub)
            stub
        }
    }

    // ─── Vision JNI declarations (implemented in llama_jni.cpp) ──────────────

    private external fun nativeInitVision(mmProjPath: String, modelHandle: Long, flashAttn: Boolean): Long
    private external fun nativeFreeVision(visionHandle: Long)
    private external fun nativeRunVisionInference(
        ctxHandle:     Long,
        visionHandle:  Long,
        imageBytes:    ByteArray,
        prompt:        String,
        maxTokens:     Int,
        tokenCallback: TokenCallback?
    ): String

    // ─── LoRA adapter loading ─────────────────────────────────────────────────

    private external fun nativeLoadLora(ctxHandle: Long, adapterPath: String, scale: Float): Boolean

    /**
     * Load a trained adapter or checkpoint on top of the current model.
     *
     * Detects the file type from its first 4 bytes:
     *   "GGUF" (0x47 0x47 0x55 0x46) → full GGUF model checkpoint produced by
     *     nativeTrainLora() via llama_model_save_to_file(). Unloads the current
     *     model and reloads using the checkpoint path, preserving contextSize and
     *     nGpuLayers from the original load() call.
     *   Otherwise → classic LoRA adapter binary; loaded via nativeLoadLora()
     *     (llama_adapter_lora_init + llama_set_adapters_lora).
     *
     * @param adapterPath Absolute path to the .gguf checkpoint or LoRA .bin file
     * @param scale       LoRA influence weight — ignored for GGUF checkpoints
     */
    fun loadLora(adapterPath: String, scale: Float = 0.8f): Boolean {
        if (!jniAvailable || !isLoaded()) return false
        return try {
            if (isGgufFile(adapterPath)) {
                // Full GGUF model checkpoint — unload inference model and reload
                // the fine-tuned weights as the new base model.
                android.util.Log.i("LlamaEngine",
                    "GGUF checkpoint detected — hot-reloading as base model: $adapterPath")
                unload()
                load(adapterPath, lastContextSize, lastNGpuLayers, lastGpuBackend, lastFlashAttn, lastKvCacheQuant, lastMemoryMapping, lastGpuUbatch)
                isLoaded()
            } else {
                // Classic LoRA adapter binary — apply on top of loaded base model
                val ok = nativeLoadLora(contextHandle, adapterPath, scale)
                android.util.Log.i("LlamaEngine",
                    "LoRA adapter loaded: $adapterPath (scale=$scale) → $ok")
                ok
            }
        } catch (e: Exception) {
            android.util.Log.w("LlamaEngine", "loadLora failed: ${e.message}")
            false
        }
    }

    /**
     * Returns true if the file starts with the GGUF magic bytes (0x47 0x47 0x55 0x46 = "GGUF").
     * Used by loadLora() to distinguish trained GGUF checkpoints from LoRA adapter binaries.
     */
    private fun isGgufFile(path: String): Boolean {
        return try {
            java.io.RandomAccessFile(path, "r").use { f ->
                val magic = ByteArray(4)
                f.readFully(magic)
                magic[0] == 0x47.toByte() &&   // 'G'
                magic[1] == 0x47.toByte() &&   // 'G'
                magic[2] == 0x55.toByte() &&   // 'U'
                magic[3] == 0x46.toByte()      // 'F'
            }
        } catch (e: Exception) { false }
    }

    // companion object is invalid inside an `object` — use a bare init block instead.
    init {
        try {
            System.loadLibrary("llama-jni")
            // Library loaded successfully — activate real JNI inference path
            markJniAvailable()
            android.util.Log.i("LlamaEngine", "llama-jni loaded — real inference active")
        } catch (e: UnsatisfiedLinkError) {
            // llama.cpp not compiled yet (submodule missing) — stub mode active
            android.util.Log.w("LlamaEngine", "llama-jni not found — running in stub mode")
        }
    }

    /**
     * Called by init block after System.loadLibrary succeeds.
     * Switches from stub → real JNI inference.
     */
    internal fun markJniAvailable() {
        jniAvailable = true
    }
}
