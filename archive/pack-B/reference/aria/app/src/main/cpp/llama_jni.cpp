#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <chrono>
#include <atomic>
#include <cctype>           // std::tolower / std::isspace for backend-string normalisation
#include <sys/stat.h>

#include "llama.h"
// common.h is in the include path via CMakeLists.txt target_include_directories.
#include "common.h"
// ggml-backend.h — backend device registry API (enumerate Vulkan / OpenCL / CPU devices).
// Used by nativeLoadModel to route inference to the backend the user selected in Settings.
#include "ggml-backend.h"
// mtmd: multimodal library for vision (CLIP image encoder + projection layer).
// mtmd.h is the public API; mtmd-helper.h provides mtmd_helper_eval_chunks and
// mtmd_helper_bitmap_init_from_buf which handle the full encode-decode pipeline.
#include "mtmd.h"
#include "mtmd-helper.h"
// NOTE: ggml-opt.h is included transitively via llama.h (llama.h line 7: #include "ggml-opt.h").
// LLAMA_HAS_TRAINING is defined in CMakeLists.txt — the training block below is active.
// A redundant direct include is NOT needed here; API verified against llama.cpp submodule.

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Global model state ───────────────────────────────────────────────────────
// Stored as raw pointers behind Long handles to cross the JNI boundary.
// Both handles are checked for null before any operation.

static llama_model*        g_model        = nullptr;
static llama_context*      g_ctx          = nullptr;
static llama_adapter_lora* g_lora_adapter = nullptr;  // active LoRA adapter (or null)
static mtmd_context*       g_vision_ctx   = nullptr;  // CLIP + projection layer (or null)

// Token/second tracking — read by getLlmStatus() bridge call
static std::atomic<double> g_toks_per_sec{0.0};
static std::atomic<double> g_memory_mb{0.0};

extern "C" {

// ─── nativeLoadModel ─────────────────────────────────────────────────────────
// Loads a GGUF file with mmap (never fully in RAM — only accessed pages loaded).
// gpu_backend: "vulkan" | "opencl" | "cpu" — selects which GGML GPU backend to use.
//   Both Vulkan and OpenCL are compiled in; this routes the model to the correct one
//   via llama_model_params.devices (GGML backend device registry API).
// Returns a non-zero Long handle on success, 0 on failure.
JNIEXPORT jlong JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeLoadModel(
    JNIEnv* env,
    jobject /* thiz */,
    jstring path_jstr,
    jint    ctx_size,
    jint    n_gpu_layers,
    jstring gpu_backend_jstr,
    jstring memory_mapping_jstr   // "auto" | "heap" | "mmap" — from user Settings
) {
    const char* path           = env->GetStringUTFChars(path_jstr,           nullptr);
    const char* gpu_backend    = env->GetStringUTFChars(gpu_backend_jstr,    nullptr);
    const char* memory_mapping = env->GetStringUTFChars(memory_mapping_jstr, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    // ── Memory mapping policy (user-configurable) ─────────────────────────────
    // "heap"  — load into anonymous RAM; Android cannot page-evict heap without
    //           OOM-killing the process → inference speed is always consistent.
    //           Penalty: ~5 s cold-start; model fully copied from eMMC on launch.
    // "mmap"  — file-backed mapping + mlock attempt.  Fastest cold-start; mlock
    //           may silently fail with EPERM on non-root stock Android kernels,
    //           leaving pages vulnerable to eviction under memory pressure.
    // "auto"  — pick based on file size: ≤ 2 GB → heap (safe),
    //                                    > 2 GB → mmap + mlock attempt (avoids OOM).
    {
        bool use_mmap, use_mlock;
        if (strcmp(memory_mapping, "heap") == 0) {
            use_mmap = false; use_mlock = false;
            LOGI("Memory mapping: heap (user override)");
        } else if (strcmp(memory_mapping, "mmap") == 0) {
            use_mmap = true; use_mlock = true;
            LOGI("Memory mapping: mmap + mlock attempt (user override)");
        } else {
            // "auto": pick based on model file size
            struct stat st;
            const bool large_model =
                (stat(path, &st) == 0) && (st.st_size > 2LL * 1024 * 1024 * 1024);
            use_mmap  = large_model;
            use_mlock = large_model;
            LOGI("Memory mapping: auto → %s",
                 large_model ? "mmap + mlock attempt" : "heap (≤2 GB model)");
        }
        mparams.use_mmap  = use_mmap;
        mparams.use_mlock = use_mlock;
    }
    env->ReleaseStringUTFChars(memory_mapping_jstr, memory_mapping);

    // ── Backend device selection ──────────────────────────────────────────────
    // Both Vulkan and OpenCL are registered in GGML's backend registry when both
    // are compiled in. llama_model_params.devices is a null-terminated array of
    // ggml_backend_dev_t; passing NULL uses GGML's default priority (Vulkan > OpenCL > CPU).
    // We build an explicit list so the user's Settings choice is always honoured.
    std::vector<ggml_backend_dev_t> selected_devices;

    // FLAWS.md #7 — accept "Vulkan", "VULKAN", " vulkan ", etc. The Settings
    // screen historically saved a capitalised string; an exact strcmp would
    // silently fall through to "default priority" without the user knowing
    // their explicit choice was ignored. Normalise to lowercase + trim before
    // comparing.
    std::string backend_norm(gpu_backend);
    // strip surrounding whitespace
    while (!backend_norm.empty() && std::isspace(static_cast<unsigned char>(backend_norm.front())))
        backend_norm.erase(backend_norm.begin());
    while (!backend_norm.empty() && std::isspace(static_cast<unsigned char>(backend_norm.back())))
        backend_norm.pop_back();
    for (char& c : backend_norm)
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));

    bool want_vulkan = (backend_norm == "vulkan") && (n_gpu_layers > 0);
    bool want_opencl = (backend_norm == "opencl") && (n_gpu_layers > 0);

    if (want_vulkan || want_opencl) {
        ggml_backend_dev_t cpu_dev = nullptr;
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t dev  = ggml_backend_dev_get(i);
            enum ggml_backend_dev_type t = ggml_backend_dev_type(dev);
            const char* name        = ggml_backend_dev_name(dev);
            if (t == GGML_BACKEND_DEVICE_TYPE_CPU) {
                cpu_dev = dev;                  // always include CPU as compute fallback
            } else if (want_vulkan && strstr(name, "Vulkan")  != nullptr) {
                selected_devices.push_back(dev);
                LOGI("Backend selected: Vulkan (%s)", name);
            } else if (want_opencl && strstr(name, "OpenCL") != nullptr) {
                selected_devices.push_back(dev);
                LOGI("Backend selected: OpenCL (%s)", name);
            }
        }
        if (!selected_devices.empty()) {
            if (cpu_dev) selected_devices.push_back(cpu_dev);
            selected_devices.push_back(nullptr);   // null-terminate the list
            mparams.devices = selected_devices.data();
        } else {
            // Requested backend not found in registry — fall back to default priority
            LOGI("Backend '%s' not found in registry — using default priority", gpu_backend);
        }
    } else {
        LOGI("GPU backend: %s (n_gpu_layers=%d) — using GGML default priority",
             gpu_backend, n_gpu_layers);
    }

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(path_jstr,        path);
    env->ReleaseStringUTFChars(gpu_backend_jstr, gpu_backend);

    if (!model) {
        LOGE("Failed to load model");
        return 0L;
    }

    // Estimate RSS: Q4_K_M 1B ~ 870MB disk, ~1700MB RSS when context allocated
    g_memory_mb.store(1700.0);

    LOGI("Model loaded — n_params=%lld gpu_layers=%d backend=%s",
         (long long)llama_model_n_params(model), n_gpu_layers, gpu_backend);
    return reinterpret_cast<jlong>(model);
}

