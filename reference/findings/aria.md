# ARIA — study notes (v4 planning pass)
> **Status:** working notes from *static reading* of `aria-ai-cpu-only-main.zip` by the planning agent (2026-10/11). Nothing was built or run. Line counts and paths refer to that archive. Statements tagged UNVERIFIED were not confirmed. **Licence:** NO LICENSE; owner UNVERIFIED — anti-reference
> These notes are the evidence behind `docs/REUSE_MATRIX.md` rows; they are not instructions and contain no copied source.

# ARIA first-hand code findings (read in full by me; line numbers are from the pristine extract)
Root: /home/claude/work/src/aria/aria-ai-cpu-only-main/app/src/main/kotlin/com/ariaagent/mobile/
Status legend: V = verified by reading code (static only; NOTHING was compiled or run), I = inference, U = unverified

## system/accessibility/AgentAccessibilityService.kt (356 lines) — READ FULL
- V singleton `instance` (companion), @Volatile cachedTree String, ConcurrentHashMap<String,AccessibilityNodeInfo> nodeRegistry keyed "#N".
- V Tree lists ONLY nodes that are clickable/scrollable/editable/focusable/longClickable (line 260-285). Non-interactive text nodes are skipped (OCR is the only source for them).
- V No password check (`isPassword` never read), no isVisibleToUser filter, no depth/node cap, only rootInActiveWindow (single window), no flagRetrieveInteractiveWindows.
- V Uses AccessibilityNodeInfo.obtain()/recycle() (deprecated API 33+; no-ops there). Registry holds copies; no refresh() before use -> stale-node risk (I).
- V rebuild: WINDOW_STATE_CHANGED -> immediate post; CONTENT_CHANGED -> 150 ms debounce; runs on main looper.
- V Gesture durations: tap 50 ms, long-press 500 ms, swipe 300 ms. `dispatchGesture(gesture, callback, null)`; return value ignored.
- V `dispatchTap()` etc. are `instance?.dispatchTapAt(..)`: if instance == null the callback is NEVER invoked.
- V IME detection = hard-coded 7 package names + className contains "InputMethod" (brittle).
- V TYPE_VIEW_CLICKED is reported as "user_tapped" on the assumption ARIA's own gestures never produce it (comment line 182-185). I: this assumption is almost certainly wrong because View.performClick() emits TYPE_VIEW_CLICKED for any touch, injected or real (conf ~0.85).
- V getScreenSize() falls back to Pair(1080,2400) if service not connected.

## system/actions/GestureEngine.kt (265 lines) — READ FULL
- V JSON tool names: click|tap, swipe, type|typetext, scroll, longpress, back, tapxy, swipexy. (Wait/Done handled in AgentLoop, not here.)
- V tap/swipe/longPress/tapXY/swipeXY use `suspendCancellableCoroutine` waiting for GestureResultCallback with NO timeout. Combined with the null-instance / dispatchGesture==false cases above => coroutine can hang forever (I, conf 0.85).
- V ARIA acts by GESTURE at node-centre coordinates; it never calls performAction(ACTION_CLICK) (no node-first strategy; ClosePaw does node-first with gesture fallback).
- V swipe(node,dir) geometry bug: multiplies ABSOLUTE coordinates (rect.bottom*0.8, rect.top*1.2, rect.right*0.8, rect.left*1.2) instead of interpolating inside the rect => wrong/very short swipes for nodes not at origin (e.g. left=600,right=1000 -> 800->720).
- V Direction semantics inconsistent: swipe("up") moves the finger up; scroll("up") = ACTION_SCROLL_BACKWARD (content moves the other way).
- V type() = ACTION_SET_TEXT (replaces whole text), requires node.isEditable, no focus step, no IME fallback, no password guard.
- V NO policy/permission/denylist layer anywhere in the action path (grep to confirm for whole tree still TODO).

## core/perception/ScreenObserver.kt (139 lines) — READ FULL
- V capture(): a11y tree string + MediaProjection bitmap (ScreenCaptureService.captureLatest) + ML Kit OCR on every step when a bitmap exists (CPU cost every step).
- V screenHash = SHA-256 over pkg|activity|a11yTree.take(500)|ocr.take(200), truncated to 8 bytes: changes beyond first 500 chars are invisible.

