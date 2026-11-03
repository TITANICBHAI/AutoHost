# VERSIONS
Chosen for compatibility, not novelty. Every entry existed on Maven/Google Maven when chosen; the owner's first Gradle run confirms them.

| Component | Version | Notes |
|---|---|---|
| Gradle | 8.13 | Pinned by the bootstrap (`scripts/android-build-env.sh:109`, sha256-verified at the 2026-10-30 owner build) and installed by CI (`gradle/actions/setup-gradle` with `gradle-version: 8.13` in both workflows). X-07 (2026-11-03) chose the documented-pin route over committing a wrapper jar (ADR-096(b) offers either); the local Windows toolchain mirrors it at `C:/Users/DELL/.cache/autohost/gradle-8.13`. If the pin ever moves, change the bootstrap AND both workflows in the same commit |
| Android Gradle Plugin | 8.13.0 | Chaquopy 17 supports AGP 7.3–9.2 |
| Kotlin | 2.2.21 | Compose compiler plugin has the same version |
| Chaquopy | 17.0.0 | Python 3.13 in the app; needs a build-machine Python 3.13 on PATH |
| compileSdk / targetSdk / minSdk | 36 / 36 / 29 | minSdk 29 = Android 10 (ADR-002) |
| JDK for the build | 17 or 21 | bytecode target 17 |
| Compose BOM | 2025.10.01 | Material 3; stays on compileSdk 36 and AGP 8 |
| kotlinx.coroutines | 1.10.2 | |
| kotlinx.serialization | 1.9.0 | |
| AndroidX activity-compose / core-ktx | 1.11.0 / 1.17.0 | |
| ktlint Gradle plugin | 12.1.2 | `org.jlleitschuh.gradle.ktlint`, android mode; bundles ktlint 1.3.x; style file `.editorconfig` (ADR-030) |
| detekt | 1.23.8 | `io.gitlab.arturbosch.detekt`, build-upon-default; config `config/detekt/detekt.yml` (ADR-030) |
| Room | 2.8.2 | `:core:data`; KSP with exported schemas in `core/data/schemas/` |
| WorkManager | 2.10.0 | `:core:engine` (P4-05/06): fallback backend + periodic reconcile worker |
| KSP | 2.2.21-2.0.3 | Room annotation processing — pin may need adjusting to the exact KSP build for Kotlin 2.2.21 (first-build risk, HANDOFF sec.6) |
| JUnit | 4.13.2 | JVM unit tests |
| Python (build + app) | 3.13 | stdlib-only SDK also runs on 3.10+ |
| Shizuku API | 13.1.5 | `dev.rikka.shizuku:api` (P9-04, Apache-2.0); Maven Central; isolated to `app/shell/ShizukuProcessExec.kt` (ADR-064) — only `:app` depends on it |

Newer releases exist; bump deliberately, one at a time, after the first green build.
| ML Kit text-recognition | 16.0.1 | `com.google.mlkit:text-recognition` (P10-05, Apache-2.0); bundled OCR — isolated to `OcrEngine.kt` (ADR-072) |
| ZXing core | 3.5.3 | `com.google.zxing:core` (P10-05, Apache-2.0); QR decode — isolated to `QrDecoder.kt` (ADR-072) |
| quickjs-kt (Android) | 1.0.3 | `io.github.dokar3:quickjs-kt-android` (P11-02, Apache-2.0); the QuickJS engine for the JS runtime — isolated to `app/runtimejs/QuickJsEngineFactory.kt` (ADR-076); the binding's exact API is owner-verified at the first Gradle build (fix that one file only if the surface drifted) |

## v4 notes (planning pass 2026-11-01 — no pin was changed, no dependency was added; nothing was built)
- **Chaquopy:** the pin stays **17.0.0** (`gradle/libs.versions.toml:7`). **17.1.0** exists (changelog dated 2026-10-12: Python 3.15 added, runtime Pythons 3.10.22–3.15.0, `.so` files made read-only before loading, a Gradle-10 deprecation fix; source: Chaquopy changelog read 2026-10-25). Not adopted: bump only after the first green build, one change at a time (Epoch V, X-09). Minimum `minSdk` for Chaquopy 17.x is 24 (we use 29). Python 3.13 app builds need a build-machine Python 3.13.
- **AGP range wording:** the table above says Chaquopy 17 supports AGP 7.3–9.2; Chaquopy's 17.0.0 changelog (read 2026-10-25) says 7.3–9.4. AGP 8.13.0 is inside both. UNVERIFIED by a build here.
- **Gradle 8.14.3:** Chaquopy's docs state no Gradle minimum or maximum; the constraint comes from AGP. The wrapper is still absent from the checkout — X-07 adds it (ADR-096).
- **Candidates, not pinned, not in the version catalog (UNVERIFIED):** LiteRT-LM (opendroid uses `com.google.ai.edge.litertlm:litertlm-android:0.14.0`; licence, API floor and 16 KB status unchecked) and llama.cpp (MIT, "Copyright (c) 2023-2026 The ggml authors"). Both belong to Phase 16 and only after spike S6 (`docs/LOCAL_INFERENCE.md`). ML Kit GenAI Prompt (proprietary) is not a candidate.
- **16 KB page size:** Chaquopy 17.0.0 supports 16 KB devices itself; many older Android wheels may still fail to load; Python 3.13+ is recommended — we use 3.13. Google Play's 16 KB requirement for apps targeting Android 15+ was researched as applying from 2027-02-01 — **verify the current rule before acting**. Per-library audit is P15-05 / G15-c.
- **Agent runtime:** `:core:llm` and `:runtime:agent` are planned pure-JVM modules with no new third-party dependency (the gateway uses `HttpURLConnection`, ADR-090); no version rows are needed until Phase 13 adds code.

## Observed in the reference projects (not adopted; for compatibility checks only)
*(Carried from the second planning pack on 2026-11-03; read there from the project build files, not re-verified here. The ARIA archive's `llama.cpp` directory was empty, so llama.cpp stays UNPINNED.)*
| Project | Observed | Notes |
|---|---|---|
| ARIA | AGP 8.8.0, Kotlin 2.0.21, NDK 27.1.12297006, compileSdk/targetSdk 35, minSdk 29, Compose BOM 2024.09.00, OkHttp 4.12.0, ML Kit text-recognition 16.0.1, MediaPipe tasks-vision 0.10.14, ONNX Runtime (Android) | Groovy Gradle with React Native/Expo remnants; arm64-v8a only; two hard-coded Windows paths |
| ClosePaw | compileSdk/targetSdk 36, minSdk 31, Compose BOM 2024.12.01, openai-java 4.14.0, OkHttp 4.12.0, kotlinx.serialization 1.6.3, coroutines 1.7.3, a build comment citing Kotlin 2.3.0, LiquidAI Leap SDK 0.9.2 | minSdk 31 is attributed to the Leap SDK, which is proprietary and excluded |