// ─── nativeCreateContext ──────────────────────────────────────────────────────
// Creates the KV cache + compute graph for inference.
// ctx_size is passed from Kotlin (LlamaEngine.load / loadVision) so the caller
// controls context length — previously this was hardcoded to 4096 and the
// Kotlin-side contextSize parameter was silently ignored.
JNIEXPORT jlong JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeCreateContext(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong    model_handle,
    jint     ctx_size,
    jboolean flash_attn,
    jboolean kv_quant,
    jint     gpu_ubatch    // OpenCL/Vulkan micro-batch size; 0 = use default (512)
) {
    auto* model = reinterpret_cast<llama_model*>(model_handle);
    if (!model) return 0L;

    // Clamp to a safe range: minimum 512 (< 512 is useless), max 4096 (OOM risk on M31)
    int32_t n_ctx = (ctx_size > 0) ? ctx_size : 2048;
    if (n_ctx < 512)  n_ctx = 512;
    if (n_ctx > 4096) n_ctx = 4096;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)n_ctx;
    // n_batch = n_ctx allows the prefill (prompt evaluation) to process the entire
    // prompt in one llama_decode call.  Without this the default n_batch (512) splits
    // a 2048-token prompt into 4 sequential chunks, each requiring a full weight
    // sweep — multiplying prefill time by 4 on CPU paths.
    cparams.n_batch         = (uint32_t)n_ctx;
    // n_ubatch: micro-batch size for OpenCL/Vulkan kernel dispatch.
    // Larger = better GPU pipeline fill; smaller = lower VRAM pressure.
    // Mali-G72 MP3 sweet spot: 512.  User-configurable; clamped to [64, n_ctx].
    {
        uint32_t ubatch = (gpu_ubatch > 0) ? (uint32_t)gpu_ubatch : 512u;
        if (ubatch < 64u)  ubatch = 64u;
        if (ubatch > (uint32_t)n_ctx) ubatch = (uint32_t)n_ctx;
        cparams.n_ubatch = ubatch;
    }
    // 6 threads: 4 big Cortex-A73 + 2 Cortex-A53 LITTLE cores.
    // Inference is memory-bandwidth-bound so LITTLE cores still contribute.
    // Using 8 can cause thermal throttling; 6 is the stable balance on the M31.
    cparams.n_threads       = 6;
    // n_threads_batch controls prompt-evaluation (prefill) parallelism.
    // Prefill is more compute-bound than decode so all 8 cores help here.
    cparams.n_threads_batch = 8;
    // Flash Attention: AUTO lets llama.cpp decide per-backend; falls back gracefully
    // if the Mali driver version doesn't support it.  DISABLED is the safe default.
    cparams.flash_attn_type = flash_attn
        ? LLAMA_FLASH_ATTN_TYPE_AUTO
        : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    // KV cache quantization: Q8_0 halves KV memory (~256 MB → ~128 MB at 2048 ctx, F16 base).
    // Negligible quality loss vs F16; avoids swap pressure on 6 GB M31.
    if (kv_quant) {
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
    }

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create context (n_ctx=%d)", (int)n_ctx);
        return 0L;
    }

    g_ctx = ctx;  // cache for LoRA cleanup in nativeFreeModel
    LOGI("Context created — n_ctx=%d flash_attn=%d kv_quant=%d n_batch=%d n_ubatch=%d",
         (int)n_ctx, (int)flash_attn, (int)kv_quant, (int)n_ctx, (int)cparams.n_ubatch);
    return reinterpret_cast<jlong>(ctx);
}

