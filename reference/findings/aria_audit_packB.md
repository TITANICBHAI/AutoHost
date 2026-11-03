# ARIA audit and licence register — from the second planning pack

> **Provenance (merge pass, 2026-11-03).** Copied verbatim from the second planning pack's `docs/REUSE_MATRIX.md` (§A and §E). It was written against that pack's own snapshots (kept under `archive/pack-B/reference/`). Task, ADR and R-numbers in this note are **that pack's**, not this tracker's. Where it disagrees with `docs/REUSE_MATRIX.md`, the matrix wins. ARIA is locked (D-17): never copy code on the strength of this note. UNVERIFIED here — nothing was built.

## A. ARIA audit (implemented / stubbed / buildable / tested / verified)
Counts from `grep`/`wc` on the archive. *Buildable* and *verified* are **Unknown/No** everywhere because no build or run was possible.

| Subsystem (ARIA paths under `app/src/main/kotlin/com/ariaagent/mobile/`) | Implemented | Stubbed | Buildable | Tested | Verified | Notes |
|---|---|---|---|---|---|---|
| Accessibility service (`system/accessibility/AgentAccessibilityService.kt`, 357 lines) and gestures (`system/actions/GestureEngine.kt`, 266) | Yes | No markers | Unknown (1 internal dependency) | No | No | Idle tree rebuilds, active window only, interactable nodes only, `#N` ids without staleness check; taps always by coordinates; gesture waits can hang if the service is gone; swipe maths multiplies absolute coordinates |
| Screen capture (`system/screen/ScreenCaptureService.kt`, 200) | Yes | No markers | Unknown (no internal dependencies) | No | No | MediaProjection + ImageReader; registers the Android 14 callback |
| Native inference (`app/src/main/cpp`: JNI 968 lines C++, CMake 233) | Yes (bridge code) | Kotlin side: yes | **No** | No | **No** | `cpp/llama.cpp` is an **empty directory** and the archive has no `.gitmodules`; CI asks for `submodules: recursive` but nothing defines them. ARIA's `reference/aria/docs/ARIA_REALITY_CHECK.md` says the library is physically present: not true of this archive. Vulkan/OpenCL forced OFF; arm64-v8a only |
| Kotlin inference wrapper (`core/ai/LlamaEngine.kt`, 490) | Yes | **Yes**: returns a fabricated `Click #1` action when JNI is missing (14 stub/fake markers) | Unknown | No | No | Must never be carried over (ADR-090) |
| Model catalog, download, storage (`core/ai/ModelCatalog.kt`, `ModelManager.kt`, `ModelDownloadService.kt`) | Yes | 1 marker | Unknown (`ModelDownloadService` needs `AgentEventBus` and a UI class) | No | No | Hugging Face downloads with range-resume; catalog lists third-party models with differing licences |
| Agent loop (`core/agent/AgentLoop.kt`, 1082 lines, plus `TaskDecomposer`, `AppSkillRegistry`, `GameLoop`) | Yes (singleton) | No markers in the loop itself | Unknown | No | No | Mixes RL, game mode, memory, vision and user-instruction injection in one global object |
| Vision and segmentation (`core/ai/VisionEngine.kt`, `Sam2Engine.kt`, `MlcEngine.kt`, `core/perception/ObjectDetectorEngine.kt`) | Code present | `MlcEngine` 3 markers | **No** (needs the missing llama.cpp `mtmd` and extra native libraries) | No | No | Not needed for the first slices |
| OCR (`core/ocr/OcrEngine.kt`, 55 lines) | Yes | 1 marker | Unknown | No | No | ML Kit text recognition; Google terms to be verified (P0-40) |
| RL, IRL, LoRA, embeddings, experience and replay stores (`core/rl/**`, `core/memory/**`) | Yes (large) | `LoraTrainer` 12 markers | Unknown | No | No | Continuous on-device learning: contrary to the low-CPU goal |
| Orchestration (`core/orchestration/**`, 15 files) | Yes | No | Unknown | No | No | Component registry, circuit breaker, problem-solving broker; README says it is ported from earlier "donor" projects (provenance unknown) |
| Compose UI (`ui/screens/*`, `ui/viewmodel/AgentViewModel.kt` 2635 lines) | Yes | Not assessed | Unknown | No | No | `reference/aria/docs/ui-backend-audit.md` claims wiring; self-reported and unverified. Agent-centric screens, not AutoHost's S01–S20 |
| Build system (`build.gradle`, `app/build.gradle`, `settings.gradle`, `.github/workflows`) | Yes | – | **No as shipped** | – | No | Hard-coded `C:/Users/DELL/...` Python and Ninja paths; React Native/Expo scaffolding; Kotlin 2.0.21, AGP 8.8.0, NDK 27.1.12297006, Compose BOM 2024.09.00 |
| Tests | – | – | – | **None found** | – | No `*Test*` files or test source sets in the archive |
| Documentation | Present | – | – | – | – | No root `README.md` (the brief lists one); `FIREBASE_STUDIO.md` is the nearest overview. **No LICENSE/NOTICE file at all** |

## E. Licence and provenance register (first pass; resolution tracked by P0-40)
| Material | Status | Consequence |
|---|---|---|
| AutoHost code | Apache-2.0 (ADR-021, `LICENSE`) | – |
| **ARIA code** | **No licence or notice file in the archive** (default: all rights reserved). `FindOpenCL.cmake` and the Windows paths in `app/build.gradle` suggest it is the owner's own project, which is not a licence grant | Owner must confirm authorship and license the staged files under Apache-2.0 (D-19), or the ARIA-derived rows are rewritten clean-room. Until then staged ARIA files stay in `imported/`, locked: `tools/check_docs.py` refuses an `integrated` ARIA row while D-19 says UNCONFIRMED |
| ARIA "donor" orchestration code | README says it is ported from earlier Java projects; origin unknown | Excluded (R-24) |
| ClosePaw code | Apache-2.0, `NOTICE` present | Keep file headers, record each adapted file in `THIRD_PARTY_NOTICES.md`, mark changes, no trademark use |
| ClosePaw dependencies | 129 Apache / 6 MIT / Leap proprietary / BouncyCastle, EdDSA, others | Leap excluded; others only if the adapted file needs them |
| Third-party models offered by ARIA's catalog | Per-model terms (Llama, Gemma, Qwen and others); not verified (no network) | Never bundled; licence shown before download (X-09) |
| ML Kit, MediaPipe, ONNX Runtime | Not verified; ML Kit is distributed under Google's own terms | Decide in P0-40 before Phase 10 |
| android-agent | **No licence file** | Clean-room ideas only; nothing copied or staged; owner may grant a licence if they want code (Open question 4) |
| OpenDroid | Apache-2.0 (`LICENSE`, OpenDroid header) | Adaptable with notices; nothing staged yet (R-28) |
| Sanna | MIT (declared in the README) | Ideas only (React Native stack) |
| llama.cpp | MIT (upstream); **absent from the ARIA archive** | Pin a commit and record it in `docs/VERSIONS.md` (P0-41) |
