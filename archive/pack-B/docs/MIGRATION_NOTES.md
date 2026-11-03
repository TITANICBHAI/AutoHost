# MIGRATION_NOTES — old paths to new paths
Generated 2026-10-27 together with `docs/REUSE_MATRIX.md` from `docs/migration-manifest.tsv` (machine-readable, hash-checked by `tools/check_docs.py`).

## What was copied, changed, replaced, deferred
| Action | What |
|---|---|
| **Copied** (verbatim, `staged`) | 107 files into `<module>/imported/<repo>/…` — 11 from ARIA, 96 from ClosePaw (of which 10 support types and 22 tests). SHA-256 recorded; not one byte changed |
| **Snapshotted** | `reference/aria/` and `reference/closepaw/` (pristine; omissions listed in `reference/README.md`) |
| **Changed** | Nothing in ARIA or ClosePaw. AutoHost files changed or added relative to `autohost.zip`: see `docs/CHANGES_FROM_AUTOHOST_ZIP.md` (documents, tracker tooling, no application source) |
| **Replaced / not carried over** | ARIA `LlamaEngine` stub fallback (fabricated actions); ARIA `GestureEngine` coordinate-only taps (replaced by ClosePaw node-first chains); ARIA Gradle/RN/Expo build files and Windows paths; ARIA orchestration, RL/IRL/LoRA/embedding code; ClosePaw Leap SDK client, Codex OAuth client, voice input, `StatusPawGlyph.kt` and the ClosePaw name |
| **Deferred** | skills/app-skills (P11-04), shell/Shizuku/virtual display/wireless pairing (Phase 9–10), OCR/vision (Phase 10), eval tooling (P12-13) |
| **Removed from AutoHost** | Nothing |

## Conventions
- Staged path = `<module dir>/imported/<repo>/<original repo-relative path>`; module dir is the row's destination.
- Row statuses in the manifest: `staged` (verbatim copy exists) · `integrated` (adapted; `staged_path` now names the new file; staged copy may be deleted) · `dropped` (decided against; reason in `notes`).
- A file is *imported* the moment it is staged. It is *integrated* only when adapted, compiled and referenced by AutoHost code (tracker state ladder).

## Path map (source → staged)


### R-06 — llama.cpp JNI bridge and native build (`native/llama-jni/imported/`)

| Manifest | Source (`aria`) | Staged path | Note |
|---|---|---|---|
| M-001 | `app/src/main/cpp/llama_jni.cpp` | `native/llama-jni/imported/aria/app/src/main/cpp/llama_jni.cpp` |  |
| M-002 | `app/src/main/cpp/CMakeLists.txt` | `native/llama-jni/imported/aria/app/src/main/cpp/CMakeLists.txt` |  |
| M-003 | `app/src/main/cpp/FindOpenCL.cmake` | `native/llama-jni/imported/aria/app/src/main/cpp/FindOpenCL.cmake` |  |
| M-004 | `app/src/main/cpp/aria_crash_handler.cpp` | `native/llama-jni/imported/aria/app/src/main/cpp/aria_crash_handler.cpp` |  |

### R-07 — LlamaEngine Kotlin wrapper (`ai/local/imported/`)

| Manifest | Source (`aria`) | Staged path | Note |
|---|---|---|---|
| M-005 | `app/src/main/kotlin/com/ariaagent/mobile/core/ai/LlamaEngine.kt` | `ai/local/imported/aria/app/src/main/kotlin/com/ariaagent/mobile/core/ai/LlamaEngine.kt` |  |

### R-08 — Model catalog, download and storage management (`ai/local/imported/`)

| Manifest | Source (`aria`) | Staged path | Note |
|---|---|---|---|
| M-006 | `app/src/main/kotlin/com/ariaagent/mobile/core/ai/ModelManager.kt` | `ai/local/imported/aria/app/src/main/kotlin/com/ariaagent/mobile/core/ai/ModelManager.kt` |  |
| M-007 | `app/src/main/kotlin/com/ariaagent/mobile/core/ai/ModelDownloadService.kt` | `ai/local/imported/aria/app/src/main/kotlin/com/ariaagent/mobile/core/ai/ModelDownloadService.kt` |  |
| M-008 | `app/src/main/kotlin/com/ariaagent/mobile/core/ai/ModelCatalog.kt` | `ai/local/imported/aria/app/src/main/kotlin/com/ariaagent/mobile/core/ai/ModelCatalog.kt` |  |