// ─── nativeRunInference ───────────────────────────────────────────────────────
// Runs a single inference turn with streaming token callback.
// tokenCallbackObj: a Java object implementing the TokenCallback interface.
// Returns the full generated text as a jstring.
JNIEXPORT jstring JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeRunInference(
    JNIEnv* env,
    jobject /* thiz */,
    jlong   ctx_handle,
    jstring prompt_jstr,
    jint    max_tokens,
    jfloat  temperature,
    jobject token_callback
) {
    auto* ctx   = reinterpret_cast<llama_context*>(ctx_handle);
    const auto* model = llama_get_model(ctx);
    if (!ctx || !model) {
        return env->NewStringUTF("{\"error\":\"context not initialized\"}");
    }
    const llama_vocab* vocab = llama_model_get_vocab(model);

    const char* prompt_cstr = env->GetStringUTFChars(prompt_jstr, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(prompt_jstr, prompt_cstr);

    // ── Tokenize ─────────────────────────────────────────────────────────────
    // First call with nullptr to determine token count, then fill buffer.
    int32_t n_prompt_tokens = -llama_tokenize(
        vocab, prompt.c_str(), (int32_t)prompt.size(),
        nullptr, 0, /*add_special=*/true, /*parse_special=*/true
    );
    std::vector<llama_token> tokens(n_prompt_tokens);
    int32_t filled = llama_tokenize(
        vocab, prompt.c_str(), (int32_t)prompt.size(),
        tokens.data(), n_prompt_tokens, /*add_special=*/true, /*parse_special=*/true
    );
    if (filled < 0) {
        LOGE("Tokenization failed (filled=%d)", (int)filled);
        return env->NewStringUTF("{\"error\":\"tokenize_failed\"}");
    }
    tokens.resize(filled);

    // Use the actual context size (not hardcoded 4096) so this check is correct
    // regardless of what contextSize was passed at load time.
    const int32_t n_ctx = (int32_t)llama_n_ctx(ctx);
    if ((int32_t)tokens.size() >= n_ctx) {
        LOGE("Prompt too long: %zu tokens (n_ctx=%d)", tokens.size(), (int)n_ctx);
        return env->NewStringUTF("{\"error\":\"prompt_too_long\"}");
    }

    llama_memory_clear(llama_get_memory(ctx), true);
    llama_decode(ctx, llama_batch_get_one(tokens.data(), (int)tokens.size()));

    // ── Token callback reference ──────────────────────────────────────────────
    jclass cb_class  = nullptr;
    jmethodID cb_mid = nullptr;
    if (token_callback) {
        cb_class = env->GetObjectClass(token_callback);
        cb_mid   = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
    }

    // ── Sample loop ───────────────────────────────────────────────────────────
    std::string output;
    output.reserve(512);

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto t_start = std::chrono::high_resolution_clock::now();
    int n_gen = 0;

    for (int i = 0; i < max_tokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char buf[256];
        int  len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (len < 0) continue;

        std::string piece(buf, len);
        output += piece;
        n_gen++;

        // Stream token to JVM callback
        if (token_callback && cb_class && cb_mid) {
            jstring tok_jstr = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(token_callback, cb_mid, tok_jstr);
            env->DeleteLocalRef(tok_jstr);
        }

        llama_batch batch = llama_batch_get_one(&tok, 1);
        llama_decode(ctx, batch);
    }

    llama_sampler_free(sampler);

    auto t_end = std::chrono::high_resolution_clock::now();
    double elapsed_s = std::chrono::duration<double>(t_end - t_start).count();
    g_toks_per_sec.store(n_gen / (elapsed_s > 0 ? elapsed_s : 1.0));

    LOGI("Inference done — %d tokens in %.2fs (%.1f tok/s)", n_gen, elapsed_s, g_toks_per_sec.load());
    return env->NewStringUTF(output.c_str());
}

// ─── nativeLoadLora ───────────────────────────────────────────────────────────
// Loads a LoRA adapter from disk and applies it to the already-loaded context.
// Uses llama_adapter_lora_init() / llama_set_adapters_lora() (current llama.cpp API).
// Safe to call multiple times — frees the previous adapter before loading new one.
// scale: 0.0 = off, 0.8 = typical usage, 1.0 = full adapter influence.
JNIEXPORT jboolean JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeLoadLora(
    JNIEnv* env,
    jobject /* thiz */,
    jlong   ctx_handle,
    jstring path_jstr,
    jfloat  scale
) {
    auto* ctx = reinterpret_cast<llama_context*>(ctx_handle);
    if (!ctx) {
        LOGE("nativeLoadLora: context is null");
        return JNI_FALSE;
    }

    const char* path = env->GetStringUTFChars(path_jstr, nullptr);

    // Free existing adapter before loading a new one
    if (g_lora_adapter) {
        llama_set_adapters_lora(ctx, nullptr, 0, nullptr);
        llama_adapter_lora_free(g_lora_adapter);
        g_lora_adapter = nullptr;
    }

    // llama_get_model returns const; llama_adapter_lora_init needs non-const
    auto* model = const_cast<llama_model*>(llama_get_model(ctx));
    g_lora_adapter = llama_adapter_lora_init(model, path);
    env->ReleaseStringUTFChars(path_jstr, path);

    if (!g_lora_adapter) {
        LOGE("nativeLoadLora: failed to init adapter from %s", path);
        return JNI_FALSE;
    }

    float scale_val = scale;
    int32_t rc = llama_set_adapters_lora(ctx, &g_lora_adapter, 1, &scale_val);
    if (rc != 0) {
        LOGE("nativeLoadLora: llama_set_adapters_lora failed rc=%d", rc);
        llama_adapter_lora_free(g_lora_adapter);
        g_lora_adapter = nullptr;
        return JNI_FALSE;
    }

    LOGI("LoRA adapter loaded (scale=%.2f)", (double)scale);
    return JNI_TRUE;
}

// ─── nativeFreeModel ─────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeFreeModel(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong   model_handle
) {
    // Free LoRA adapter before freeing the model (adapter references the model)
    if (g_lora_adapter && g_ctx) {
        llama_set_adapters_lora(g_ctx, nullptr, 0, nullptr);
        llama_adapter_lora_free(g_lora_adapter);
        g_lora_adapter = nullptr;
    }

    auto* model = reinterpret_cast<llama_model*>(model_handle);
    if (model) {
        llama_model_free(model);
        g_memory_mb.store(0.0);
        g_toks_per_sec.store(0.0);
        LOGI("Model freed");
    }
}

