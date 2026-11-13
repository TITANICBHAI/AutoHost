# THIRD_PARTY_NOTICES
Record every third-party code **actually reused** (copied or adapted). Libraries pulled in by Gradle/pip are covered by their own metadata, but list any with unusual terms.

| Component | Source / commit | Licence | Where used | Notice text preserved? |
|---|---|---|---|---|
| closepaw `llm/OtherBaseUrlValidator.kt` | `reference/closepaw/app/src/main/kotlin/ai/closepaw/llm/OtherBaseUrlValidator.kt` (0.1.0) | Apache-2.0 (LICENSE+NOTICE preserved) | `core/llm/src/main/kotlin/app/autohost/core/llm/BaseUrlValidator.kt` | notice preserved; adapted per RM-D1 |
| _(none yet)_ | | | | |

Rules: Apache-2.0 and MIT code only. **Never** add code from AGPL/GPL projects (Mobilerun Portal, OpenAutoJs). See `docs/REFERENCES.md` §0.

## Shizuku API (dev.rikka.shizuku:api 13.1.5)

Used by the shell tier's Shizuku transport (P9-04, ADR-064) — process execution with shell-level privileges through a user-started Shizuku server. Isolated to `app/shell/ShizukuProcessExec.kt`.

```
Apache License 2.0 — Copyright 2018 Rikka
https://github.com/RikkaApps/Shizuku-API — licensed under the Apache License, Version 2.0
```

## ML Kit Text Recognition (com.google.mlkit:text-recognition 16.0.1)

Used by the `vision` capability's OCR engine (P10-05, ADR-072) — on-device Latin text recognition, bundled into the APK (no Play Services dependency). Isolated to `capabilities/vision/src/main/kotlin/app/autohost/capabilities/vision/OcrEngine.kt`.

```
Apache License 2.0 — Copyright Google LLC
https://developers.google.com/ml-kit — bundled artifacts on Google Maven under Apache-2.0
```

## ZXing Core (com.google.zxing:core 3.5.3)

Used by the `vision` capability's QR decoder (P10-05, ADR-072). Isolated to `capabilities/vision/src/main/kotlin/app/autohost/capabilities/vision/QrDecoder.kt`.

```
Apache License 2.0 — Copyright 2010 ZXing authors
https://github.com/zxing/zxing — licensed under the Apache License, Version 2.0
```

## quickjs-kt (io.github.dokar3:quickjs-kt-android 1.0.3)

Used by the JavaScript runtime as the QuickJS engine binding (P11-02, ADR-076). Isolated to `app/src/main/kotlin/app/autohost/runtimejs/QuickJsEngineFactory.kt` — the only file importing `com.dokar.quickjs.*`.

```
Apache License 2.0 — Copyright Dokar (dokar3) and quickjs-kt contributors
https://github.com/dokar3/quickjs-kt — licensed under the Apache License, Version 2.0
Bundles QuickJS (MIT) — Copyright 2017-2021 Charles Brunet / Bellard (https://bellard.org/quickjs/)
```

## Not used, by decision (carried from the second planning pack, 2026-11-03)

