# REUSE_MATRIX — what AutoHost takes from ARIA and ClosePaw
> **Status (2026-10-27): static audit.** Everything below comes from reading the supplied archives. Nothing was compiled, installed or run: the planning sandbox has a JRE but no JDK, Gradle, Android SDK, NDK or network (`docs/VALIDATION_REPORT.md`). Anything not backed by a build or a device run is marked *unverified*. ARIA's and ClosePaw's own documents are treated as claims, not evidence.

**Roles (ADR-087).** AutoHost = product and execution-architecture source of truth. ARIA = Android implementation source *where its code proves reusable*. ClosePaw = selective implementation reference, never the base application. One run lifecycle, one capability broker, one action path (ADR-089).

**Dispositions** (exactly these five): **Reuse unchanged** (file copied as-is, only attribution added) · **Adapt** (staged verbatim, then changed to fit AutoHost) · **Rewrite** (written new; the source is reference) · **Reference only** (stays in `reference/`, nothing staged) · **Defer** (revisit in the named phase).
**Evidence ladder** (`docs/PROGRESS.md`): imported → written → compiled → unit-tested → integration-tested → device-verified. Staging a file is *imported*, never *integrated*.
**Layout.** Pristine snapshots: `reference/aria/`, `reference/closepaw/` (never built). Staged copies: `<module>/imported/<repo>/<original repo path>` (never built, byte-identical, hash-checked by `tools/check_docs.py`). Adapted code lives in `src/main/kotlin/app/autohost/…` under the AutoHost package. Path map: `docs/migration-manifest.tsv`, `docs/MIGRATION_NOTES.md`.

## 0. Verdict
| Source | Verdict |
|---|---|
| AutoHost | **State: all eleven phases are written and almost nothing is verified** (`AutoHost-main.zip`, 2026-10-27: 346 shipped Kotlin files (about 44.7K lines), no model/agent/tier code yet). Keeps the product, capability catalog, broker, SDK contracts, gates and roadmap. Changes: an in-process Kotlin **agent runtime** and **model-provider** layer are added (plan §5.9, §7.11, Phase 12); Python is demoted to the user-script runtime and started lazily (§5.8). |
| ARIA | Not usable as a base application: no tests, no licence file, `llama.cpp` missing from the archive so native inference cannot be built or verified, Windows-only build paths, RN/Expo remnants, global singletons. Since AutoHost already wrote its own accessibility, projection and UI modules, ARIA now contributes only the **local-inference path** (JNI bridge, wrapper, model download, thermal guard; conditional on S6 and on the licence) and cross-check findings for the written code. |
| ClosePaw | Better engineered (376 main Kotlin files, 210 unit-test and 27 instrumented-test files, Apache-2.0) but minSdk 31, cloud-LLM-centric and tightly layered. Not the base; **selective adaptation** of its OpenAI-compatible clients, sensitive-app policy, trace recorder, overlay/capsule UI and design tokens; its tested action-chain scenarios become test cases for AutoHost's written code; its design documents are reference. |
| android-agent, OpenDroid, Sanna | Reviewed 2026-10-27 (not in this pack). `android-agent` (no licence file): policy layers, routing, scripts, eval baselines as clean-room ideas. OpenDroid (Apache-2.0): a provider catalog, model-integrity checks and a LiteRT-LM on-device route as candidates after the first provider works. Sanna (MIT, React Native): event-triggered sub-agents and a skill validator as ideas only. |

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

## B. ClosePaw assessment (package `ai.closepaw`, Apache-2.0)
Main sources under `app/src/main/kotlin/ai/closepaw/`; tests under `app/src/test` and `app/src/androidTest`. "Depends on" counts import statements into other ClosePaw packages (a coupling measure, not a build graph).

| Package | Main files | Main lines | Test files | Depends on (top imports) | Disposition |
|---|---|---|---|---|---|
| `agent` | 29 | 3726 | 30 | history 18, tool 13, llm 11, protocol 10 | Rewrite as `:runtime:agent`; two helpers staged (R-11) |
| `app` | 17 | 3864 | 7 | ui 37, protocol 34, llm 19, auth 7 | Reference only |
| `auth` | 5 | 826 | 3 | llm 2, BuildConfig 1 | Excluded (Codex/ChatGPT sign-in) |
| `browser` | 38 | 6225 | 32 | trace 3 | Defer (R-21) |
| `debug` | 3 | 743 | 0 | platform 9, tool 6, app 4, model 2 | Reference only |
| `history` | 16 | 2474 | 9 | ui 7, llm 2, protocol 2 | Reference (R-15) |
| `llm` | 26 | 4150 | 20 | auth 3, BuildConfig 2, app 1 | Adapt cloud clients (R-10); Leap/Codex files excluded |
| `memory` | 3 | 387 | 2 | – | Defer (AutoHost `store`/`state` cover it) |
| `model` | 1 | 131 | 1 | – | Shared types pulled in by closure only |
| `onboarding` | 8 | 1220 | 7 | protocol 9, app 7, auth 7, llm 6 | Reference only |
| `perception` | 6 | 837 | 3 | model 9, util 2 | Reference only (R-16) |
| `platform` | 32 | 5131 | 12 | model 13, trace 11, perception 8, protocol 7 | Gestures, node actions, screenshot adapt (R-02–R-04); virtual display defer (R-21) |
| `protocol` | 27 | 728 | 0 | llm 1, perception 1 | Shared types pulled in by closure only |
| `session` | 12 | 2427 | 15 | tool 29, history 20, agent 19, protocol 19 | Reference only (coupled to tool/history/agent) |
| `termux` | 4 | 859 | 4 | R 1 | Defer (R-21) |
| `tool` | 41 | 6062 | 31 | platform 29, model 23, protocol 14, agent 7 | Action executors adapt (R-02); policy adapt (R-13); registry reference (R-12) |
| `trace` | 11 | 1243 | 3 | agent 7, history 3, protocol 3, tool 3 | Adapt recorder and redactor (R-14) |
| `ui` | 96 | 16096 | 38 | protocol 50, llm 23, onboarding 19, app 14 | Theme and overlay adapt (R-17, R-18); remaining screens reference (R-19) |
| `util` | 1 | 27 | 0 | – | Pulled in by closure only |

**Android floor.** ClosePaw builds with `minSdk = 31`, `targetSdk = 36`; a build comment says 31 is required by the LiquidAI Leap SDK, so lowering it needs the Leap dependency removed and every API 30+ call checked. AutoHost's floor is 29 (ADR-002): staged files are compiled against minSdk 29 and linted (`NewApi`) before integration. Files with explicit version checks: `platform/AccessibilityPlatform.kt` (S), `platform/AccessibilityScreenshotCapturer.kt` (R, UPSIDE_DOWN_CAKE), `platform/NodeActionPerformer.kt` (R), `platform/virtualdisplay/*` (S, TIRAMISU), `termux/*` (O, TIRAMISU), `app/AgentService*.kt` (TIRAMISU).

**Dependency licences** (from `app/src/main/assets/open_source_licenses.json`, 140 entries): 129 Apache-2.0, 6 MIT, 5 other. Not Apache-2.0:

| Dependency | Version | Licence as reported |
|---|---|---|
| Bouncy Castle ASN.1 Extension and Utility APIs | 1.84 | Bouncy Castle Licence |
| Bouncy Castle PKIX, CMS, EAC, TSP, PKCS, OCSP, CMP, and CRMF APIs | 1.84 | Bouncy Castle Licence |
| Bouncy Castle Provider | 1.84 | Bouncy Castle Licence |
| Compose Icons Lucide | 2.2.1 | MIT License |
| EdDSA-Java | 0.3.0 | CC0 1.0 Universal |
| leap-sdk | 0.9.2 | Leap Terms of Use |
| Shizuku API - aidl | 13.1.5 | MIT License |
| Shizuku API - API | 13.1.5 | MIT License |
| Shizuku API - provider | 13.1.5 | MIT License |
| Shizuku API - shared | 13.1.5 | MIT License |
| SLF4J API Module | 2.0.17 | MIT |

Findings: (1) **`ai.liquid.leap:leap-sdk` is proprietary ("Leap Terms of Use") and is excluded.** (2) ClosePaw's `NOTICE` lists Shizuku API/Provider as Apache-2.0 and Lucide as ISC, while the generated report says MIT for both: verify upstream before redistributing either (P0-40). (3) Fonts (Fraunces, Geist, JetBrains Mono) are third-party; `FONT_ATTRIBUTION.md` is staged with them (R-18) and their licence texts must be confirmed. (4) Apache-2.0 requires keeping notices and marking changed files, and grants no trademark rights: the ClosePaw name and paw glyph are not carried over (`StatusPawGlyph.kt` is not staged).

## C. Component reuse matrix