// ─── nativeFreeContext ────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeFreeContext(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong   ctx_handle
) {
    auto* ctx = reinterpret_cast<llama_context*>(ctx_handle);
    if (ctx) {
        llama_free(ctx);
        if (g_ctx == ctx) g_ctx = nullptr;
        LOGI("Context freed");
    }
}

// ─── nativeInitVision ────────────────────────────────────────────────────────
// Loads a multimodal projection (mmproj) GGUF and creates an mtmd_context.
// The text model must already be loaded (model_handle from nativeLoadModel).
// Returns a non-zero Long handle on success, 0 on failure.
//
// Required on-device files (both in GGUF format):
//   • LLM base:  e.g. moondream2-Q4_K_M.gguf  (loaded via nativeLoadModel)
//   • mmproj:    e.g. moondream2-mmproj.gguf   (loaded here)
// Tested vision models on Cortex-A73 (≤ 2 GB total RSS):
//   moondream2 (2B), SmolVLM-Instruct-500M/2B, MiniCPM-V-2.6-Q4_K_M
JNIEXPORT jlong JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeInitVision(
    JNIEnv* env,
    jobject /* thiz */,
    jstring  mmproj_path_jstr,
    jlong    model_handle,
    jboolean flash_attn
) {
    auto* model = reinterpret_cast<llama_model*>(model_handle);
    if (!model) {
        LOGE("nativeInitVision: model handle is null");
        return 0L;
    }

    const char* path = env->GetStringUTFChars(mmproj_path_jstr, nullptr);

    mtmd_context_params vparams  = mtmd_context_params_default();
    vparams.use_gpu              = true;   // Vulkan enabled — offload CLIP encoder to Mali-G72
    vparams.n_threads            = 6;      // Cortex-A73 big + 2 A53 LITTLE cores
    vparams.print_timings        = false;
    vparams.flash_attn_type      = flash_attn
                                     ? LLAMA_FLASH_ATTN_TYPE_AUTO
                                     : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    vparams.warmup               = false;  // skip warmup pass to save cold-start time

    mtmd_context* vctx = mtmd_init_from_file(path, model, vparams);
    env->ReleaseStringUTFChars(mmproj_path_jstr, path);

    if (!vctx) {
        LOGE("nativeInitVision: mtmd_init_from_file failed");
        return 0L;
    }
    if (!mtmd_support_vision(vctx)) {
        LOGE("nativeInitVision: mmproj file does not support vision");
        mtmd_free(vctx);
        return 0L;
    }

    g_vision_ctx = vctx;
    LOGI("Vision context initialized");
    return reinterpret_cast<jlong>(vctx);
}

// ─── nativeFreeVision ────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeFreeVision(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong   vision_handle
) {
    auto* vctx = reinterpret_cast<mtmd_context*>(vision_handle);
    if (vctx) {
        mtmd_free(vctx);
        if (g_vision_ctx == vctx) g_vision_ctx = nullptr;
        LOGI("Vision context freed");
    }
}