### R-09 — Thermal and sustained-performance guard for inference (`ai/local/imported/`)

| Manifest | Source (`aria`) | Staged path | Note |
|---|---|---|---|
| M-009 | `app/src/main/kotlin/com/ariaagent/mobile/core/system/ThermalGuard.kt` | `ai/local/imported/aria/app/src/main/kotlin/com/ariaagent/mobile/core/system/ThermalGuard.kt` |  |
| M-010 | `app/src/main/kotlin/com/ariaagent/mobile/core/system/SustainedPerformanceManager.kt` | `ai/local/imported/aria/app/src/main/kotlin/com/ariaagent/mobile/core/system/SustainedPerformanceManager.kt` |  |
| M-011 | `app/src/main/kotlin/com/ariaagent/mobile/core/system/HardwareMonitor.kt` | `ai/local/imported/aria/app/src/main/kotlin/com/ariaagent/mobile/core/system/HardwareMonitor.kt` |  |

### R-10 — Cloud LLM clients (OpenAI-compatible) (`ai/cloud/imported/`)

| Manifest | Source (`closepaw`) | Staged path | Note |
|---|---|---|---|
| M-012 | `app/src/main/kotlin/ai/closepaw/llm/LLMClient.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/LLMClient.kt` |  |
| M-013 | `app/src/main/kotlin/ai/closepaw/llm/LLMProvider.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/LLMProvider.kt` |  |
| M-014 | `app/src/main/kotlin/ai/closepaw/llm/ChatCompletionClient.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/ChatCompletionClient.kt` |  |
| M-015 | `app/src/main/kotlin/ai/closepaw/llm/ChatCompletionInterop.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/ChatCompletionInterop.kt` |  |
| M-016 | `app/src/main/kotlin/ai/closepaw/llm/OpenAIResponseClient.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/OpenAIResponseClient.kt` |  |
| M-017 | `app/src/main/kotlin/ai/closepaw/llm/CloudLlmRetry.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/CloudLlmRetry.kt` |  |
| M-018 | `app/src/main/kotlin/ai/closepaw/llm/CloudStreamRetryPolicy.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/CloudStreamRetryPolicy.kt` |  |
| M-019 | `app/src/main/kotlin/ai/closepaw/llm/CloudStreamRetryRunner.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/CloudStreamRetryRunner.kt` |  |
| M-020 | `app/src/main/kotlin/ai/closepaw/llm/ContextWindowClassifier.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/ContextWindowClassifier.kt` |  |
| M-021 | `app/src/main/kotlin/ai/closepaw/llm/ContextWindowExceededException.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/ContextWindowExceededException.kt` |  |
| M-022 | `app/src/main/kotlin/ai/closepaw/llm/OpenAIErrorClassifier.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/OpenAIErrorClassifier.kt` |  |
| M-023 | `app/src/main/kotlin/ai/closepaw/llm/OtherBaseUrlValidator.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/OtherBaseUrlValidator.kt` |  |
| M-024 | `app/src/main/kotlin/ai/closepaw/llm/ToolParameterExtractor.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/ToolParameterExtractor.kt` |  |
| M-025 | `app/src/main/kotlin/ai/closepaw/llm/ModelIdValidator.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/ModelIdValidator.kt` |  |
| M-026 | `app/src/main/kotlin/ai/closepaw/llm/LlmLogger.kt` | `ai/cloud/imported/closepaw/app/src/main/kotlin/ai/closepaw/llm/LlmLogger.kt` |  |
| M-027 | `app/src/test/kotlin/ai/closepaw/llm/ChatCompletionClientTest.kt` | `ai/cloud/imported/closepaw/app/src/test/kotlin/ai/closepaw/llm/ChatCompletionClientTest.kt` | unit/instrumented test to port |
| M-028 | `app/src/test/kotlin/ai/closepaw/llm/ChatCompletionInteropTest.kt` | `ai/cloud/imported/closepaw/app/src/test/kotlin/ai/closepaw/llm/ChatCompletionInteropTest.kt` | unit/instrumented test to port |
| M-029 | `app/src/test/kotlin/ai/closepaw/llm/OpenAIResponseClientTest.kt` | `ai/cloud/imported/closepaw/app/src/test/kotlin/ai/closepaw/llm/OpenAIResponseClientTest.kt` | unit/instrumented test to port |
| M-030 | `app/src/test/kotlin/ai/closepaw/llm/CloudStreamRetryPolicyTest.kt` | `ai/cloud/imported/closepaw/app/src/test/kotlin/ai/closepaw/llm/CloudStreamRetryPolicyTest.kt` | unit/instrumented test to port |
| M-031 | `app/src/test/kotlin/ai/closepaw/llm/CloudStreamRetryRunnerTest.kt` | `ai/cloud/imported/closepaw/app/src/test/kotlin/ai/closepaw/llm/CloudStreamRetryRunnerTest.kt` | unit/instrumented test to port |
| M-032 | `app/src/test/kotlin/ai/closepaw/llm/ContextWindowClassifierTest.kt` | `ai/cloud/imported/closepaw/app/src/test/kotlin/ai/closepaw/llm/ContextWindowClassifierTest.kt` | unit/instrumented test to port |
| M-033 | `app/src/test/kotlin/ai/closepaw/llm/OpenAIErrorClassifierTest.kt` | `ai/cloud/imported/closepaw/app/src/test/kotlin/ai/closepaw/llm/OpenAIErrorClassifierTest.kt` | unit/instrumented test to port |
| M-034 | `app/src/test/kotlin/ai/closepaw/llm/OtherBaseUrlValidatorTest.kt` | `ai/cloud/imported/closepaw/app/src/test/kotlin/ai/closepaw/llm/OtherBaseUrlValidatorTest.kt` | unit/instrumented test to port |
| M-035 | `app/src/test/kotlin/ai/closepaw/llm/ToolParameterExtractorTest.kt` | `ai/cloud/imported/closepaw/app/src/test/kotlin/ai/closepaw/llm/ToolParameterExtractorTest.kt` | unit/instrumented test to port |
| M-036 | `app/src/test/kotlin/ai/closepaw/llm/ModelIdValidatorTest.kt` | `ai/cloud/imported/closepaw/app/src/test/kotlin/ai/closepaw/llm/ModelIdValidatorTest.kt` | unit/instrumented test to port |