## core/agent/AgentLoop.kt (1081 lines) — READ FULL (1-1081)
- V `object AgentLoop` (global singleton, global mutable `state`, own CoroutineScope(Default+SupervisorJob)).
- V MAX_STEPS=200 and MAX_STEPS_PER_SUBTASK=30 (lines 94-95) => the "200/30 caps" claim is TRUE.
- V Loop = observe(a11y+OCR+bitmap) -> game detect -> detector -> embedding retrieve -> labels -> thermal check -> app knowledge -> VLM description -> SAM2 -> user note -> PromptBuilder -> LlamaEngine.infer(maxTokens=200) -> parse -> stuck -> act -> pixel verify -> reward -> store (SQLite) -> replay -> progress.txt -> emit -> delay(800).
- V Stop/pause: stop() = loopJob.cancel(); the loop body `catch (e: Exception)` also catches CancellationException => after Stop the catch sets status=ERROR and calls recordAndChain(), which can dequeue and START THE NEXT QUEUED TASK (line 909-918, 974-1078). I (conf ~0.85): Stop does not reliably stop; pause() has no resume (just cancels).
- V `start()` launches a new AgentEventBus.flow.collect{} coroutine on every call and never cancels it (lines 144-151) => collector leak.
- V Unchanged-screen gate (line 371-374) `continue`s with delay(1200) WITHOUT incrementing stepCount or any timeout. Also the empty-snapshot branch (330-333). => unbounded busy-wait on a static screen (no wall-clock budget).
- V Stuck detector (3 hint / 5 force Back / 8 abort, debounced by 2 same hashes) compares currentHash == lastScreenHash, but the change-gate above already `continue`d on unchanged hashes in normal mode => detector is only reachable in learnOnly mode (I, conf ~0.8; static reading).
- V Done/Wait detection by String.contains("\"tool\":\"Done\"") — whitespace-sensitive; `{"tool": "Done"}` is NOT recognised (lines 706-707). Action extraction uses regexes (extractTool/NodeId) not a JSON parser (954-963).
- V parseAction() = substring from first '{' to last '}' (PromptBuilder 252-262), no schema validation; on garbage returns a Wait action (so garbage never aborts).
- V User note and previous-session log are interpolated into JSON strings without escaping (lines 203, 586).
- V Prompt injection: OCR + node text from third-party apps go straight into the prompt with no untrusted-content delimiting; no policy engine.
- V PixelVerifier: success = gestureSuccess && pixel diff of acted node region (line 784) — a click that opens a dialog/navigates may or may not change that region; reward is noisy.
- V Per-step heavy extras: ONNX MiniLM retrieval, ML Kit OCR, MediaPipe detector, SmolVLM description, MobileSAM, SQLite + progress.txt + replay writes. None needed for AutoHost v1.