// ─── nativeRunVisionInference ─────────────────────────────────────────────────
// Runs vision inference: encodes the image through CLIP, prepends the resulting
// embeddings to the text prompt, then generates a response.
//
// image_bytes:  raw JPEG or PNG bytes (stb_image decodes them inside mtmd)
// prompt_jstr:  the text question asked about the image, e.g. "What is in this image?"
//               The image marker is prepended automatically.
// max_tokens:   generation cap
// token_callback: Java object implementing TokenCallback (onToken(String)) for streaming
//
// Returns the full generated response as a jstring, or a JSON error object on failure.
JNIEXPORT jstring JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeRunVisionInference(
    JNIEnv* env,
    jobject /* thiz */,
    jlong       ctx_handle,
    jlong       vision_handle,
    jbyteArray  image_bytes,
    jstring     prompt_jstr,
    jint        max_tokens,
    jobject     token_callback
) {
    auto* ctx  = reinterpret_cast<llama_context*>(ctx_handle);
    auto* vctx = reinterpret_cast<mtmd_context*>(vision_handle);
    if (!ctx || !vctx) {
        return env->NewStringUTF("{\"error\":\"context not initialized\"}");
    }
    const auto* model = llama_get_model(ctx);
    const llama_vocab* vocab = llama_model_get_vocab(model);

    // ── Decode image from raw JPEG/PNG bytes ──────────────────────────────────
    jsize img_len  = env->GetArrayLength(image_bytes);
    jbyte* img_buf = env->GetByteArrayElements(image_bytes, nullptr);
    mtmd_bitmap* bitmap = mtmd_helper_bitmap_init_from_buf(
        vctx, reinterpret_cast<const unsigned char*>(img_buf), (size_t)img_len);
    env->ReleaseByteArrayElements(image_bytes, img_buf, JNI_ABORT);

    if (!bitmap) {
        LOGE("nativeRunVisionInference: failed to decode image bytes");
        return env->NewStringUTF("{\"error\":\"image_decode_failed\"}");
    }

    // ── Build prompt: image marker first, then text question ──────────────────
    // mtmd_tokenize replaces the marker with image token chunks.
    const char* prompt_cstr = env->GetStringUTFChars(prompt_jstr, nullptr);
    std::string full_prompt = std::string(mtmd_default_marker()) + "\n" + prompt_cstr;
    env->ReleaseStringUTFChars(prompt_jstr, prompt_cstr);

    mtmd_input_text input_text;
    input_text.text          = full_prompt.c_str();
    input_text.add_special   = true;
    input_text.parse_special = true;

    // ── Tokenize into text + image chunks ─────────────────────────────────────
    mtmd_input_chunks* chunks       = mtmd_input_chunks_init();
    const mtmd_bitmap* bitmaps_arr[1] = { bitmap };
    int32_t tok_rc = mtmd_tokenize(vctx, chunks, &input_text, bitmaps_arr, 1);
    mtmd_bitmap_free(bitmap);  // bitmap decoded into chunks — safe to free now

    if (tok_rc != 0) {
        LOGE("nativeRunVisionInference: mtmd_tokenize failed rc=%d", tok_rc);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("{\"error\":\"vision_tokenize_failed\"}");
    }

    // ── Eval all chunks: text via llama_decode, image via CLIP + llama_decode ─
    llama_memory_clear(llama_get_memory(ctx), true);
    llama_pos new_n_past = 0;
    int32_t eval_rc = mtmd_helper_eval_chunks(
        vctx, ctx, chunks,
        /*n_past=*/0,
        /*seq_id=*/0,
        /*n_batch=*/(int32_t)llama_n_batch(ctx),
        /*logits_last=*/true,
        &new_n_past
    );
    mtmd_input_chunks_free(chunks);

    if (eval_rc != 0) {
        LOGE("nativeRunVisionInference: eval_chunks failed rc=%d", eval_rc);
        return env->NewStringUTF("{\"error\":\"vision_eval_failed\"}");
    }

    LOGI("nativeRunVisionInference: prompt processed — n_past=%d", (int)new_n_past);

    // ── Token callback setup ───────────────────────────────────────────────────
    jclass    cb_class = nullptr;
    jmethodID cb_mid   = nullptr;
    if (token_callback) {
        cb_class = env->GetObjectClass(token_callback);
        cb_mid   = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");
    }

    // ── Sampling loop ─────────────────────────────────────────────────────────
    // After mtmd_helper_eval_chunks the KV cache is filled to new_n_past.
    // llama_batch_get_one uses pos=nullptr so llama.cpp assigns the next
    // position automatically from the KV cache state — no manual tracking needed.
    std::string output;
    output.reserve(512);

    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(chain_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    // Lower temperature for vision — grounding the response in the image
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto t_start = std::chrono::high_resolution_clock::now();
    int n_gen = 0;

    for (int i = 0; i < (int)max_tokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char buf[256];
        int  piece_len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (piece_len < 0) continue;  // special token with no string representation

        std::string piece(buf, piece_len);
        output += piece;
        n_gen++;

        if (token_callback && cb_class && cb_mid) {
            jstring tok_jstr = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(token_callback, cb_mid, tok_jstr);
            env->DeleteLocalRef(tok_jstr);
        }

        llama_batch next_batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, next_batch) != 0) {
            LOGE("nativeRunVisionInference: llama_decode failed at token %d", i);
            break;
        }
    }

    llama_sampler_free(sampler);

    auto t_end    = std::chrono::high_resolution_clock::now();
    double elapsed_s = std::chrono::duration<double>(t_end - t_start).count();
    g_toks_per_sec.store(n_gen / (elapsed_s > 0.0 ? elapsed_s : 1.0));

    LOGI("nativeRunVisionInference: %d tokens in %.2fs (%.1f tok/s)",
         n_gen, elapsed_s, g_toks_per_sec.load());

    return env->NewStringUTF(output.c_str());
}