### R-11 — Turn loop building blocks (error classification, loop detection) (`runtime/agent/imported/`)

| Manifest | Source (`closepaw`) | Staged path | Note |
|---|---|---|---|
| M-037 | `app/src/main/kotlin/ai/closepaw/agent/TurnErrorClassifier.kt` | `runtime/agent/imported/closepaw/app/src/main/kotlin/ai/closepaw/agent/TurnErrorClassifier.kt` |  |
| M-038 | `app/src/main/kotlin/ai/closepaw/agent/cognition/policy/LoopDetectionPolicy.kt` | `runtime/agent/imported/closepaw/app/src/main/kotlin/ai/closepaw/agent/cognition/policy/LoopDetectionPolicy.kt` |  |
| M-039 | `app/src/main/kotlin/ai/closepaw/agent/cognition/context/NavigationState.kt` | `runtime/agent/imported/closepaw/app/src/main/kotlin/ai/closepaw/agent/cognition/context/NavigationState.kt` | support type (import closure) |
| M-040 | `app/src/main/kotlin/ai/closepaw/model/Models.kt` | `runtime/agent/imported/closepaw/app/src/main/kotlin/ai/closepaw/model/Models.kt` | support type (import closure) |
| M-041 | `app/src/test/kotlin/ai/closepaw/agent/TurnErrorClassifierTest.kt` | `runtime/agent/imported/closepaw/app/src/test/kotlin/ai/closepaw/agent/TurnErrorClassifierTest.kt` | unit/instrumented test to port |
| M-042 | `app/src/test/kotlin/ai/closepaw/agent/cognition/policy/LoopDetectionPolicyTest.kt` | `runtime/agent/imported/closepaw/app/src/test/kotlin/ai/closepaw/agent/cognition/policy/LoopDetectionPolicyTest.kt` | unit/instrumented test to port |
| M-043 | `app/src/test/kotlin/ai/closepaw/agent/cognition/context/NavigationStateTest.kt` | `runtime/agent/imported/closepaw/app/src/test/kotlin/ai/closepaw/agent/cognition/context/NavigationStateTest.kt` | unit/instrumented test to port |
| M-044 | `app/src/test/kotlin/ai/closepaw/model/ModelsTest.kt` | `runtime/agent/imported/closepaw/app/src/test/kotlin/ai/closepaw/model/ModelsTest.kt` | unit/instrumented test to port |

