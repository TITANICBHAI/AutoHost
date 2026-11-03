# VERSIONS
Chosen for compatibility, not novelty. Every entry existed on Maven/Google Maven when chosen; the owner's first Gradle run confirms them.

| Component | Version | Notes |
|---|---|---|
| Gradle | 8.14.3 | No wrapper jar is committed (it is binary); CI installs this version; locally run `gradle wrapper --gradle-version 8.14.3` once |
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

## Observed in the reference projects (not adopted; for compatibility checks only)
| Project | Observed | Notes |
|---|---|---|
| ARIA | AGP 8.8.0, Kotlin 2.0.21, NDK 27.1.12297006, compileSdk/targetSdk 35, minSdk 29, Compose BOM 2024.09.00, OkHttp 4.12.0, ML Kit text-recognition 16.0.1, MediaPipe tasks-vision 0.10.14, ONNX Runtime (Android) | Groovy Gradle with React Native/Expo remnants; arm64-v8a only; two hard-coded Windows paths |
| ClosePaw | compileSdk/targetSdk 36, minSdk 31, Compose BOM 2024.12.01, openai-java 4.14.0, OkHttp 4.12.0, kotlinx.serialization 1.6.3, coroutines 1.7.3, a build comment citing Kotlin 2.3.0, LiquidAI Leap SDK 0.9.2 | minSdk 31 is attributed to the Leap SDK, which is proprietary and excluded |

## Pins still open (v4)
| Item | Status | Task |
|---|---|---|
| llama.cpp | **UNPINNED**: the ARIA archive's `llama.cpp` directory is empty; record the commit when the native build first succeeds | P0-41 |
| NDK | r27 if `native/llama-jni` ships (ARIA used 27.1.12297006) | P0-41 |
| Model client for `:ai:cloud` | `openai-java` 4.14.0 (ClosePaw's) or OkHttp + kotlinx.serialization; decide and record at P12-02 | P12-02 |
| ML Kit, MediaPipe, ONNX Runtime | deferred to Phase 10; licence terms unverified | P0-40, P10-05 |
| JDK / Gradle / Android SDK in the planning sandbox | none installed (a JRE only); nothing above was built here | P0-33 |