// ─── nativeGetToksPerSec ─────────────────────────────────────────────────────
JNIEXPORT jdouble JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeGetToksPerSec(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    return static_cast<jdouble>(g_toks_per_sec.load());
}

// ─── nativeGetMemoryMb ───────────────────────────────────────────────────────
JNIEXPORT jdouble JNICALL
Java_com_ariaagent_mobile_core_ai_LlamaEngine_nativeGetMemoryMb(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    return static_cast<jdouble>(g_memory_mb.load());
}

// ─── nativeTrainLora ─────────────────────────────────────────────────────────
//
// On-device full-model fine-tuning via the llama_opt_* / ggml-opt API.
//
// Called by LoraTrainer.kt ONLY during idle+charging (LearningScheduler gates
// this). The base model must NOT be loaded for inference at the same time
// (AgentLoop stops before LearningScheduler fires — enforced in Kotlin).
//
// Training approach (llama_opt_* high-level API):
//   - llama_opt_init()  — registers AdamW optimizer on the context
//   - llama_opt_epoch() — one pass over the JSONL dataset (forward + backward + Adam)
//   - All weights updated in-place (mmap=false required for writable tensors)
//   - n_gpu_layers=0: training on CPU (Vulkan backends lack full backward pass)
//
// Dataset format (ggml_opt_dataset_t):
//   Each datapoint = one sequence of TRAIN_CTX int32 token IDs (data tensor)
//   Each label     = one sequence of TRAIN_CTX int32 token IDs (label tensor)
//   Label = -1 → masked (input tokens + padding, not trained)
//   Label = token_id → next-token prediction target (output portion only)
//
// Output: GGUF checkpoint written by llama_model_save_to_file().
//   LlamaEngine.loadLora() detects GGUF magic bytes and reloads as base model.
//
// Compile-time flag: LLAMA_HAS_TRAINING must be defined in CMakeLists.txt.
// Without it the function returns JNI_FALSE → Kotlin falls to stubTrainLora().