## core/ai/LlamaEngine.kt (489 lines) — READ FULL
- V `object LlamaEngine`; `System.loadLibrary("llama-jni")` in init; on UnsatisfiedLinkError -> STUB MODE.
- V STUB: load() sets modelHandle=1, contextHandle=1, memoryMb=1700.0; infer() returns {"tool":"Click","node_id":"#1","reason":"stub inference — llama.cpp not compiled"} and lastToksPerSec=11.5 (fabricated numbers). In a real run the stub would CLICK node #1. => a "succeeding" stub is a safety hazard; any AutoHost provider must FAIL CLOSED.
- V Comment claims "CPU-only ~2–5 tok/s; ~8–15 tok/s with Vulkan GPU offload" for Galaxy M31 (Exynos 9611) — UNVERIFIED (never measured here; CMake turns Vulkan/OpenCL off per earlier audit).
- V Defaults inconsistent: load() -> nGpuLayers=0/"cpu" (FLAWS.md #2 fix); loadVision()/loadUnified() -> nGpuLayers=32/"opencl".
- V modelHandle/contextHandle are plain vars (not @Volatile); unload() uses runBlocking+withTimeoutOrNull(10 s).
- V JNI surface (private external): nativeLoadModel, nativeCreateContext, nativeRunInference(ctx,prompt,maxTokens,temperature,TokenCallback), nativeFreeModel, nativeFreeContext, nativeGetToksPerSec, nativeGetMemoryMb, nativeInitVision, nativeFreeVision, nativeRunVisionInference, nativeLoadLora.
- V Single engineMutex serialises inference; prompts are plain Llama-3 template strings built in Kotlin.

## core/ai/PromptBuilder.kt (263 lines) — READ FULL
- V Llama-3 chat template, SYSTEM_PROMPT defines 10 JSON tools (Click, Type, Swipe, Scroll, LongPress, TapXY, SwipeXY, Back, Wait, Done). Tree trimmed by char budget = (ctx-700)*4*0.85 clamp [1500,12000]; default ctx 2048 => ~4.6k chars for everything incl. node tree.
- V Doc comment says ctx=4096 elsewhere while LlamaEngine default is 2048 (internal inconsistency).

---

# ARIA findings, part 2 (written after the 2nd compaction)
Root: aria-ai-cpu-only-main/app/src/main/. Static reading only. Part 1 is aria_findings.md (accessibility, gesture engine, AgentLoop, LlamaEngine, PromptBuilder).

- cpp/CMakeLists.txt (232, read fully):
  - GGML_VULKAN and GGML_OPENCL are FORCED OFF (lines 43-44) although the comments say both are compiled in.
  - C:/VulkanSDK/... paths appear in the search lists (lines 92-93, 124-125).
  - It links llama, llama-common and mtmd, which needs llama.cpp b6800 or later.
  - The JNI uses the NEW llama_set_adapters_lora and llama_opt_* APIs with NO pinned llama.cpp version.
  - add_compile_definitions(LLAMA_HAS_TRAINING) is set.
  - Flags: -O3 -march=armv8-a -ffast-math -fno-finite-math-only -funroll-loops.
- cpp/llama_jni.cpp (967 lines; lines 1-345 read; 345-967 (LoRA, vision, training) UNREAD):
  - Global statics g_model, g_ctx, g_lora_adapter, g_vision_ctx; nativeCreateContext overwrites g_ctx.
  - g_memory_mb.store(1700.0) is a CONSTANT, not a measurement.
  - A comment claims "mmap (never fully in RAM)" but the code loads models up to 2 GB into the heap.
  - ORDER BUG at lines 253-257: llama_get_model(ctx) is dereferenced BEFORE the null check, and Kotlin's isLoaded() checks only modelHandle, so a failed context creation leads to a native crash.
  - llama_decode return values are never checked (lines 290, 332).
  - NewStringUTF is called on per-token pieces, giving invalid modified-UTF-8 for partial multibyte tokens.
  - No pending-exception check after the Kotlin callback.
  - No abort/cancel for native generation.
  - llama_memory_clear on every call, so no prefix/KV reuse.
  - Sampling is top_p 0.9 + temp + dist with NO grammar.
  - Threads are hard-coded to 6/8 (Exynos 9611).
  - GetStringUTFChars is not null-checked.
- app/build.gradle:
  - Lines 90-91 contain HARD-CODED WINDOWS PATHS: -DPython3_EXECUTABLE=C:/Users/DELL/AppData/Local/Programs/Python/Python312/python.exe and -DCMAKE_MAKE_PROGRAM=C:/Users/DELL/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe.
  - ANDROID_PLATFORM=android-29; abiFilters arm64-v8a only; release minifyEnabled true.
  - Toolchain: AGP 8.8.0, Kotlin 2.0.21, SDK 35, minSdk 29, NDK 27.1.12297006, CMake 3.22.1, Compose BOM 2024.09.00.
  - Deps: OkHttp 4.12.0, ML Kit text-recognition 16.0.1, ONNX Runtime 1.19.2, MediaPipe tasks-vision 0.10.14.
- app/src/main/cpp/llama.cpp/ is EMPTY and there is NO .gitmodules. .github/workflows/build-android.yml uses `submodules: recursive` (does nothing without .gitmodules), installs libvulkan-dev/glslang and runs ./gradlew assembleDebug. On ubuntu the Windows ninja path would fail (inference; not run).
- docs/ARIA_REALITY_CHECK.md claims llama.cpp is "physically present" at android/app/src/main/cpp/llama.cpp/, which is FALSE for this snapshot. It also says llama_jni.cpp has 823 lines; it has 967.
- core/ai/ModelCatalog.kt (271): 10 Hugging Face models (SmolVLM-256M/500M, Moondream2, Qwen2.5-VL-3B, MiniCPM-V-2.6, Llama-3.2 1B/3B, Gemma-3 1B/4B, Qwen2.5-1.5B). Only expectedSizeBytes (a minimum size); NO pinned SHA-256, NO licence-acceptance flow. ModelManager.sha256() exists (line 197) but is NEVER CALLED (grep).
- core/monitoring/LocalDeviceServer.kt (171): UNAUTHENTICATED HTTP ServerSocket(8765) bound to all interfaces, Access-Control-Allow-Origin: *, serves status/activity/RL data, auto-started by AgentViewModel.init. DO NOT REUSE.
- core/config/SafetyConfigStore.kt (75) + AgentViewModel.kt (2634): SafetyConfig (globalKillActive, confirmMode, blockedPackages, allowlistMode, allowedPackages) is only persisted and edited via the ViewModel/UI. It is NOT referenced anywhere in core/ or system/ (grep), so the Safety screen is NOT enforced (~0.9 confidence).
- Manifest: INTERNET, FOREGROUND_SERVICE (+DATA_SYNC, MEDIA_PROJECTION, SPECIAL_USE), POST_NOTIFICATIONS, WAKE_LOCK, SYSTEM_ALERT_WINDOW; allowBackup=false; largeHeap=true; exported accessibility service.
- ARIA accessibility config: events typeWindowStateChanged|typeWindowContentChanged|typeViewFocused|typeViewClicked; flags flagDefault|flagReportViewIds|flagRequestEnhancedWebAccessibility; canPerformGestures, canRetrieveWindowContent; NO canTakeScreenshot.
- Other: zero tests; ~41.2k lines under app/src including ~9.5k lines of OpenCL headers; 15 TODO/FIXME; no README; Expo residue; PUSH_LOG.md names github.com/TITANICBHAI/Ai-android (ownership UNVERIFIED).
- Still unread: llama_jni.cpp 345-967, ModelDownloadService, ThermalGuard, AgentForegroundService, MonitoringPusher.
