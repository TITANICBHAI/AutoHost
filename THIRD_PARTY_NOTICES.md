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

## Planned adaptations (v4 — NONE adopted yet)

This ledger is **generated from the `adapt` rows of `docs/REUSE_MATRIX.md`** (13 rows at the time of writing) and records what *may* be copied or adapted later. **Nothing in it has been copied into the tree.** A row moves to the table at the top of this file only in the change that adds the adapted file, together with the header comment, the ported upstream test and the tracker evidence (`docs/REUSE_MATRIX.md` §0). Only Apache-2.0 files that carry a LICENSE in their own tree (ClosePaw, opendroid) are eligible; android-agent (no licence), sannabotapp (README-only "MIT", proprietary wake-word model) and ARIA (no licence) are ideas-only.

Project tags: **CP** = ClosePaw, **OD** = opendroid.

| Row | Upstream (project path · lines) | Licence | Planned AutoHost destination | Status |
|---|---|---|---|---|
| RM-A1 | CP `platform/NodeActionPerformer.kt` · 383 | Apache-2.0 | `capabilities/a11y/.../Actions.kt` + new pure helpers | PLANNED — not adopted |
| RM-A2 | CP `platform/AccessibilityGestureInjector.kt` · 175 | Apache-2.0 | `Actions.kt` gesture path | PLANNED — not adopted |
| RM-A3 | CP `tool/action/PointActionExecutorCore.kt` · 326; `ClickExecutor.kt` · 52 | Apache-2.0 | `capabilities/a11y` | PLANNED — not adopted |
| RM-A4 | CP `tool/action/UiChangeDetector.kt` · 157 | Apache-2.0 | new pure `ChangeDetector` + state-aware fingerprint replacing `SettlePolicy.fingerprint` (`index:id:text:desc:bounds`) | PLANNED — not adopted |
| RM-B1 | CP `tool/AppClassifier.kt` · 136 + data `app/src/main/assets/security/app_tiers.json` · 128 lines (116 package entries: 35 BLOCKED, 5 CAUTIOUS, 76 NORMAL — counted by parsing the JSON) | Apache-2.0 (+ NOTICE line) | `:runtime:agent/.../AppSensitivity.kt` + resource `app_sensitivity.json` | PLANNED — not adopted |
| RM-B2 | CP `tool/PolicyEngine.kt` · 181 | Apache-2.0 | `:runtime:agent/.../ToolPolicy.kt` | PLANNED — not adopted |
| RM-C2 | CP `agent/cognition/policy/LoopDetectionPolicy.kt` · 55 (test `app/src/test/kotlin/ai/closepaw/agent/cognition/policy/LoopDetectionPolicyTest.kt`) | Apache-2.0 | `:runtime:agent/.../StuckDetector.kt` | PLANNED — not adopted |
| RM-D1 | CP `llm/OtherBaseUrlValidator.kt` · 91 (test `app/src/test/kotlin/ai/closepaw/llm/OtherBaseUrlValidatorTest.kt`) | Apache-2.0 | `:core:llm/.../BaseUrlValidator.kt` | PLANNED — not adopted |
| RM-E1 | OD `core/llm/ModelArtifactIntegrity.kt` · 564 (test · 445) | Apache-2.0 (OD edit to §4(d) noted) | `:llm` process module (or `:core:llm`) | PLANNED — not adopted |
| RM-E2 | OD `core/llm/ModelDownloadWorker.kt` · 386; `ModelDownloadRetryPolicy.kt`, `ModelStoragePaths.kt` (+ tests) | Apache-2.0 | P16-03 | PLANNED — not adopted |
| RM-E3 | OD `core/llm/PromptBudget.kt` · 36 (test · 67) | Apache-2.0 | `:llm` process | PLANNED — not adopted |
| RM-E8 | OD `docs/qa/qwen-litert-test-plan.md` | Apache-2.0 | P16-01 | PLANNED — not adopted |
| RM-G2 | CP `app/src/test/kotlin/ai/closepaw/{platform/NodeActionPerformerTest, agent/cognition/policy/LoopDetectionPolicyTest, llm/OtherBaseUrlValidatorTest, llm/CloudStreamRetryPolicyTest, tool/PolicyEngineTest, tool/AppClassifierSecurityTest, tool/AppClassifierOverrideTest, tool/action/TargetResolverTest}.kt` | Apache-2.0 | the owning task | PLANNED — not adopted |

**ClosePaw** — Apache-2.0; its `LICENSE` and `NOTICE` carry "Copyright 2026 ClosePaw Authors". Every ClosePaw-derived file must carry the attribution line from that `NOTICE` (verbatim copies of `LICENSE` and `NOTICE` are kept under `reference/closepaw/` for this purpose); the dependency list inside that `NOTICE` applies to us only if we take the dependency (none is taken).

**opendroid** — Apache-2.0 text with one word changed in §4(d) ("if provided" → "if distributed"), a header "OpenDroid Contributors", no `NOTICE`; the repository is a fork. Treated as Apache-2.0 at low risk; adaptations are kept small and each keeps its upstream header. Re-check the licence text before the first adaptation.

**Not eligible:** android-agent (no `LICENSE` in the archive), sannabotapp (README says "MIT" but no `LICENSE` file; proprietary Picovoice wake-word dependency), ARIA (no `LICENSE`; owner UNVERIFIED). `tools/licence_check.py` is extended by X-11 to fail on an upstream-path header with no row here.