| Material | Reason |
|---|---|
| LiquidAI Leap SDK (`ai.liquid.leap:leap-sdk`) | Proprietary "Leap Terms of Use" (seen in ClosePaw's build files); never a dependency |
| ClosePaw name, paw glyph and branding | Apache-2.0 grants no trademark rights; no branding asset is copied |
| ARIA RL/IRL/LoRA training, orchestration layer, Expo remnants and build files | Not needed or conflicting; ARIA has no LICENSE (D-17) |
| Model weights | Never bundled; see `docs/LOCAL_INFERENCE.md` |
| The second planning pack's staged copies of ARIA and ClosePaw files (the ai, app, core, native and runtime folders under `archive/pack-B`, in subfolders named imported) | Verbatim copies, never adapted or built; the ARIA files have no LICENSE. Not part of the product; delete `archive/` before any public release |

## Adaptation ledger (v4)

This ledger is **generated from the `adapt` rows of `docs/REUSE_MATRIX.md`** (13 rows at the time of writing). **R7 update (2026-11-06): SEVEN rows are ADOPTED** — the adapted files exist in the tree, each carrying the ClosePaw attribution header verbatim, with the ported upstream test and the tracker evidence alongside (`docs/REUSE_MATRIX.md` §0's rule): **RM-A2** (`GestureInjector.kt`), **RM-A3** (`HotspotRefiner.kt`), **RM-A4** (`ChangeDetector.kt`), **RM-B1** (`AppSensitivity.kt` + the `app_sensitivity.json` resource), **RM-B2** (`ToolPolicy.kt`), **RM-C2** (`StuckDetector.kt`), **RM-D1** (`BaseUrlValidator.kt`). The upstream paths stay in the rows below — `tools/licence_check.py` requires every adapted file's upstream path to have a row here. The RM-E* rows remain planned (Phase 16, NO-GO — dormant).

Project tags: **CP** = ClosePaw, **OD** = opendroid.

| Row | Upstream (project path · lines) | Licence | Planned AutoHost destination | Status |
|---|---|---|---|---|
| RM-A1 | CP `platform/NodeActionPerformer.kt` · 383 | Apache-2.0 | `capabilities/a11y/.../Actions.kt` + new pure helpers | PARTIALLY ADOPTED — the set-text verify order/NodeHandle seam + `ImeEnterPlan` derive from the row (P12-05/P12-10); `Actions.kt` keeps its own lineage (no verbatim adaptation); the ported set-text cases live in `SetTextOrderTest` |
| RM-A2 | CP `platform/AccessibilityGestureInjector.kt` · 175 | Apache-2.0 | `Actions.kt` gesture path | ADOPTED — `GestureInjector.kt` carries the attribution header (P12-06); `GestureInjectorTest` 7 |
| RM-A3 | CP `tool/action/PointActionExecutorCore.kt` · 326; `ClickExecutor.kt` · 52 | Apache-2.0 | `capabilities/a11y` | ADOPTED — `HotspotRefiner.kt` carries the attribution header (P12-09); `HotspotRefinerTest` 7 |
| RM-A4 | CP `tool/action/UiChangeDetector.kt` · 157 | Apache-2.0 | new pure `ChangeDetector` + state-aware fingerprint replacing `SettlePolicy.fingerprint` (`index:id:text:desc:bounds`) | ADOPTED — `ChangeDetector.kt` carries the attribution header (P12-03); `ChangeDetectorTest` 7 + the `SettlePolicy` delegation |
| RM-B1 | CP `tool/AppClassifier.kt` · 136 + data `app/src/main/assets/security/app_tiers.json` · 128 lines (116 package entries: 35 BLOCKED, 5 CAUTIOUS, 76 NORMAL — counted by parsing the JSON) | Apache-2.0 (+ NOTICE line) | `:runtime:agent/.../AppSensitivity.kt` + resource `app_sensitivity.json` | ADOPTED — `AppSensitivity.kt` + the `app_sensitivity.json` resource carry the header (P13-08); `AppSensitivityTest` incl. the RM-G2 security/override ports |
| RM-B2 | CP `tool/PolicyEngine.kt` · 181 | Apache-2.0 | `:runtime:agent/.../ToolPolicy.kt` | ADOPTED — `ToolPolicy.kt` carries the attribution header (P13-11); `ToolPolicyTest` incl. the RM-G2 PolicyEngine port |
| RM-C2 | CP `agent/cognition/policy/LoopDetectionPolicy.kt` · 55 (test `app/src/test/kotlin/ai/closepaw/agent/cognition/policy/LoopDetectionPolicyTest.kt`) | Apache-2.0 | `:runtime:agent/.../StuckDetector.kt` | ADOPTED — `StuckDetector.kt` carries the attribution header (P13-10); `StuckDetectorTest` is the RM-G2 LoopDetectionPolicyTest port |
| RM-D1 | CP `llm/OtherBaseUrlValidator.kt` · 91 (test `app/src/test/kotlin/ai/closepaw/llm/OtherBaseUrlValidatorTest.kt`) | Apache-2.0 | `:core:llm/.../BaseUrlValidator.kt` | ADOPTED — `BaseUrlValidator.kt` carries the attribution header (P13-03); `BaseUrlValidatorTest` (the 34 shared vectors) is the RM-G2 port |
| RM-E1 | OD `core/llm/ModelArtifactIntegrity.kt` · 564 (test · 445) | Apache-2.0 (OD edit to §4(d) noted) | `:llm` process module (or `:core:llm`) | PLANNED — Phase 16 (NO-GO; dormant) |
| RM-E2 | OD `core/llm/ModelDownloadWorker.kt` · 386; `ModelDownloadRetryPolicy.kt`, `ModelStoragePaths.kt` (+ tests) | Apache-2.0 | P16-03 | PLANNED — Phase 16 (NO-GO; dormant) |
| RM-E3 | OD `core/llm/PromptBudget.kt` · 36 (test · 67) | Apache-2.0 | `:llm` process | PLANNED — Phase 16 (NO-GO; dormant) |
| RM-E8 | OD `docs/qa/qwen-litert-test-plan.md` | Apache-2.0 | P16-01 | PLANNED — Phase 16 (NO-GO; dormant) |
| RM-G2 | CP `app/src/test/kotlin/ai/closepaw/{platform/NodeActionPerformerTest, agent/cognition/policy/LoopDetectionPolicyTest, llm/OtherBaseUrlValidatorTest, llm/CloudStreamRetryPolicyTest, tool/PolicyEngineTest, tool/AppClassifierSecurityTest, tool/AppClassifierOverrideTest, tool/action/TargetResolverTest}.kt` | Apache-2.0 | the owning task | PARTIALLY ADOPTED — the ported tests landed with their units (`BaseUrlValidatorTest`, `AppSensitivityTest`, `StuckDetectorTest`, `ToolPolicyTest`, `SetTextOrderTest`); TargetResolverTest is N/A (RM-A5 reference-only); the NodeActionPerformer remainder is N/A (no such unit — the provider conformance tables cover it) |

**ClosePaw** — Apache-2.0; its `LICENSE` and `NOTICE` carry "Copyright 2026 ClosePaw Authors". Every ClosePaw-derived file must carry the attribution line from that `NOTICE` (verbatim copies of `LICENSE` and `NOTICE` are kept under `reference/closepaw/` for this purpose); the dependency list inside that `NOTICE` applies to us only if we take the dependency (none is taken).

**opendroid** — Apache-2.0 text with one word changed in §4(d) ("if provided" → "if distributed"), a header "OpenDroid Contributors", no `NOTICE`; the repository is a fork. Treated as Apache-2.0 at low risk; adaptations are kept small and each keeps its upstream header. Re-check the licence text before the first adaptation.

**Not eligible:** android-agent (no `LICENSE` in the archive), sannabotapp (README says "MIT" but no `LICENSE` file; proprietary Picovoice wake-word dependency), ARIA (no `LICENSE`; owner UNVERIFIED). `tools/licence_check.py` is extended by X-11 to fail on an upstream-path header with no row here.

## Inspiration, no code taken (added 2026-11-13)

| Project | Licence | What we took | Where |
|---|---|---|---|
| OpenTasker (SysAdminDoc) | MIT, "Copyright (c) 2026 SysAdminDoc" | The idea of a small profile (context) + task (steps) split and a compact editor. **No code copied**: macros compile to AutoHost's own flow runtime and trigger rules. If code is ever adapted, it gets the §0 header and a row above and this MIT notice is reproduced. | `app/src/main/kotlin/app/autohost/ui2/macro/` |
| Brahma-Echo | Source-available; forbids rebranding and publishing modified versions under another identity | Nothing. Read for ideas only; it is a Windows desktop assistant, a different product. | — |