JNIEXPORT jboolean JNICALL
Java_com_ariaagent_mobile_core_rl_LoraTrainer_nativeTrainLora(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_path_jstr,
    jstring dataset_path_jstr,
    jstring output_path_jstr,
    jint    rank,
    jint    epochs
) {
#if defined(LLAMA_HAS_TRAINING)
    // rank is intentionally unused here — the llama_opt API fine-tunes all weights
    (void)rank;

    // ── 0. String args ────────────────────────────────────────────────────────
    const char* model_path   = env->GetStringUTFChars(model_path_jstr,   nullptr);
    const char* dataset_path = env->GetStringUTFChars(dataset_path_jstr, nullptr);
    const char* output_path  = env->GetStringUTFChars(output_path_jstr,  nullptr);

    LOGI("nativeTrainLora: model=%s dataset=%s out=%s epochs=%d",
         model_path, dataset_path, output_path, (int)epochs);

    // ── 1. Load base model (mmap=false — writable weights for gradient updates) ─
    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap     = false;   // MUST be false: Adam writes deltas into tensors
    mparams.use_mlock    = false;
    mparams.n_gpu_layers = 0;       // CPU training — Vulkan lacks backward pass

    llama_model* model = llama_model_load_from_file(model_path, mparams);
    if (!model) {
        LOGE("nativeTrainLora: failed to load model from %s", model_path);
        env->ReleaseStringUTFChars(model_path_jstr,   model_path);
        env->ReleaseStringUTFChars(dataset_path_jstr, dataset_path);
        env->ReleaseStringUTFChars(output_path_jstr,  output_path);
        return JNI_FALSE;
    }

    // ── 2. Read JSONL dataset ─────────────────────────────────────────────────
    // Format per line: {"input": "...", "output": "..."}
    struct TrainPair { std::string input; std::string output; };
    std::vector<TrainPair> pairs;

    {
        FILE* f = fopen(dataset_path, "r");
        if (!f) {
            LOGE("nativeTrainLora: cannot open dataset %s", dataset_path);
            llama_model_free(model);
            env->ReleaseStringUTFChars(model_path_jstr,   model_path);
            env->ReleaseStringUTFChars(dataset_path_jstr, dataset_path);
            env->ReleaseStringUTFChars(output_path_jstr,  output_path);
            return JNI_FALSE;
        }

        // Minimal JSON string extractor — handles backslash escapes,
        // single-line format, no escaped quote inside key names.
        auto extract_str = [](const char* json, const char* key) -> std::string {
            std::string needle = std::string("\"") + key + "\":\"";
            const char* start = strstr(json, needle.c_str());
            if (!start) return {};
            start += needle.size();
            std::string val;
            bool escaped = false;
            for (const char* p = start; *p; ++p) {
                if (escaped) { val += *p; escaped = false; continue; }
                if (*p == '\\') { escaped = true; continue; }
                if (*p == '"')  break;
                val += *p;
            }
            return val;
        };

        char line[8192];
        while (fgets(line, sizeof(line), f)) {
            std::string inp = extract_str(line, "input");
            std::string out = extract_str(line, "output");
            if (!inp.empty() && !out.empty()) pairs.push_back({inp, out});
        }
        fclose(f);
    }

    LOGI("nativeTrainLora: loaded %zu training pairs", pairs.size());
    if (pairs.empty()) {
        LOGE("nativeTrainLora: empty dataset — nothing to train on");
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path_jstr,   model_path);
        env->ReleaseStringUTFChars(dataset_path_jstr, dataset_path);
        env->ReleaseStringUTFChars(output_path_jstr,  output_path);
        return JNI_FALSE;
    }

    // ── 3. Training context ───────────────────────────────────────────────────
    // TRAIN_CTX = 512: smaller than 4096 inference context → ~8× less KV-cache RAM.
    // n_batch MUST equal n_ctx: llama_opt_init asserts n_ctx_train % n_batch == 0,
    // and we set n_ctx_train = TRAIN_CTX = n_batch so the assertion holds.
    const uint32_t TRAIN_CTX = 512;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx       = TRAIN_CTX;
    cparams.n_batch     = TRAIN_CTX;    // n_batch == n_ctx → 1 sequence per step
    cparams.n_ubatch    = TRAIN_CTX;
    cparams.n_threads   = 4;            // Cortex-A73 big cores
    cparams.n_threads_batch = 4;

    llama_context* train_ctx_ptr = llama_init_from_model(model, cparams);
    if (!train_ctx_ptr) {
        LOGE("nativeTrainLora: failed to create training context (OOM?)");
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path_jstr,   model_path);
        env->ReleaseStringUTFChars(dataset_path_jstr, dataset_path);
        env->ReleaseStringUTFChars(output_path_jstr,  output_path);
        return JNI_FALSE;
    }

    // ── 4. Tokenize all pairs and build ggml_opt_dataset ─────────────────────
    // Each datapoint is a flat int32 array of TRAIN_CTX token IDs (padded with 0).
    // Each label is a flat int32 array of TRAIN_CTX values:
    //   label[i] = -1          → masked (input portion + padding)
    //   label[i] = seq[i+1]    → next-token prediction target (output portion)

    const llama_vocab* vocab = llama_model_get_vocab(model);
    auto tokenize_text = [&](const std::string& text, int max_len) -> std::vector<llama_token> {
        std::vector<llama_token> toks(max_len);
        int n = llama_tokenize(vocab, text.c_str(), (int)text.size(),
                               toks.data(), max_len, /*add_special=*/true, /*parse_special=*/false);
        if (n < 0) n = 0;
        toks.resize(n);
        return toks;
    };

    // Flattened buffers: [ndata × TRAIN_CTX] each
    std::vector<int32_t> data_buf;
    std::vector<int32_t> lbl_buf;
    data_buf.reserve(pairs.size() * TRAIN_CTX);
    lbl_buf.reserve(pairs.size() * TRAIN_CTX);

    for (const auto& p : pairs) {
        // Reserve 3/4 context for input, 1/4 for output
        auto inp_toks = tokenize_text(p.input,  (int)(TRAIN_CTX * 3 / 4));
        auto out_toks = tokenize_text(p.output, (int)(TRAIN_CTX / 4));
        if (inp_toks.empty() || out_toks.empty()) continue;

        // Concatenate input + output; truncate to TRAIN_CTX
        std::vector<llama_token> seq;
        seq.insert(seq.end(), inp_toks.begin(), inp_toks.end());
        seq.insert(seq.end(), out_toks.begin(), out_toks.end());
        if (seq.size() > TRAIN_CTX) seq.resize(TRAIN_CTX);

        const size_t seq_len = seq.size();
        const size_t inp_len = std::min(inp_toks.size(), seq_len);

        for (size_t i = 0; i < TRAIN_CTX; ++i) {
            data_buf.push_back(i < seq_len ? (int32_t)seq[i] : 0);
            // Mask input tokens, padding, and the last output token
            // (no next-token target exists for it)
            if (i < inp_len || i + 1 >= seq_len) {
                lbl_buf.push_back(-1);
            } else {
                lbl_buf.push_back((int32_t)seq[i + 1]);
            }
        }
    }

    const int64_t ndata = (int64_t)(data_buf.size() / TRAIN_CTX);
    if (ndata == 0) {
        LOGE("nativeTrainLora: all pairs empty after tokenization");
        llama_free(train_ctx_ptr);
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path_jstr,   model_path);
        env->ReleaseStringUTFChars(dataset_path_jstr, dataset_path);
        env->ReleaseStringUTFChars(output_path_jstr,  output_path);
        return JNI_FALSE;
    }

    LOGI("nativeTrainLora: building dataset — %lld sequences × %u tokens", (long long)ndata, TRAIN_CTX);

    ggml_opt_dataset_t opt_dataset = ggml_opt_dataset_init(
        GGML_TYPE_I32,           // data type:  llama_token = int32_t
        GGML_TYPE_I32,           // label type: llama_token = int32_t
        (int64_t)TRAIN_CTX,      // ne_datapoint
        (int64_t)TRAIN_CTX,      // ne_label
        ndata,                   // total datapoints
        1                        // ndata_shard (1 = shuffle at individual sample level)
    );

    // Copy flattened data into dataset tensors
    // Tensor shape = [ne_datapoint, ndata] in GGML row-major layout
    struct ggml_tensor* dt = ggml_opt_dataset_data(opt_dataset);
    struct ggml_tensor* lt = ggml_opt_dataset_labels(opt_dataset);
    memcpy(dt->data, data_buf.data(), data_buf.size() * sizeof(int32_t));
    memcpy(lt->data, lbl_buf.data(),  lbl_buf.size()  * sizeof(int32_t));

    // ── 5. Optimizer setup ────────────────────────────────────────────────────
    // AdamW with lr=1e-4 (conservative — on-device fine-tuning on M31 hardware).
    // ggml_opt_get_constant_optimizer_params() is the built-in callback that
    // simply casts userdata → ggml_opt_optimizer_params and returns it (constant LR).
    static ggml_opt_optimizer_params s_opt_pars = {
        /* adamw = */ {
            /* alpha = */ 1e-4f,   // learning rate
            /* beta1 = */ 0.9f,
            /* beta2 = */ 0.999f,
            /* eps   = */ 1e-8f,
            /* wd    = */ 0.0f,    // no weight decay
        },
        /* sgd = */ {
            /* alpha = */ 1e-4f,
            /* wd    = */ 0.0f,
        },
    };

    struct llama_opt_params lopt_params;
    memset(&lopt_params, 0, sizeof(lopt_params));
    lopt_params.n_ctx_train     = TRAIN_CTX;
    lopt_params.param_filter    = llama_opt_param_filter_all;              // train all weights
    lopt_params.param_filter_ud = nullptr;
    lopt_params.optimizer_type  = GGML_OPT_OPTIMIZER_TYPE_ADAMW;
    lopt_params.get_opt_pars    = ggml_opt_get_constant_optimizer_params;  // built-in constant-LR callback
    lopt_params.get_opt_pars_ud = &s_opt_pars;                            // cast back to ggml_opt_optimizer_params

    llama_opt_init(train_ctx_ptr, model, lopt_params);

    // ── 6. Training loop ──────────────────────────────────────────────────────
    ggml_opt_result_t result_train = ggml_opt_result_init();

    for (int ep = 0; ep < (int)epochs; ++ep) {
        ggml_opt_result_reset(result_train);

        // idata_split = ndata: use all data for training (no validation split)
        llama_opt_epoch(train_ctx_ptr, opt_dataset,
                        result_train, /*result_eval=*/nullptr,
                        ndata,
                        /*callback_train=*/nullptr,
                        /*callback_eval=*/nullptr);

        double loss = 0.0;
        ggml_opt_result_loss(result_train, &loss, /*unc=*/nullptr);
        LOGI("nativeTrainLora: epoch %d/%d — loss=%.4f", ep + 1, (int)epochs, loss);
    }

    ggml_opt_result_free(result_train);
    ggml_opt_dataset_free(opt_dataset);

    // ── 7. Save GGUF checkpoint ───────────────────────────────────────────────
    // Writes all weight tensors (now fine-tuned in-place) into a new GGUF file.
    // LlamaEngine.loadLora() detects GGUF magic ("GGUF") and reloads this as
    // the base model for the next inference session.
    bool saved = false;
    try {
        llama_model_save_to_file(model, output_path);
        saved = true;
        LOGI("nativeTrainLora: GGUF checkpoint saved → %s", output_path);
    } catch (...) {
        LOGE("nativeTrainLora: failed to save GGUF checkpoint");
    }

    // ── 8. Cleanup ────────────────────────────────────────────────────────────
    llama_free(train_ctx_ptr);
    llama_model_free(model);

    env->ReleaseStringUTFChars(model_path_jstr,   model_path);
    env->ReleaseStringUTFChars(dataset_path_jstr, dataset_path);
    env->ReleaseStringUTFChars(output_path_jstr,  output_path);

    return saved ? JNI_TRUE : JNI_FALSE;

#else
    // LLAMA_HAS_TRAINING not defined — should not happen now that CMakeLists.txt
    // has add_compile_definitions(LLAMA_HAS_TRAINING).
    (void)env; (void)model_path_jstr; (void)dataset_path_jstr;
    (void)output_path_jstr; (void)rank; (void)epochs;
    LOGI("nativeTrainLora: LLAMA_HAS_TRAINING not set — returning false");
    return JNI_FALSE;
#endif
}

} // extern "C"