### R-13 — Sensitive-app tier policy (banking/authenticator/wallet hard-block) (`core/model/imported/`)

| Manifest | Source (`closepaw`) | Staged path | Note |
|---|---|---|---|
| M-045 | `app/src/main/kotlin/ai/closepaw/tool/AppClassifier.kt` | `core/model/imported/closepaw/app/src/main/kotlin/ai/closepaw/tool/AppClassifier.kt` |  |
| M-046 | `app/src/main/kotlin/ai/closepaw/tool/PolicyEngine.kt` | `core/model/imported/closepaw/app/src/main/kotlin/ai/closepaw/tool/PolicyEngine.kt` |  |
| M-047 | `app/src/main/kotlin/ai/closepaw/protocol/AppTier.kt` | `core/model/imported/closepaw/app/src/main/kotlin/ai/closepaw/protocol/AppTier.kt` |  |
| M-048 | `app/src/main/assets/security/app_tiers.json` | `core/model/imported/closepaw/app/src/main/assets/security/app_tiers.json` |  |
| M-049 | `app/src/main/kotlin/ai/closepaw/protocol/SessionConfig.kt` | `core/model/imported/closepaw/app/src/main/kotlin/ai/closepaw/protocol/SessionConfig.kt` | support type (import closure) |
| M-050 | `app/src/test/kotlin/ai/closepaw/tool/AppClassifierOverrideTest.kt` | `core/model/imported/closepaw/app/src/test/kotlin/ai/closepaw/tool/AppClassifierOverrideTest.kt` | unit/instrumented test to port |
| M-051 | `app/src/test/kotlin/ai/closepaw/tool/AppClassifierSecurityTest.kt` | `core/model/imported/closepaw/app/src/test/kotlin/ai/closepaw/tool/AppClassifierSecurityTest.kt` | unit/instrumented test to port |
| M-052 | `app/src/test/kotlin/ai/closepaw/tool/PolicyEngineTest.kt` | `core/model/imported/closepaw/app/src/test/kotlin/ai/closepaw/tool/PolicyEngineTest.kt` | unit/instrumented test to port |

### R-14 — Run traces and evidence recorder (`runtime/agent/imported/`)

| Manifest | Source (`closepaw`) | Staged path | Note |
|---|---|---|---|
| M-053 | `app/src/main/kotlin/ai/closepaw/trace/TraceRecorder.kt` | `runtime/agent/imported/closepaw/app/src/main/kotlin/ai/closepaw/trace/TraceRecorder.kt` |  |
| M-054 | `app/src/main/kotlin/ai/closepaw/trace/FileTraceRecorder.kt` | `runtime/agent/imported/closepaw/app/src/main/kotlin/ai/closepaw/trace/FileTraceRecorder.kt` |  |
| M-055 | `app/src/main/kotlin/ai/closepaw/trace/TraceModels.kt` | `runtime/agent/imported/closepaw/app/src/main/kotlin/ai/closepaw/trace/TraceModels.kt` |  |
| M-056 | `app/src/main/kotlin/ai/closepaw/trace/CognitionTraceRedactor.kt` | `runtime/agent/imported/closepaw/app/src/main/kotlin/ai/closepaw/trace/CognitionTraceRedactor.kt` |  |
| M-057 | `app/src/main/kotlin/ai/closepaw/trace/A11yTreeDumper.kt` | `runtime/agent/imported/closepaw/app/src/main/kotlin/ai/closepaw/trace/A11yTreeDumper.kt` |  |
| M-058 | `app/src/main/kotlin/ai/closepaw/util/AccessibilityNodeInfoCompat.kt` | `runtime/agent/imported/closepaw/app/src/main/kotlin/ai/closepaw/util/AccessibilityNodeInfoCompat.kt` | support type (import closure) |
| M-059 | `app/src/test/kotlin/ai/closepaw/trace/FileTraceRecorderTest.kt` | `runtime/agent/imported/closepaw/app/src/test/kotlin/ai/closepaw/trace/FileTraceRecorderTest.kt` | unit/instrumented test to port |
| M-060 | `app/src/test/kotlin/ai/closepaw/trace/CognitionTraceRedactorSecurityTest.kt` | `runtime/agent/imported/closepaw/app/src/test/kotlin/ai/closepaw/trace/CognitionTraceRedactorSecurityTest.kt` | unit/instrumented test to port |

