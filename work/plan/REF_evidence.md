# REF — verified findings (file:line in the repo as uploaded, 2026-11-07)

Tags: **V** = read the code/ran it · **I** = follows from the code, not seen running.

## CI and tests
| # | Finding | Where | Tag |
|---|---|---|---|
| 1 | CI = 21 sequential Gradle runs, 150-min limit; data and flow steps are `continue-on-error`; run 22 hit 100 min | `.github/workflows/jvm-capabilities.yml`, `ci report.md` | V |
| 2 | CI runs `gradle :app:test` (all 8 flavor×type variants) and `:lib:test` (debug+release) | same | V (flavors) / I (AGP) |
| 3 | `:runtime:agent` (175 tests) and `:core:llm` (36) are in no workflow; `:runtime:python` has 0 JVM tests | workflows, test dirs | V |
| 4 | **data hang:** `SmsProvider.wait_for` loops on the clock with a real `delay(250)`; the test clock never advances | `SmsTelephonyProviders.kt:~135`, `DataClusterTest.kt:201,240` | V |
| 5 | **flow hang:** test resumes a paused run from a virtual-time coroutine while `checkpoint()` blocks the thread | `FlowRuntimeProviderTest.kt` (pause test), `FlowRuntimeProvider.kt:191–243` | V |
| 6 | Manifest has `allowBackup=false` but never references `dataExtractionRules`/`fullBackupContent`; `ReleaseHardeningTest` asserts it | `AndroidManifest.xml:34`, `res/xml/*rules.xml` | V |
| 7 | FileProvider exposes all of `files/` (incl. `files/adb` key dir) | `file_paths.xml`, `AppGraph.kt:889` | V |
| 8 | No workflow runs ktlint/detekt/lint although CONVENTIONS says `check` must pass; tasks `ktlintCheck`, `detekt` exist | root `build.gradle.kts` | V |
| 9 | The Python runner exists twice, kept identical by `tools/sync_sdk.py` (CI checks) | `sdk-python/autohost/`, `runtime/python/…/autohost/` | V |
| 10 | 14 files use CRLF (e.g. `testing/golden/flow_vectors.json`, `schemas/task.schema.json`); the generator writes LF so ci.yml's golden "drift" step can show a diff on a fresh checkout | `tools/m0_reset.py --check-eol` | V (bytes) / I (CI effect) |
| 11 | CI APK is **debug**-signed, `versionCode = 1`; Gradle already reads `AUTOHOST_KEYSTORE`, `_KEYSTORE_PASSWORD`, `_KEY_ALIAS`, `_KEY_PASSWORD` | `app/build.gradle.kts` | V |

## Process
| 12 | Tracker says "Phase 0 BLOCKED, verified 1 %" while CI shows most modules green; `AGENT_STATE.md` contradicts itself (git on/off) | `docs/PROGRESS.md`, `AGENT_STATE.md` | V |
| 13 | Six agent entry points disagree (README, READ_FIRST, KICKOFF, AGENTS, verify/AGENTS, .agents/memory) | root | V |
| 14 | 109 `.md` files outside `archive/`; root+docs markdown 11,080 lines; `archive/` 1,204 files ≈ 28 MB | repo | V |

## UI (code vs `docs/UI_SPEC.md`)
| 15 | **Task list hides installed tasks:** Library lists only `graph.devTasks` (adb folder). Templates/imports are saved to the database; the DB-backed `LibraryScreen(graph, …)` is never called. Tiles/widget/shortcut pickers also read only `devTasks` | `AppNavHost.kt:142,154–162`, `TaskScreens.kt:49`, `SurfacesScreen.kt:50,126,292`, `AppGraph.kt:1337,1642`; Recorder saves to the dev folder | V |
| 16 | Schedule editor = raw JSON; "+ New" only when the list is empty; no Schedule tab (file says "arrive later"); `timezone` defaults to `"UTC"` | `ScheduleScreen.kt:80–90,167–208`, `TaskScreens.kt:102,142–144`, `ScheduleRule.kt` | V |
| 17 | Task detail has no Schedule tab; triggers are on Overview in developer vocabulary | `TaskScreens.kt:142–144,192` | V |
| 18 | No `BackHandler`; navigation is an activity field; `MainActivity` has no `configChanges`, so rotation resets it | `MainActivity.kt:21,24`, manifest | V / I |
| 19 | Setup screen cannot scroll; `Row(fillMaxSize())` inside the column | `SetupScreen.kt:37,42` | V |
| 20 | Library header: six buttons in one row; header says "DEV-FOLDER TASKS" | `AppNavHost.kt:168–212` | V |
| 21 | No Home screen / 4-tab bar / active-runs bar that UI_SPEC S01/§3 specify; `RunCapsule` is hosted nowhere | `ui/`, `ui/capsule` | V |
| 22 | Config widgets: samples declare `"x-ui": {"widget": "app-picker"}`; `ConfigFormModel.Field` ignores `x-ui` (documented "placeholder … until the app list lands") | `ConfigForm.kt`, `samples/*/task.json` | V |
| 23 | Only 4 of 14 samples ship in the app | `assets/templates` | V |
| 24 | A task needs `task.json`; no in-app way to pick a script/folder; Studio asks for an id like `com.example.x` | `DevFolderTaskSource`, `Templates.kt`, `studio/` | V |
| 25 | `ImportActivity` unzips on the UI thread (`onCreate`→`reviewIntent`, `!!`), `runBlocking` in `install()`, `onReviewPermissions = null`; BROWSABLE `content`+`file` filter | `ImportActivity.kt:36–114`, manifest | V |
| 26 | 72 plain-language reason codes with `userMessage`+`fixAction` exist in `schemas/errors.json`; **no Kotlin loads them** (`ReasonCopy` from CONVENTIONS does not exist). Of 23 fix-action ids FixHub handles 8, **4 under another spelling**, and **11 not at all** (`SHOW_RESTRICTED_SETTINGS_HELP`, `EXPLAIN_ADVANCED_PROTECTION`, `SHOW_OEM_GUIDE`, `REQUEST_POST_NOTIFICATIONS`, `OPEN_OVERLAY_SETTINGS`, `OPEN_USAGE_ACCESS_SETTINGS`, `OPEN_APP_INFO_UNUSED_SETTINGS`, `REQUEST_PROJECTION_CONSENT`, `OPEN_AI_SETTINGS`, `SHOW_PC_COMMAND_SHELL`, `SHOW_PC_COMMAND_SECURE`). Nothing emits the restricted-settings code | `schemas/errors.json`, `FixHub.kt` | V |
| 27 | Python runner: a script without `main()` runs then fails (`AttributeError`); `print()` is not routed to the run log | `_runtime.py` | V |
| 28 | AI: OpenAI-compatible Chat Completions only, non-streaming (ADR-090); no presets; plain `http` only to localhost/127.0.0.1/10.0.2.2 (pinned by `ReleaseHardeningTest`) | `core/llm`, `AiSettingsScreen.kt` | V |
| 29 | `ChainRunner.await` polls with `Thread.sleep(200)` on a broker thread | `AppGraph.kt:1266` | V |
| 30 | Vision `TemplateMatcher`: the NCC formula is right; the test needle is ~a gradient like the background (pure-background windows score 0.9983–0.9984), and a strided full-res coarse scan misses distinct needles (0.776 at the wrong place); pooled coarse pass fixes it (oracle in `LATER.md`) | `TemplateMatcher.kt`, `VisionModuleTest.kt`; Python simulation | V |