| ID | Component | Existing source | Disposition | AutoHost destination | Evidence required |
|---|---|---|---|---|---|
| R-01 | Accessibility service shell (config, lifecycle, event filter) | ARIA `…/system/accessibility/AgentAccessibilityService.kt`; ARIA `res/xml/accessibility_service_config.xml`; ClosePaw `…/platform/AccessibilityPlatform.kt` (reference) | **Reference only** | :capabilities:a11y (already written) | DEVICE during verification: G1-b/G1-c, G3-f wedged-read test, G1-h idle-cost test (no tree work while no run is active). |
| R-02 | Action executors and fallback chains (click, long-press, scroll, swipe, type) | ClosePaw `…/tool/action/{PointActionExecutorCore, ClickExecutor, TypeExecutor}` (reference) | **Reference only** | :capabilities:a11y (already written) | UNIT: ClosePaw scenarios ported to fakes against AutoHost's `Actions` |
| R-03 | Gesture injection with bounded callbacks | ClosePaw `…/platform/{AccessibilityGestureInjector, BoundedCallback}` (reference) | **Reference only** | :capabilities:a11y (already written) | UNIT: timeout and disconnect tests |
| R-04 | Accessibility screenshot (API 30+) | ClosePaw `…/platform/{AccessibilityScreenshotCapturer, BitmapUtils}` (reference) | **Reference only** | :capabilities:a11y (already written) | DEVICE (emulator API 30+): G3-h. |
| R-05 | MediaProjection capture service | ARIA `…/system/screen/ScreenCaptureService.kt` (reference) | **Reference only** | :capabilities:projection (already written) | COMPILED then DEVICE: G3-e (20 unattended screenshots, also after killing the main process). |
| R-06 | llama.cpp JNI bridge and native build | ARIA `cpp/{llama_jni.cpp, CMakeLists.txt, FindOpenCL.cmake, aria_crash_handler.cpp}` | **Adapt** (conditional) | `native/llama-jni` + `:ai:local` | COMPILED: arm64-v8a build log with the pinned llama.cpp commit recorded in `docs/VERSIONS.md` |
| R-07 | LlamaEngine Kotlin wrapper | ARIA `…/core/ai/LlamaEngine.kt` | **Adapt** | :ai:local | UNIT: contract test that an unavailable engine returns `CAPABILITY_UNAVAILABLE` and never an action |
| R-08 | Model catalog, download and storage management | ARIA `…/core/ai/{ModelManager, ModelDownloadService, ModelCatalog}` | **Adapt** | :ai:local | UNIT: resume/checksum tests against a fake HTTP server |
| R-09 | Thermal and sustained-performance guard for inference | ARIA `…/core/system/{ThermalGuard, SustainedPerformanceManager, HardwareMonitor}` | **Adapt** | :ai:local | UNIT: policy tests on fake thermal inputs |
| R-10 | Cloud LLM clients (OpenAI-compatible) | ClosePaw `…/llm/*` (15 files) | **Adapt** | :ai:cloud | UNIT: ported client, retry and classifier tests |
| R-11 | Turn loop building blocks (error classification, loop detection) | ClosePaw `…/agent/TurnErrorClassifier.kt`; ClosePaw `…/agent/cognition/policy/LoopDetectionPolicy.kt` | **Rewrite** | :runtime:agent | UNIT: ported helper tests |
| R-12 | Tool registry, router and spec | ClosePaw `…/tool/{ToolSpec, ToolRegistry, ToolRouter}` (reference) | **Reference only** | :runtime:agent (tool schemas generated from specs) | Not applicable (no code adopted) |
| R-13 | Sensitive-app tier policy (banking/authenticator/wallet hard-block) | ClosePaw `…/tool/{AppClassifier, PolicyEngine}`; ClosePaw `…/protocol/AppTier.kt`; ClosePaw `assets/security/app_tiers.json` | **Adapt** | :core:model (policy) + broker interceptor | UNIT: ported `AppClassifier*Test` and `PolicyEngineTest` tables |
| R-14 | Run traces and evidence recorder | ClosePaw `…/trace/{TraceRecorder, FileTraceRecorder, TraceModels, CognitionTraceRedactor, A11yTreeDumper}` | **Adapt** | :runtime:agent (agent traces); the `.aarun` export in `:core:engine` is already written | UNIT: ported recorder and redactor tests plus new tests for queue saturation, flush deadline and crash durability (ClosePaw's guarantees are not trusted until tested) |
| R-15 | History, context compaction and prompt assets | ClosePaw `doc/main/agent/{overview.md, planning.md, memory.md}` (reference) | **Reference only** | :runtime:agent | Not applicable. |
| R-16 | Perception shaping for LLM input (compact UI text) | ClosePaw `…/perception/Perceptor.kt`; ARIA `…/core/perception/ScreenObserver.kt` (reference) | **Reference only** | :core:model `CompactDump` | UNIT: golden vectors |
| R-17 | Floating overlay and status capsule | ClosePaw `…/ui/capsule/{CapsuleBinding, NavAction}`; ClosePaw `…/ui/overlay/CapsuleStateHolder.kt`; ClosePaw `…/ui/overlay/model/{CapsuleContext, CapsuleMode, CapsuleRenderSpec, GlowState}`; ClosePaw `…/ui/overlay/compose/*` (9 files); ClosePaw `…/ui/overlay/visualizer/ActionVisualizerManager.kt`; ClosePaw `…/ui/capsule/surface/{CapsuleControlBar, CapsuleInputBar, SmartCapsuleHostLayout, SmartCapsuleSurface, StatusColors}` | **Adapt** | :app (surfaces; the P7-06 bubble is already written, the agent capsule is new) | UNIT: ported `CapsuleStateHolderTest`, `CapsuleRenderSpecTest` |
| R-18 | Design system (tokens, type, motion) and fonts | ClosePaw `…/ui/theme/*` (9 files); ClosePaw `res/font/{fraunces_italic.ttf, fraunces_regular.ttf, geist_medium.ttf, geist_regular.ttf, jetbrains_mono_medium.ttf, jetbrains_mono_regular.ttf}`; ClosePaw `assets/FONT_ATTRIBUTION.md` | **Adapt** | :core:designsystem | COMPILED then screenshot tests per `docs/UI_SPEC.md` §9; TalkBack and contrast checks (§8). |
| R-19 | Application screens and navigation (ARIA 11 screens, ClosePaw onboarding/settings) | ARIA `…/ui/screens/{ModulesScreen, OnboardingScreen}`; ClosePaw `…/onboarding/` (reference) | **Reference only** | :app, :feature:* | Not applicable. |
| R-20 | Agent memory, skills, app-skills and browser-use skill packs | ClosePaw `assets/app_skills/`; ClosePaw `assets/agent_skills/`; ClosePaw `…/agent/cognition/skills/AgentSkillManager.kt` (reference) | **Defer** | :runtime:agent (later) | Not applicable until P11-04. |
| R-21 | Shell, Termux, Shizuku virtual display, browser CDP, wireless-ADB pairing | ClosePaw `…/browser/cdp/wireless/{AdbPairingClient, Spake25519}`; ClosePaw `…/platform/virtualdisplay/VirtualDisplayPlatform.kt` (reference) | **Defer** | :capabilities:shell, :capabilities:browser | Not applicable until Phase 9. |
| R-22 | OCR and vision (ML Kit, MediaPipe, SmolVLM via mtmd, SAM2, pixel verifier) | ARIA `…/core/ocr/OcrEngine.kt`; ARIA `…/core/perception/ObjectDetectorEngine.kt`; ARIA `…/core/ai/VisionEngine.kt` (reference) | **Reference only** | :capabilities:vision | Not applicable until Phase 10. |
| R-23 | RL, IRL, LoRA training, embeddings, experience/replay stores, game loop | ARIA `…/core/rl/{PolicyNetwork, IrlModule, LoraTrainer}` (reference) | **Reference only** | (none) | Not applicable. |
| R-24 | ARIA orchestration layer and event bus | ARIA `…/core/orchestration/{CentralOrchestrator, ComponentRegistry}`; ARIA `…/core/events/AgentEventBus.kt` (reference) | **Reference only** | (none) | Not applicable. |
| R-25 | ARIA foreground service, chat overlay, logging, monitoring server, build files | ARIA `…/system/AgentForegroundService.kt`; ARIA `…/core/logging/CrashHandler.kt`; ARIA `…/core/monitoring/LocalDeviceServer.kt` (reference) | **Reference only** | (none) | Not applicable. |
| R-26 | Agent evaluation and trace inspection tooling (Python, PC side) | ClosePaw `eval/`; ClosePaw `inspection_tool/`; ClosePaw `doc/main/agent/overview.md` (reference) | **Defer** | `sdk-python/` / `testing/` | Not applicable until P12-13. |
| R-27 | Agent policy layers, approvals, model routing, compaction and agent-authored scripts (android-agent) | android-agent `java/com/foxislam/androidagent/agent/{Approvals, Hooks, Router}` (reference) | **Reference only** | :runtime:agent (policy layers, router, `run_flow` tool, eval baselines) | Not applicable (no code adopted); proven by G12-b/c and the eval baselines (P12-13). |
| R-28 | Multi-provider catalog, model integrity checks and the LiteRT-LM on-device runtime (OpenDroid) | OpenDroid `java/com/opendroid/ai/core/llm/{ProviderCatalog, LLMProviderFactory, ConnectionTest}` (reference) | **Defer** | :ai:cloud (more providers), :ai:local (LiteRT option) | COMPILED then UNIT for any adapted file; DEVICE: tokens/s, RSS and temperature for the chosen local runtime (S6, G12-f). |
| R-29 | Notification and schedule sub-agents, skill validator, learned app hints (Sanna) | Sanna `src/agent/{notification-sub-agent.ts, accessibility-sub-agent.ts, scheduler-headless.ts}` (reference) | **Reference only** | (none) | Not applicable. |

Rows are grouped as Android platform (R-01–R-05), AI and models (R-06–R-10), agent runtime (R-11–R-16), UI (R-17–R-19), deferred (R-20–R-22), excluded features (R-23–R-25), tooling (R-26) and other reference projects reviewed on 2026-10-27 (R-27–R-29; those archives are not part of this pack, paths are inside the owner's zips). Detail per row follows.

#### Android platform

### R-01 — Accessibility service shell (config, lifecycle, event filter)

| | |
|---|---|
| Disposition | **Reference only**: AutoHost already has its own `AutoHostAccessibilityService`, `ReadGate`, `TraversalGuard`, `RootResolver`, `NodeRegistry` and `UiTreeBuilder` (written, unverified); the ARIA shell is a cross-check, nothing is staged |
| Destination | :capabilities:a11y (already written) |
| Existing source | (nothing staged) · reference: ARIA `…/system/accessibility/AgentAccessibilityService.kt`; ARIA `res/xml/accessibility_service_config.xml`; ClosePaw `…/platform/AccessibilityPlatform.kt` |
| Why this disposition | The audit found ARIA's service rebuilds a full text tree on every content change even when idle, keeps a global static registry of `AccessibilityNodeInfo` copies keyed `#N` with no staleness check, reads only the active window and lists only interactable nodes. AutoHost's written code follows plan §5.7 instead. The value left is as a checklist while verifying the written service (P1-22). |
| Dependencies and Android compatibility | n/a (reference) |
| Licence | ARIA (no licence file; locked until D-19 is confirmed, P0-40) |
| Evidence required | DEVICE during verification: G1-b/G1-c, G3-f wedged-read test, G1-h idle-cost test (no tree work while no run is active). |
| Tracker | `P0-42`, `P1-22`, `P1-25` |

### R-02 — Action executors and fallback chains (click, long-press, scroll, swipe, type)

| | |
|---|---|
| Disposition | **Reference only**: AutoHost already has `Actions.kt`, `UiActions.kt` and `SetTextChain.kt` (written, unverified) implementing the same chain; ClosePaw's unit-test scenarios are ported onto them (P3-25) and used to cross-check (P1-23); nothing is staged |
| Destination | :capabilities:a11y (already written) |
| Existing source | (nothing staged) · reference: ClosePaw `…/tool/action/{PointActionExecutorCore, ClickExecutor, TypeExecutor, ScrollExecutor, UiChangeDetector}`; ClosePaw `…/platform/NodeActionPerformer.kt`; ARIA `…/system/actions/GestureEngine.kt` |
| Why this disposition | ClosePaw is the better-tested implementation of the chain the plan specifies (§7.6, L17), so its test scenarios are the cheapest independent oracle for AutoHost's written code. ARIA's `GestureEngine` always taps coordinates and is not a safe reference for behaviour. |
| Dependencies and Android compatibility | ClosePaw was built with minSdk 31; scenarios that touch API 30+ behaviour need an API-29 variant. |
| Licence | Apache-2.0 (ClosePaw) |
| Evidence required | UNIT: ClosePaw scenarios ported to fakes against AutoHost's `Actions`. DEVICE: G3-a fixture suite and G3-b 100-cycle reliability. |
| Tracker | `P1-23`, `P3-10`, `P3-12`, `P3-25` |

### R-03 — Gesture injection with bounded callbacks

| | |
|---|---|
| Disposition | **Reference only**: check that AutoHost's gesture dispatch has the same property — a `dispatchGesture` callback that never fires must not suspend the caller forever, and a service disconnect completes every pending action with a typed failure; nothing is staged |
| Destination | :capabilities:a11y (already written) |
| Existing source | (nothing staged) · reference: ClosePaw `…/platform/{AccessibilityGestureInjector, BoundedCallback}` |
| Why this disposition | ARIA's `GestureEngine` suspends forever when the service instance is gone (no callback, no timeout). The bounded-callback idea is the fix; whether AutoHost's code already has it is a verification question (P1-23). |
| Dependencies and Android compatibility | n/a (reference) |
| Licence | Apache-2.0 (ClosePaw) |
| Evidence required | UNIT: timeout and disconnect tests. DEVICE: G3-f. |
| Tracker | `P1-23` |

### R-04 — Accessibility screenshot (API 30+)

| | |
|---|---|
| Disposition | **Reference only**: AutoHost already has `ScreenshotA11yApi30.kt` and `ScreenshotThrottlePolicy.kt` (written, unverified) |
| Destination | :capabilities:a11y (already written) |
| Existing source | (nothing staged) · reference: ClosePaw `…/platform/{AccessibilityScreenshotCapturer, BitmapUtils}` |
| Why this disposition | Same API-30 path; compare throttling and failure handling while verifying (G3-h on an emulator). |
| Dependencies and Android compatibility | n/a (reference) |
| Licence | Apache-2.0 (ClosePaw) |
| Evidence required | DEVICE (emulator API 30+): G3-h. |
| Tracker | `P3-13` |

### R-05 — MediaProjection capture service

| | |
|---|---|
| Disposition | **Reference only**: AutoHost already has `capabilities/projection` (`ProjectionService`, `ProjectionHolder`, `ProjectionConsentActivity`; written, unverified) |
| Destination | :capabilities:projection (already written) |
| Existing source | (nothing staged) · reference: ARIA `…/system/screen/ScreenCaptureService.kt` |
| Why this disposition | Check that the written service registers the Android 14 callback and uses the right foreground-service type (ADR-020); ARIA's file is a tidy example of both. |
| Dependencies and Android compatibility | n/a (reference) |
| Licence | ARIA (no licence file; locked until D-19 is confirmed, P0-40) |
| Evidence required | COMPILED then DEVICE: G3-e (20 unattended screenshots, also after killing the main process). |
| Tracker | `P3-14`, `P3-15` |

#### AI and models

### R-06 — llama.cpp JNI bridge and native build

| | |
|---|---|
| Disposition | **Adapt**: CONDITIONAL on P0-41/P0-43: staged for evaluation, integrated only if a native build and real token generation are demonstrated; S6 also measures LiteRT-LM (R-28) and a local HTTP server, and the best of the three is chosen on numbers |
| Destination | `native/llama-jni` + `:ai:local` |
| Existing source | ARIA `cpp/{llama_jni.cpp, CMakeLists.txt, FindOpenCL.cmake, aria_crash_handler.cpp}` · reference: ARIA `cpp/aria_math.cpp`; ARIA `cpp/opencl-headers/CL/cl.h` |
| Staged (verbatim) | 4 files, 2077 lines at `native/llama-jni/imported/…` · manifest M-001…M-004 |
| Why this disposition | Real JNI code (about 1.4K lines of C++) for load, tokenise, generate, vision via mtmd. NOT buildable from the supplied archive: `app/src/main/cpp/llama.cpp` is an empty directory and no `.gitmodules` exists, so the llama.cpp commit to pin is unknown (CMake comments point at a recent build that includes llama.cpp's multimodal `mtmd` tool). The LoRA-training block and `aria_math.cpp` are excluded. |
| Dependencies and Android compatibility | `-march=armv8-a` and `abiFilters arm64-v8a` only (no 32-bit phones). Vulkan/OpenCL forced OFF in CMake. Gradle passes two hard-coded Windows paths (`C:/Users/DELL/...`) that must be removed. |
| Licence | ARIA (no licence file; locked until D-19 is confirmed, P0-40); llama.cpp is MIT |
| Evidence required | COMPILED: arm64-v8a build log with the pinned llama.cpp commit recorded in `docs/VERSIONS.md`. DEVICE: tokens generated through JNI with `jniAvailable=true`; tok/s, RSS and temperature recorded (P0-43). |
| Tracker | `P0-40`, `P0-41`, `P0-43`, `P12-14` |

### R-07 — LlamaEngine Kotlin wrapper

| | |
|---|---|
| Disposition | **Adapt**: remove the `object` singleton and the stub fallback; expose as an `:ai:api` `ModelProvider` with typed errors |
| Destination | :ai:local |
| Existing source | ARIA `…/core/ai/LlamaEngine.kt` |
| Staged (verbatim) | 1 files, 490 lines at `ai/local/imported/…` · manifest M-005…M-005 |
| Why this disposition | When the JNI library is missing it returns a fabricated `Click #1` JSON (14 stub/fake markers in the file). A broker-mediated agent must fail closed instead (ADR-090). |
| Dependencies and Android compatibility | external libraries: `kotlinx.coroutines` · No internal dependencies. Calls the JNI symbols of R-06. |
| Licence | ARIA (no licence file; locked until D-19 is confirmed, P0-40) |
| Evidence required | UNIT: contract test that an unavailable engine returns `CAPABILITY_UNAVAILABLE` and never an action. DEVICE: P0-43 run and G12-d. |
| Tracker | `P0-43`, `P12-14` |

### R-08 — Model catalog, download and storage management

| | |
|---|---|
| Disposition | **Adapt**: resumable OkHttp download and storage layout kept; catalog gains licence, checksum and size fields; no weights bundled |
| Destination | :ai:local |
| Existing source | ARIA `…/core/ai/{ModelManager, ModelDownloadService, ModelCatalog}` |
| Staged (verbatim) | 3 files, 827 lines at `ai/local/imported/…` · manifest M-006…M-008 |
| Why this disposition | Downloads GGUF files from Hugging Face with range-resume. The catalog lists several third-party models (SmolVLM, Qwen, Llama 3.2, Gemma) whose licence terms differ; AutoHost must show those terms before download (X-09). |
| Dependencies and Android compatibility | not staged, cut or replace: `core/events`: AgentEventBus; `ui`: ComposeMainActivity · external libraries: `kotlinx.coroutines`, `androidx.core`, `okhttp3.OkHttpClient`, `okhttp3.Request` · `ModelDownloadService` depends on `AgentEventBus` and `ui.ComposeMainActivity` (drop both). `dataSync` foreground services are time-limited on Android 15: use a user-initiated data transfer job or WorkManager. |
| Licence | ARIA (no licence file; locked until D-19 is confirmed, P0-40); model weights carry their own licences |
| Evidence required | UNIT: resume/checksum tests against a fake HTTP server. DEVICE: one real download resumed after a network drop. |
| Tracker | `P12-14`, `X-09` |

### R-09 — Thermal and sustained-performance guard for inference

| | |
|---|---|
| Disposition | **Adapt**: decision logic extracted into a pure `*Policy` (CONVENTIONS), Android sensors behind an interface |
| Destination | :ai:local |
| Existing source | ARIA `…/core/system/{ThermalGuard, SustainedPerformanceManager, HardwareMonitor}` |
| Staged (verbatim) | 3 files, 445 lines at `ai/local/imported/…` · manifest M-009…M-011 |
| Why this disposition | Local inference heats low-end phones quickly; a guard that pauses or throttles generation is a prerequisite for shipping the local provider. Small files, one internal dependency (`AgentEventBus`, drop). |
| Dependencies and Android compatibility | not staged, cut or replace: `core/events`: AgentEventBus · external libraries: `kotlinx.coroutines`, `androidx.annotation` · Uses `PowerManager` thermal APIs (API 29+ thermal status); verify availability per API in `ApiGate`. |
| Licence | ARIA (no licence file; locked until D-19 is confirmed, P0-40) |
| Evidence required | UNIT: policy tests on fake thermal inputs. DEVICE: soak with the local model (G12-f). |
| Tracker | `P12-14` |

### R-10 — Cloud LLM clients (OpenAI-compatible)

| | |
|---|---|
| Disposition | **Adapt**: Chat Completions + Responses clients, retry/stream-retry, error and context-window classifiers; base-URL validator |
| Destination | :ai:cloud |
| Existing source | ClosePaw `…/llm/*` (15 files) · reference: ClosePaw `…/llm/{CodexResponseClient, LFMLLMClient, LeapFunctionInterop, LocalLLMConfig}` |
| Staged (verbatim) | 15 files, 1794 lines at `ai/cloud/imported/…` + 10 tests · manifest M-012…M-036 |
| Why this disposition | Self-contained package: it imports only `auth` (3 references, Codex flow) and one `app` class, and has 10 unit-test files. This is what makes cloud-first agent runs cheap on the phone: no on-device inference, no Python. |
| Dependencies and Android compatibility | not staged, cut or replace: `root`: BuildConfig · external libraries: `com.openai`, `kotlinx.coroutines` · Built on `com.openai:openai-java` 4.14.0; no OpenAI SDK type may cross the `:ai:api` boundary, and OkHttp + kotlinx.serialization is the lighter alternative to evaluate (ADR-090). `LLMClientFactory` is not staged (Codex/Leap wiring). |
| Tests | 10 staged test files · 1 use MockK, but AutoHost conventions say fakes over mocks: port them to fakes · also need (not staged): `tool`: jsonObjectToJsonValueMap |
| Licence | Apache-2.0 (ClosePaw); depends on the Apache-2.0 `openai-java` SDK |
| Evidence required | UNIT: ported client, retry and classifier tests. INTEG: one real streamed call with tool calls (P0-45). Budget caps tested (G12-a). |
| Tracker | `P0-45`, `P12-01`, `P12-02`, `P12-03` |

#### Agent runtime

### R-11 — Turn loop building blocks (error classification, loop detection)

| | |
|---|---|
| Disposition | **Rewrite**: `:runtime:agent` is written new as an AutoHost `RuntimeProvider`; only the two pure helpers are staged |
| Destination | :runtime:agent |
| Existing source | ClosePaw `…/agent/TurnErrorClassifier.kt`; ClosePaw `…/agent/cognition/policy/LoopDetectionPolicy.kt` · reference: ClosePaw `…/agent/{Agent, AgentTurnRunner, TurnPlanningPhaseRunner, TurnExecutionPhaseRunner}`; ClosePaw `…/agent/cognition/prompt/PromptBuilder.kt`; ARIA `…/core/agent/AgentLoop.kt`; ClosePaw `doc/main/agent/loop.md`; ClosePaw `doc/main/state_machines/agent_run_loop.md` |
| Staged (verbatim) | 2 files, 123 lines at `runtime/agent/imported/…` + 2 support types + 4 tests · manifest M-037…M-044 |
| Why this disposition | ClosePaw's `agent` package depends on `history` (18 imports), `tool` (13), `llm` (11), `protocol` (10), `session` (7) and `trace` (7); ARIA's loop is a global singleton. Neither can be the run lifecycle (ADR-089: one orchestrator, one broker, one action path). The algorithms (turn phases, error classes, loop detection, tool arbitration) are reference. |
| Dependencies and Android compatibility | `LoopDetectionPolicy` needs `NavigationState` (staged by closure); `TurnErrorClassifier` only uses `android.util.Log`. |
| Tests | 4 staged test files |
| Licence | Apache-2.0 (ClosePaw) |
| Evidence required | UNIT: ported helper tests. INTEG: fake-model scenario suite through the real broker. DEVICE: G12-a. |
| Tracker | `P12-05`, `P12-06`, `P12-09` |

### R-12 — Tool registry, router and spec

| | |
|---|---|
| Disposition | **Reference only**: AutoHost's tool surface is generated from `CapabilitySpec`s and executed by the broker; ClosePaw's registry would be a second dispatch path |
| Destination | :runtime:agent (tool schemas generated from specs) |
| Existing source | (nothing staged) · reference: ClosePaw `…/tool/{ToolSpec, ToolRegistry, ToolRouter}`; ClosePaw `doc/main/infra/tools.md` |
| Why this disposition | One broker, one action path (ADR-089). The useful part is the tool-schema shape and the arbitration rules, which inform P12-05. |
| Dependencies and Android compatibility |  |
| Licence | Apache-2.0 (ClosePaw) |
| Evidence required | Not applicable (no code adopted). P12-05 is verified by schema-generation drift tests. |
| Tracker | `P12-05` |

### R-13 — Sensitive-app tier policy (banking/authenticator/wallet hard-block)

| | |
|---|---|
| Disposition | **Adapt**: classifier and tier model become a pure `SensitiveAppPolicy` in `:core:model`; `app_tiers.json` is reused unchanged as data (116 apps) with attribution |
| Destination | :core:model (policy) + broker interceptor |
| Existing source | ClosePaw `…/tool/{AppClassifier, PolicyEngine}`; ClosePaw `…/protocol/AppTier.kt`; ClosePaw `assets/security/app_tiers.json` · reference: ClosePaw `doc/main/infra/tools.md`; ClosePaw `…/platform/AccessibilityPlatform.kt` |
| Staged (verbatim) | 4 files, 475 lines at `core/model/imported/…` + 1 support types + 3 tests · manifest M-045…M-052 |
| Why this disposition | Directly serves the plan's boundaries (§0.4) and fail-closed rule: BLOCKED tier is always denied for agent runs, CAUTIOUS needs approval, NORMAL is allowed. It also lets Permission Review warn when a task's scope contains a sensitive package. |
| Dependencies and Android compatibility | not staged, cut or replace: `llm`: LocalLLMConfig; `perception`: PerceptionConfig · also uses files staged under another row: R-11: Models · external libraries: `kotlinx.coroutines` · `AppClassifier` reads the list through `AssetManager` and logs through `android.util.Log`: inject the asset reader and `AhLog`. `PolicyEngine` also carries ClosePaw's approval modes (map to `ui.ask`). |
| Tests | 3 staged test files · 2 use MockK, but AutoHost conventions say fakes over mocks: port them to fakes · also need (not staged): `app`: AppSettingsStore |
| Licence | Apache-2.0 (ClosePaw) |
| Evidence required | UNIT: ported `AppClassifier*Test` and `PolicyEngineTest` tables. DEVICE: G12-b (denial + FLAG_SECURE masking on the phone). |
| Tracker | `P0-46`, `P2-25`, `P8-17`, `P12-07` |

### R-14 — Run traces and evidence recorder

| | |
|---|---|
| Disposition | **Adapt**: file-backed recorder and redactor feed the agent traces that join the existing `.aarun` export bundle |
| Destination | :runtime:agent (agent traces); the `.aarun` export in `:core:engine` is already written |
| Existing source | ClosePaw `…/trace/{TraceRecorder, FileTraceRecorder, TraceModels, CognitionTraceRedactor, A11yTreeDumper}` · reference: ClosePaw `…/trace/AgentTrace.kt` |
| Staged (verbatim) | 5 files, 543 lines at `runtime/agent/imported/…` + 1 support types + 2 tests · manifest M-053…M-060 |
| Why this disposition | AutoHost needs redacted, replayable evidence for failures (G5-c) and for agent runs (G12-e). The recorder and its redactor have unit and security tests; they must be merged with AutoHost's own `Redactor`, not run beside it. |
| Dependencies and Android compatibility | external libraries: `kotlinx.serialization`, `kotlinx.coroutines` · `A11yTreeDumper` uses `util.AccessibilityNodeInfoCompat` (staged by closure). |
| Tests | 2 staged test files |
| Licence | Apache-2.0 (ClosePaw) |
| Evidence required | UNIT: ported recorder and redactor tests plus new tests for queue saturation, flush deadline and crash durability (ClosePaw's guarantees are not trusted until tested). INTEG: canary-secret grep over the bundle (G5-d, G12-e). |
| Tracker | `P5-09`, `P12-08` |

### R-15 — History, context compaction and prompt assets

| | |
|---|---|
| Disposition | **Reference only**: read when implementing P12-09; no files staged |
| Destination | :runtime:agent |
| Existing source | (nothing staged) · reference: ClosePaw `doc/main/agent/{overview.md, planning.md, memory.md, agent_skills.md}` |
| Why this disposition | History is coupled to ClosePaw's `ui` package (7 imports) and session model; the compaction idea is useful, the code is not separable. |
| Dependencies and Android compatibility |  |
| Licence | Apache-2.0 (ClosePaw) |
| Evidence required | Not applicable. |
| Tracker | `P12-09` |

### R-16 — Perception shaping for LLM input (compact UI text)

| | |
|---|---|
| Disposition | **Reference only**: AutoHost's compact dump is already specified (§7.6, `core:model` golden vectors); compare token cost, do not import |
| Destination | :core:model `CompactDump` |
| Existing source | (nothing staged) · reference: ClosePaw `…/perception/Perceptor.kt`; ARIA `…/core/perception/ScreenObserver.kt` |
| Why this disposition | A third dump format would fork the spec. ARIA lists interactable nodes only; ClosePaw's is richer but model-coupled. |
| Dependencies and Android compatibility |  |
| Licence | mixed |
| Evidence required | UNIT: golden vectors. Token-size comparison recorded in P12-09 evidence. |
| Tracker | `P12-09` |

#### UI

### R-17 — Floating overlay and status capsule

| | |
|---|---|
| Disposition | **Adapt**: state holder, overlay hosts, capsule surface and the touch/bounds action visualiser become the floating Stop/status bubble (P7-06) and the live agent overlay with its command bar (P12-12); branding glyph and voice excluded |
| Destination | :app (surfaces; the P7-06 bubble is already written, the agent capsule is new) |
| Existing source | ClosePaw `…/ui/capsule/{CapsuleBinding, NavAction}`; ClosePaw `…/ui/overlay/CapsuleStateHolder.kt`; ClosePaw `…/ui/overlay/model/{CapsuleContext, CapsuleMode, CapsuleRenderSpec, GlowState}`; ClosePaw `…/ui/overlay/compose/*` (9 files); ClosePaw `…/ui/overlay/visualizer/ActionVisualizerManager.kt`; ClosePaw `…/ui/capsule/surface/{CapsuleControlBar, CapsuleInputBar, SmartCapsuleHostLayout, SmartCapsuleSurface, StatusColors}` · reference: ClosePaw `…/ui/capsule/voice/VoiceInputController.kt`; ClosePaw `doc/main/ui/capsule/architecture.md`; ARIA `…/system/overlay/FloatingChatOverlay.kt` |
| Staged (verbatim) | 22 files, 3113 lines at `app/imported/…` + 6 support types + 3 tests · manifest M-061…M-091 |
| Why this disposition | The user asked for ClosePaw's clean UI. This is the part that matters for a running automation: an always-visible status and Stop control. It is also the most coupled UI code (50 `protocol` imports), so it is staged with its state types and re-bound to AutoHost's run model. |
| Dependencies and Android compatibility | not staged, cut or replace: `app`: AgentService, shouldCapsuleOverlayBeTouchable; `platform`: OverlayTouchGate; `ui`: AndroidRecognizerFactory, RecognizerFactory, StatusPawGlyph, VoicePermissionDisposition, VoiceState, rememberVoiceInputController (+1 more) · also uses files staged under another row: R-13: SessionConfig; R-18: Motion, Theme, Tokens · external libraries: `androidx.compose`, `kotlinx.coroutines`, `androidx.lifecycle`, `androidx.savedstate`, `com.composables` · Overlay hosts use `LOLLIPOP_MR1`/`P` checks only; `CapsuleOverlayHost` imports `app` and `platform` classes (cut). Requires `SYSTEM_ALERT_WINDOW` or accessibility overlays per `docs/ANDROID_MANIFEST.md`. |
| Tests | 3 staged test files |
| Licence | Apache-2.0 (ClosePaw); Apache-2.0 does not grant the ClosePaw name or paw mascot |
| Evidence required | UNIT: ported `CapsuleStateHolderTest`, `CapsuleRenderSpecTest`. DEVICE: G7-c (Stop from the bubble halts a run while another app is foreground). |
| Tracker | `P7-06`, `P12-12` |

### R-18 — Design system (tokens, type, motion) and fonts

| | |
|---|---|
| Disposition | **Adapt**: restyle: tokens, type and components adopted into the existing 225-line `:core:designsystem` behind `docs/UI_SPEC.md` §2 (P7-14); fonts reused unchanged with their attribution file |
| Destination | :core:designsystem |
| Existing source | ClosePaw `…/ui/theme/*` (9 files); ClosePaw `res/font/{fraunces_italic.ttf, fraunces_regular.ttf, geist_medium.ttf, geist_regular.ttf, jetbrains_mono_medium.ttf, jetbrains_mono_regular.ttf}`; ClosePaw `assets/FONT_ATTRIBUTION.md` · reference: ARIA `…/ui/theme/ARIATheme.kt` |
| Staged (verbatim) | 16 files, 9485 lines at `core/designsystem/imported/…` · manifest M-092…M-107 |
| Why this disposition | The cleanest UI in the three repos and the one the owner asked for. UI_SPEC remains authoritative for screens; ClosePaw supplies the visual language. |
| Dependencies and Android compatibility | not staged, cut or replace: `root`: R · external libraries: `androidx.compose`, `androidx.core` · `Theme.kt` has an API-35 branch only. Decorative pieces (`Ornaments`, `PaperGrain`) are optional. |
| Licence | Apache-2.0 (code); fonts are third-party (see FONT_ATTRIBUTION.md, verify licence text before shipping) |
| Evidence required | COMPILED then screenshot tests per `docs/UI_SPEC.md` §9; TalkBack and contrast checks (§8). |
| Tracker | `P1-17`, `P7-14` |

### R-19 — Application screens and navigation (ARIA 11 screens, ClosePaw onboarding/settings)

| | |
|---|---|
| Disposition | **Reference only**: deviation from the brief's example row (Android UI: ARIA, reuse/adapt) |
| Destination | :app, :feature:* |
| Existing source | (nothing staged) · reference: ARIA `…/ui/screens/{ModulesScreen, OnboardingScreen}`; ClosePaw `…/onboarding/` |
| Why this disposition | ARIA's screens (about 11K lines plus a 2,634-line `AgentViewModel`) are agent-centric (Goals, Train, Labeler, Knowledge wizard) and are not the AutoHost screen set in `docs/UI_SPEC.md` (S01–S20). Nothing is salvageable without rewriting its state layer. |
| Dependencies and Android compatibility |  |
| Licence | mixed |
| Evidence required | Not applicable. |
| Tracker | – |

#### Deferred

### R-20 — Agent memory, skills, app-skills and browser-use skill packs

| | |
|---|---|
| Disposition | **Defer**: skills map onto AutoHost app packs (P11-04); memory needs decision D-20 first (opt-in, provenance, user view/edit/delete, retention); skill content is untrusted data read by a bounded parser; per-file provenance must be checked |
| Destination | :runtime:agent (later) |
| Existing source | (nothing staged) · reference: ClosePaw `assets/app_skills/`; ClosePaw `assets/agent_skills/`; ClosePaw `…/agent/cognition/skills/AgentSkillManager.kt`; ClosePaw `…/memory/{MemoryStore, MemoryRecaller}` |
| Why this disposition | Valuable idea, but the packs are text written for ClosePaw's tool names. |
| Dependencies and Android compatibility |  |
| Licence | Apache-2.0 (repository); per-file provenance unverified |
| Evidence required | Not applicable until P11-04. |
| Tracker | `P11-04` |

### R-21 — Shell, Termux, Shizuku virtual display, browser CDP, wireless-ADB pairing

| | |
|---|---|
| Disposition | **Defer**: Phase 9/10 candidates (P9-01, P9-03, P10-01); API 31+ and BouncyCastle/Conscrypt/EdDSA dependencies |
| Destination | :capabilities:shell, :capabilities:browser |
| Existing source | (nothing staged) · reference: ClosePaw `…/browser/cdp/wireless/{AdbPairingClient, Spake25519}`; ClosePaw `…/platform/virtualdisplay/VirtualDisplayPlatform.kt`; ClosePaw `…/termux/TermuxRunCommandAdapter.kt` |
| Why this disposition | Power tools are outside the first vertical slice and the primary phone (Android 10) cannot use the wireless-pairing path. |
| Dependencies and Android compatibility | Needs API 30/31+; virtual display requires Shizuku. |
| Licence | Apache-2.0 (code); Shizuku API listed as Apache in NOTICE but MIT in the generated report (verify) |
| Evidence required | Not applicable until Phase 9. |
| Tracker | `P9-01`, `P9-03`, `P10-01` |

### R-22 — OCR and vision (ML Kit, MediaPipe, SmolVLM via mtmd, SAM2, pixel verifier)

| | |
|---|---|
| Disposition | **Reference only**: AutoHost already has `capabilities/vision` with engines behind seams (ADR-072, written, unverified); ARIA's `OcrEngine` is only a reference for the engine behind the OCR seam, after the licence decision in P0-40 (ML Kit and ONNX/MediaPipe terms) |
| Destination | :capabilities:vision |
| Existing source | (nothing staged) · reference: ARIA `…/core/ocr/OcrEngine.kt`; ARIA `…/core/perception/ObjectDetectorEngine.kt`; ARIA `…/core/ai/VisionEngine.kt`; ARIA `…/core/system/PixelVerifier.kt` |
| Why this disposition | Vision is a hint, not proof (§6.1); it adds APK size and CPU cost the first slice does not need. |
| Dependencies and Android compatibility | ML Kit and MediaPipe bring native libraries per ABI. |
| Licence | ARIA (no licence file) + third-party SDK terms (ML Kit: Google terms, verify) |
| Evidence required | Not applicable until Phase 10. |
| Tracker | `P10-05`, `P0-40` |

#### Excluded features

### R-23 — RL, IRL, LoRA training, embeddings, experience/replay stores, game loop

| | |
|---|---|
| Disposition | **Reference only**: excluded from AutoHost: CPU-heavy research features with no requirement in `plan.md`; they stay in `reference/aria/` |
| Destination | (none) |
| Existing source | (nothing staged) · reference: ARIA `…/core/rl/{PolicyNetwork, IrlModule, LoraTrainer}`; ARIA `…/core/memory/EmbeddingEngine.kt`; ARIA `…/core/agent/GameLoop.kt` |
| Why this disposition | Directly contrary to the low-CPU goal: on-device training, embedding models and RL loops run continuously. |
| Dependencies and Android compatibility |  |
| Licence | ARIA (no licence file) |
| Evidence required | Not applicable. |
| Tracker | – |

### R-24 — ARIA orchestration layer and event bus

| | |
|---|---|
| Disposition | **Reference only**: excluded: a second orchestrator would violate ADR-089; the event bus dependency is cut from every staged file |
| Destination | (none) |
| Existing source | (nothing staged) · reference: ARIA `…/core/orchestration/{CentralOrchestrator, ComponentRegistry}`; ARIA `…/core/events/AgentEventBus.kt` |
| Why this disposition | Enterprise-style registry, circuit breaker and problem-solving broker around an app that needs one run state machine. |
| Dependencies and Android compatibility |  |
| Licence | ARIA (no licence file); its README says it is ported from earlier 'donor' Java projects of unknown licence |
| Evidence required | Not applicable. |
| Tracker | – |

### R-25 — ARIA foreground service, chat overlay, logging, monitoring server, build files

| | |
|---|---|
| Disposition | **Reference only**: AutoHost specifies `RunForegroundService`, `AhLog` and the Dev API itself; ARIA's build files are rewritten, not adapted |
| Destination | (none) |
| Existing source | (nothing staged) · reference: ARIA `…/system/AgentForegroundService.kt`; ARIA `…/core/logging/CrashHandler.kt`; ARIA `…/core/monitoring/LocalDeviceServer.kt`; ARIA `app/build.gradle`; ARIA `/settings.gradle` |
| Why this disposition | Each duplicates something AutoHost already specifies in more detail. |
| Dependencies and Android compatibility |  |
| Licence | ARIA (no licence file) |
| Evidence required | Not applicable. |
| Tracker | – |

#### Tooling

### R-26 — Agent evaluation and trace inspection tooling (Python, PC side)

| | |
|---|---|
| Disposition | **Defer**: adapt in P12-13; Python is the right language for PC-side tooling |
| Destination | `sdk-python/` / `testing/` |
| Existing source | (nothing staged) · reference: ClosePaw `eval/`; ClosePaw `inspection_tool/`; ClosePaw `doc/main/agent/overview.md` |
| Why this disposition | Keeps the agent measurable (success rate, steps, tokens) without adding phone CPU. |
| Dependencies and Android compatibility |  |
| Licence | Apache-2.0 (ClosePaw) |
| Evidence required | Not applicable until P12-13. |
| Tracker | `P12-13` |

#### Other reference projects (2026-10-27)

### R-27 — Agent policy layers, approvals, model routing, compaction and agent-authored scripts (android-agent)

| | |
|---|---|
| Disposition | **Reference only**: clean-room ideas only: the archive has no licence file (treated like ARIA); nothing is copied or staged |
| Destination | :runtime:agent (policy layers, router, `run_flow` tool, eval baselines) |
| Existing source | (nothing staged) · reference: android-agent `java/com/foxislam/androidagent/agent/*` (8 files); android-agent `eval/` |
| Why this disposition | A compact (about 11.8K lines) cloud-first agent that solved the same problems the plan has: a three-layer permission model that is code rather than prompt text, a plan mode that withholds tools until the user approves, cheap-model routing for mechanical steps, scripts so the model does not pay a round trip per tap, an append-only chat log that survives process death, and a scripted eval harness with baselines. Its ideas are folded into P12-03, P12-06, P12-07, P12-13 and P12-16. |
| Dependencies and Android compatibility | It allows cleartext gateways with a warning; AutoHost keeps HTTPS-only release builds (D-18). |
| Licence | No licence file (locked like ARIA); ideas only |
| Evidence required | Not applicable (no code adopted); proven by G12-b/c and the eval baselines (P12-13). |
| Tracker | `P12-03`, `P12-06`, `P12-07`, `P12-13`, `P12-16` |

### R-28 — Multi-provider catalog, model integrity checks and the LiteRT-LM on-device runtime (OpenDroid)

| | |
|---|---|
| Disposition | **Defer**: Apache-2.0, so adaptation is legally open, but adopt specific files only after the first OpenAI-compatible provider works (P12-02); candidates: native Anthropic and Gemini adapters, model-artifact integrity checks, and the LiteRT-LM provider as an alternative to the llama.cpp JNI path (P12-14) |
| Destination | :ai:cloud (more providers), :ai:local (LiteRT option) |
| Existing source | (nothing staged) · reference: OpenDroid `java/com/opendroid/ai/core/llm/{ProviderCatalog, LLMProviderFactory, ConnectionTest, ModelArtifactIntegrity, OnDeviceModelRegistry, LiteRtCompatibility}`; OpenDroid `java/com/opendroid/ai/core/llm/providers/LiteRTLMProvider.kt`; OpenDroid `java/com/opendroid/ai/core/security/ProviderCredentialStore.kt` |
| Why this disposition | A large (about 66K lines, 71 test files) assistant app whose smart-home, finance, voice and social features are out of scope. The useful part is its LLM layer, which shows an established on-device route (LiteRT-LM and the Android AI Core prompt API) that needs no custom JNI or NDK code — relevant because ARIA's llama.cpp path cannot be built from its archive. |
| Dependencies and Android compatibility | minSdk 26 (AutoHost 29); uses Hilt and Room (AutoHost uses manual DI); LiteRT-LM needs models in its own format and their licences (for example Gemma terms) apply. |
| Licence | Apache-2.0 (LICENSE with an OpenDroid header); not yet staged |
| Evidence required | COMPILED then UNIT for any adapted file; DEVICE: tokens/s, RSS and temperature for the chosen local runtime (S6, G12-f). |
| Tracker | `P0-43`, `P12-02`, `P12-14` |

### R-29 — Notification and schedule sub-agents, skill validator, learned app hints (Sanna)

| | |
|---|---|
| Disposition | **Reference only**: React Native stack, so nothing is reusable as code; ideas only: event-triggered agent runs (AutoHost triggers already exist, Phase 4/8), validated skills (app packs, P11-04), per-app hints (D-20) |
| Destination | (none) |
| Existing source | (nothing staged) · reference: Sanna `src/agent/{notification-sub-agent.ts, accessibility-sub-agent.ts, scheduler-headless.ts, skill-validator.ts, accessibility-hint-store.ts}` |
| Why this disposition | A voice-first React Native assistant; its Kotlin modules are accessibility and notification plumbing that AutoHost already has. |
| Dependencies and Android compatibility | TypeScript/React Native. |
| Licence | MIT (declared in the README; no licence file) |
| Evidence required | Not applicable. |
| Tracker | `P11-04` |

## D. AutoHost-owned components (no external source adopted)

| ID | Component | Source of truth | AutoHost today (autohost.zip, 2026-10-26) | Disposition | Destination | Evidence required | Tracker |
|---|---|---|---|---|---|---|---|
| R-40 | Capability broker, interceptor chain, permission and scope enforcement | AutoHost spec: plan §5.4, `schemas/broker.schema.json`, `core/broker/src/main/aidl/**` | `core/broker` (17 files): written, unverified | Verify (then fix the run-token gaps) | :core:broker | UNIT scope/permission tests; G1-f; X-03 canary suite; G1-j | `P1-06`, `P1-07`, `P1-26` |
| R-41 | Run lifecycle, cancellation and recovery (one state machine) | AutoHost spec: plan §12 | `core/engine` (55 files): written, unverified | Verify | :core:engine | UNIT state-machine tests; DEVICE G1-d (Stop within 6 s), G5-a (resume) | `P1-10`, `P5-01` |
| R-42 | Python SDK, simulator and worker runtime | `sdk-python/`, `schemas/`, `samples/` | SDK, simulator and CLI written; Python suites pass in the planning sandbox (`docs/VALIDATION_REPORT.md`); `runtime/python` (Kotlin) written, unverified | Preserve and complete | `sdk-python/`, `:runtime:python` | UNIT: SDK/schema/sync suites; INTEG/DEVICE: G1-a..e | `P1-08`, `P1-09`, `P1-27`, `P5-17` |
| R-43 | Declarative flow runtime and recorder | AutoHost spec: plan §7.9 (no source in ARIA/ClosePaw) | `runtime/flow` (14 files): written, unverified | Verify | :runtime:flow | UNIT with `FakeDevice`; DEVICE G6-a (no `:w*` process) | `P6-01`, `P6-02` |
| R-44 | Agent runtime (LLM loop as a `RuntimeProvider`) | New; built from R-10, R-11, R-13, R-14 under ADR-089/088 | nothing written (no model, agent or tier code exists) | Implement (new) | :runtime:agent | INTEG fake-model suite; DEVICE G12-a..e | `P12-05`, `P12-06` |
| R-45 | Dev API, CLI and MCP server | AutoHost spec: `docs/DEVAPI.md`; ARIA `LocalDeviceServer` is reference only | `core/devapi` (5 files) and `sdk-python/autohost_cli`: written, unverified | Verify | :core:devapi, `sdk-python/autohost_cli` | UNIT RPC tests; DEVICE G2-f | `P2-21`, `P5-12` |

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

## F. Integration procedure (applies to every staged row)
1. Confirm the row's licence status above (ARIA rows: D-19 must be CONFIRMED first). 2. Create the module build file only when its first adapted class compiles. 3. Move one staged file at a time to `src/main/kotlin/app/autohost/<module>/…`, rename the package, add the attribution header, remove cut dependencies, and edit its row in `docs/migration-manifest.tsv` (`staged` → `integrated` with the new path, or `dropped` with a reason). 4. Port the staged tests with the file. 5. Raise the tracker state only as far as the evidence goes (`COMPILED` → `UNIT` → `INTEG` → `DEVICE`). 6. Update `THIRD_PARTY_NOTICES.md`. `python3 tools/check_docs.py` must pass after every step.
