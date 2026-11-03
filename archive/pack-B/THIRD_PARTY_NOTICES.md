# THIRD_PARTY_NOTICES
Record every third-party code **actually reused** (copied or adapted). Libraries pulled in by Gradle/pip are covered by their own metadata, but list any with unusual terms.

| Component | Source / commit | Licence | Where used | Notice text preserved? |
|---|---|---|---|---|
| _(none yet)_ | | | | |

## Staged sources from ARIA and ClosePaw (copied verbatim, 2026-10-26; none is adapted or built yet)
Per-file provenance — archive, original path, SHA-256 — is in `docs/migration-manifest.tsv`; the archives' own SHA-256 are in `reference/README.md`. When a file is adapted, add its row to the table at the top of this file and keep the original header.

| Row | Component | Source (archive, original path family) | Licence | Staged at | Files | Status | Notice obligations |
|---|---|---|---|---|---|---|---|
| R-06 | llama.cpp JNI bridge and native build | ARIA (`aria-ai-cpu-only-main.zip`) | ARIA (no licence file; locked until D-19 is confirmed, P0-40); llama.cpp is MIT | `native/llama-jni/imported/` | 4 | staged verbatim, not integrated | Owner confirmation first (D-19); then add the owner's licence header |
| R-07 | LlamaEngine Kotlin wrapper | ARIA (`aria-ai-cpu-only-main.zip`) | ARIA (no licence file; locked until D-19 is confirmed, P0-40) | `ai/local/imported/` | 1 | staged verbatim, not integrated | Owner confirmation first (D-19); then add the owner's licence header |
| R-08 | Model catalog, download and storage management | ARIA (`aria-ai-cpu-only-main.zip`) | ARIA (no licence file; locked until D-19 is confirmed, P0-40); model weights carry their own licences | `ai/local/imported/` | 3 | staged verbatim, not integrated | Owner confirmation first (D-19); then add the owner's licence header |
| R-09 | Thermal and sustained-performance guard for inference | ARIA (`aria-ai-cpu-only-main.zip`) | ARIA (no licence file; locked until D-19 is confirmed, P0-40) | `ai/local/imported/` | 3 | staged verbatim, not integrated | Owner confirmation first (D-19); then add the owner's licence header |
| R-10 | Cloud LLM clients (OpenAI-compatible) | ClosePaw 0.1.0 (`closepaw-0_1_0.zip`) | Apache-2.0 (ClosePaw); depends on the Apache-2.0 `openai-java` SDK | `ai/cloud/imported/` | 25 | staged verbatim, not integrated | Keep Apache-2.0 headers; carry `NOTICE`; mark changes; no ClosePaw name or mascot |
| R-11 | Turn loop building blocks (error classification, loop detection) | ClosePaw 0.1.0 (`closepaw-0_1_0.zip`) | Apache-2.0 (ClosePaw) | `runtime/agent/imported/` | 8 | staged verbatim, not integrated | Keep Apache-2.0 headers; carry `NOTICE`; mark changes; no ClosePaw name or mascot |
| R-13 | Sensitive-app tier policy (banking/authenticator/wallet hard-block) | ClosePaw 0.1.0 (`closepaw-0_1_0.zip`) | Apache-2.0 (ClosePaw) | `core/model/imported/` | 8 | staged verbatim, not integrated | Keep Apache-2.0 headers; carry `NOTICE`; mark changes; no ClosePaw name or mascot |
| R-14 | Run traces and evidence recorder | ClosePaw 0.1.0 (`closepaw-0_1_0.zip`) | Apache-2.0 (ClosePaw) | `runtime/agent/imported/` | 8 | staged verbatim, not integrated | Keep Apache-2.0 headers; carry `NOTICE`; mark changes; no ClosePaw name or mascot |
| R-17 | Floating overlay and status capsule | ClosePaw 0.1.0 (`closepaw-0_1_0.zip`) | Apache-2.0 (ClosePaw); Apache-2.0 does not grant the ClosePaw name or paw mascot | `app/imported/` | 31 | staged verbatim, not integrated | Keep Apache-2.0 headers; carry `NOTICE`; mark changes; no ClosePaw name or mascot |
| R-18 | Design system (tokens, type, motion) and fonts | ClosePaw 0.1.0 (`closepaw-0_1_0.zip`) | Apache-2.0 (code); fonts are third-party (see FONT_ATTRIBUTION.md, verify licence text before shipping) | `core/designsystem/imported/` | 16 | staged verbatim, not integrated | Keep Apache-2.0 headers; carry `NOTICE`; mark changes; no ClosePaw name or mascot |

### Not used, by decision
| Material | Reason |
|---|---|
| LiquidAI Leap SDK (`ai.liquid.leap:leap-sdk`) | Proprietary "Leap Terms of Use"; never a dependency (ADR-093) |
| ClosePaw name, paw glyph and branding | Apache-2.0 grants no trademark rights; `StatusPawGlyph.kt` is not staged |
| ARIA RL/IRL/LoRA, orchestration, Expo remnants and build files | Not needed or conflicting (R-23–R-25) |
| Model weights | Never bundled; terms shown before download (X-09) |

Rules: Apache-2.0 and MIT code only. **Never** add code from AGPL/GPL projects (Mobilerun Portal, OpenAutoJs). ARIA files stay locked until D-19 is CONFIRMED. See `docs/REFERENCES.md` §0 and §4–§5.

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