### R-17 — Floating overlay and status capsule (`app/imported/`)

| Manifest | Source (`closepaw`) | Staged path | Note |
|---|---|---|---|
| M-061 | `app/src/main/kotlin/ai/closepaw/ui/capsule/CapsuleBinding.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/capsule/CapsuleBinding.kt` |  |
| M-062 | `app/src/main/kotlin/ai/closepaw/ui/capsule/NavAction.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/capsule/NavAction.kt` |  |
| M-063 | `app/src/main/kotlin/ai/closepaw/ui/overlay/CapsuleStateHolder.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/CapsuleStateHolder.kt` |  |
| M-064 | `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleContext.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleContext.kt` |  |
| M-065 | `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleMode.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleMode.kt` |  |
| M-066 | `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt` |  |
| M-067 | `app/src/main/kotlin/ai/closepaw/ui/overlay/model/GlowState.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/model/GlowState.kt` |  |
| M-068 | `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/ActionVisualizerCompose.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/compose/ActionVisualizerCompose.kt` |  |
| M-069 | `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/CapsuleOverlayHost.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/compose/CapsuleOverlayHost.kt` |  |
| M-070 | `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/EdgeGlowCompose.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/compose/EdgeGlowCompose.kt` |  |
| M-071 | `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/GlowOverlayHost.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/compose/GlowOverlayHost.kt` |  |
| M-072 | `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/IslandOverlayHost.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/compose/IslandOverlayHost.kt` |  |
| M-073 | `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/OverlayComposeHost.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/compose/OverlayComposeHost.kt` |  |
| M-074 | `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/ServiceLifecycleOwner.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/compose/ServiceLifecycleOwner.kt` |  |
| M-075 | `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/StatusIslandCompose.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/compose/StatusIslandCompose.kt` |  |
| M-076 | `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/VisualizerOverlayHost.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/compose/VisualizerOverlayHost.kt` |  |
| M-077 | `app/src/main/kotlin/ai/closepaw/ui/overlay/visualizer/ActionVisualizerManager.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/overlay/visualizer/ActionVisualizerManager.kt` |  |
| M-078 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt` |  |
| M-079 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt` |  |
| M-080 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleHostLayout.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleHostLayout.kt` |  |
| M-081 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt` |  |
| M-082 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/StatusColors.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/capsule/surface/StatusColors.kt` |  |
| M-083 | `app/src/main/kotlin/ai/closepaw/protocol/ApprovalTypes.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/protocol/ApprovalTypes.kt` | support type (import closure) |
| M-084 | `app/src/main/kotlin/ai/closepaw/protocol/TextUtils.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/protocol/TextUtils.kt` | support type (import closure) |
| M-085 | `app/src/main/kotlin/ai/closepaw/protocol/TurnPhase.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/protocol/TurnPhase.kt` | support type (import closure) |
| M-086 | `app/src/main/kotlin/ai/closepaw/protocol/AskUserType.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/protocol/AskUserType.kt` | support type (import closure) |
| M-087 | `app/src/main/kotlin/ai/closepaw/protocol/SessionEndReason.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/protocol/SessionEndReason.kt` | support type (import closure) |
| M-088 | `app/src/main/kotlin/ai/closepaw/protocol/TaskOutcome.kt` | `app/imported/closepaw/app/src/main/kotlin/ai/closepaw/protocol/TaskOutcome.kt` | support type (import closure) |
| M-089 | `app/src/test/kotlin/ai/closepaw/ui/overlay/CapsuleStateHolderTest.kt` | `app/imported/closepaw/app/src/test/kotlin/ai/closepaw/ui/overlay/CapsuleStateHolderTest.kt` | unit/instrumented test to port |
| M-090 | `app/src/test/kotlin/ai/closepaw/ui/overlay/model/CapsuleModeTest.kt` | `app/imported/closepaw/app/src/test/kotlin/ai/closepaw/ui/overlay/model/CapsuleModeTest.kt` | unit/instrumented test to port |
| M-091 | `app/src/test/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpecTest.kt` | `app/imported/closepaw/app/src/test/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpecTest.kt` | unit/instrumented test to port |

### R-18 — Design system (tokens, type, motion) and fonts (`core/designsystem/imported/`)

| Manifest | Source (`closepaw`) | Staged path | Note |
|---|---|---|---|
| M-092 | `app/src/main/kotlin/ai/closepaw/ui/theme/Color.kt` | `core/designsystem/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/theme/Color.kt` |  |
| M-093 | `app/src/main/kotlin/ai/closepaw/ui/theme/Motion.kt` | `core/designsystem/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/theme/Motion.kt` |  |
| M-094 | `app/src/main/kotlin/ai/closepaw/ui/theme/Ornaments.kt` | `core/designsystem/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/theme/Ornaments.kt` |  |
| M-095 | `app/src/main/kotlin/ai/closepaw/ui/theme/PaperGrain.kt` | `core/designsystem/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/theme/PaperGrain.kt` |  |
| M-096 | `app/src/main/kotlin/ai/closepaw/ui/theme/Shape.kt` | `core/designsystem/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/theme/Shape.kt` |  |
| M-097 | `app/src/main/kotlin/ai/closepaw/ui/theme/Theme.kt` | `core/designsystem/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/theme/Theme.kt` |  |
| M-098 | `app/src/main/kotlin/ai/closepaw/ui/theme/Tokens.kt` | `core/designsystem/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/theme/Tokens.kt` |  |
| M-099 | `app/src/main/kotlin/ai/closepaw/ui/theme/Type.kt` | `core/designsystem/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/theme/Type.kt` |  |
| M-100 | `app/src/main/kotlin/ai/closepaw/ui/theme/WindowInsets.kt` | `core/designsystem/imported/closepaw/app/src/main/kotlin/ai/closepaw/ui/theme/WindowInsets.kt` |  |
| M-101 | `app/src/main/res/font/fraunces_italic.ttf` | `core/designsystem/imported/closepaw/app/src/main/res/font/fraunces_italic.ttf` |  |
| M-102 | `app/src/main/res/font/fraunces_regular.ttf` | `core/designsystem/imported/closepaw/app/src/main/res/font/fraunces_regular.ttf` |  |
| M-103 | `app/src/main/res/font/geist_medium.ttf` | `core/designsystem/imported/closepaw/app/src/main/res/font/geist_medium.ttf` |  |
| M-104 | `app/src/main/res/font/geist_regular.ttf` | `core/designsystem/imported/closepaw/app/src/main/res/font/geist_regular.ttf` |  |
| M-105 | `app/src/main/res/font/jetbrains_mono_medium.ttf` | `core/designsystem/imported/closepaw/app/src/main/res/font/jetbrains_mono_medium.ttf` |  |
| M-106 | `app/src/main/res/font/jetbrains_mono_regular.ttf` | `core/designsystem/imported/closepaw/app/src/main/res/font/jetbrains_mono_regular.ttf` |  |
| M-107 | `app/src/main/assets/FONT_ATTRIBUTION.md` | `core/designsystem/imported/closepaw/app/src/main/assets/FONT_ATTRIBUTION.md` |  |
