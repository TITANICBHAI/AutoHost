# VALIDATION_REPORT — what was checked, what was not (v4 planning pass)

*Run 2026-11-02 (UTC) in a Linux cloud sandbox. Companion: `docs/MIGRATION.md` (what changed). "Run" below means the command was executed and its output read; everything else is reading or prediction.*

**Bottom line.** The documents and the Python-side tooling are checked and agree with each other. **No Kotlin was compiled, no JVM test was run, and nothing ran on Android.** Every statement about how AutoHost or a reference project behaves on a device is a prediction from reading code, and is labelled that way in the documents.

## 1. Environment

| Item | Value |
|---|---|
| Python / PyYAML | 3.13.17 / 6.0.3 (the repo script builds its own venv from `sdk-python/requirements-test.txt`) |
| Node | v22.23.4 |
| JDK / Gradle | OpenJDK 21.0.12.1 / Gradle 8.14.3 — installed, **unusable here** (§3) |
| Android SDK, `adb`, `sdkmanager`, `kotlinc` | absent |
| Git | none in the delivered tree; the script run below used a throwaway `git init` copy because `scripts/test-python.sh` compares generated files with the Git index |

## 2. Checks run

| # | Check | Result |
|---|---|---|
| 1 | `bash scripts/test-python.sh` — the canonical Python entry point (ADR-096), in a scratch copy with a throwaway git index | **exit 0**, run twice with identical results (on the working tree, then on the unzipped delivered archive); it runs rows 2–10 |
| 2 | SDK + simulator suite: `cd sdk-python && python3 -m unittest discover -s tests -t tests -p "test_*.py"` | Ran **415** tests — OK |
| 3 | Tool and schema suite: `python3 -m unittest discover -s tests -p "test_*.py"` | Ran **75** tests — OK (includes the ladder tests in `tests/test_progress.py` and the 12 in `tests/test_doc_check.py`) |
| 4 | `python3 tools/progress.py --check` | `progress check: ok` |
| 5 | `python3 tools/gen_artifacts.py --check` | `generated artifacts in sync` |
| 6 | `python3 tools/sync_sdk.py --check` | `SDK copy in sync` |
| 7 | `tools/gen_flow_vectors.py` and `tools/gen_schedule_vectors.py` | 44 and 43 cases; regenerated files identical to the Git index (earlier also `cmp`-identical to the attached tree) |
| 8 | `python3 tools/licence_check.py` | `licence check ok — 25 catalog dependencies, all covered` |
| 9 | `python3 tools/doc_check.py` | `doc_check: ok (50 files)` — references agree; it does **not** check that claims are true |
| 10 | `python3 tools/mcp_eval.py` | PASS `author-run.yaml`, `failure-export.yaml`, `inspect-device.yaml` |
| 11 | Application code untouched — `diff -rq` against the delivered tree | only the files in `docs/MIGRATION.md` §2 differ; 0 differences under `app/`, `core/`, `runtime/`, `capabilities/`, `plugin-sdk/`, `sdk-python/`, `samples/`, `schemas/`, `testing/`, `gradle/`, `spikes/`, `config/`, `.github/` and the root build files |
| 12 | `reference/` snapshots | all 35 verbatim files compared byte-for-byte with the extracted upstream archives: 35/35 identical; the 35 sha256 + size values in `reference/INDEX.md` recomputed: 35/35 match; the other 12 files are the planner's notes, lists and index documents |
| 13 | Reuse-matrix upstream paths | 83 backticked file paths in `docs/REUSE_MATRIX.md` resolved against the extracted archives: 81 resolve; **1 was wrong and is fixed** (RM-E5 named `app/build.gradle.kts`; opendroid's file is `app/build.gradle`, and line 263 pins `litertlm-android:0.14.0`); 1 is AutoHost's own `reference/findings/opendroid.md`, which exists and which the checker mis-attributed |
| 14 | AutoHost line citations, spot-checked | `app/build.gradle.kts:24` is `flavorDimensions += "tier"`; `AppGraph.kt:163` and `:340` are `GrantBuilder(permissionIndex, devMode = true)` (in the tree the planning pass was given; the crash-fix pass of 2026-11-03 moved them to `:170` and `:347`); `gradle/libs.versions.toml:7` is `chaquopy = "17.0.0"` |
| 15 | Language counts, recounted on the delivered tree | Kotlin 466 files / 60,323 lines (main 367 / 45,647; tests 99 / 14,676); Python 160 / 27,837 (on-device SDK 42 / 7,060); JavaScript 4 / 827; C/C++ 0. Matches `docs/LANGUAGE_WEIGHT.md`. **Stale figures of my own were found and fixed** (ADR-093; the Python rows in `docs/LANGUAGE_WEIGHT.md`) |
| 16 | Tracker size-parser change | owner's `progress.py` on the owner's tracker: 2/723; fixed tool on the same unchanged tracker: 2/726; with the new phases: 2/914 |

Tracker after this pass (generated): Verified 0% (weighted 2/914) · Tasks 2/285 · Gate items 0/94 · Capabilities 0/46 · Ladder: planned 245, implemented 175, compiled 0, unit-tested 3, integration-tested 0, verified 2, real-device-verified 0/18, failing 8.

## 3. Checks not run, and why

| Not run | Why |
|---|---|
| Any Gradle task — compile, JVM unit tests, lint, detekt, `assembleDebug` | Maven Central, Google Maven, the Gradle plugin portal and `services.gradle.org` were all unreachable from the sandbox (curl http_code 000; for Maven Central the sandbox proxy answered `CONNECT tunnel failed, response 403`), so no dependency or plugin could be resolved; no task was started. The tree also has no Gradle wrapper and the sandbox has no Android SDK |
| The 466 Kotlin files, including 99 test files | follows from the row above. The eight `(FAILING)` tags in the tracker (P3-01, P3-23, P4-06, P4-16, P5-07, P7-01, P7-11, P11-02) are the owner's own results and are **neither reproduced nor disproved**. Matching golden vectors from a Python generator do not prove Kotlin parity |
| Instrumented tests, emulator, real devices | no devices or emulator images; real-device-verified is 0/18 by construction |
| Phases 12–16 | nothing exists to run. The stub-model harness (RM-G1), agent evals and the failing-test-first tests for findings A1–A9 are tracker tasks, not artifacts |
| JavaScript runtime | QuickJS arrives as an Android AAR; the four JS files were counted, not executed |
| Start-up time, resident memory, APK size, CPU, battery, thermals | Phase 15 and spikes S6/S7 exist to measure them; no number is claimed |
| Model gateway network behaviour | not built |

## 4. Claims that rest on reading, not running

- **Static findings about the owner's code** — A1 (set-text stale read-back), A2 (click "verified" means accepted), A3 (fire-and-forget gestures), A4 (scroll defaults of 1080×2400), A5 (coordinate fallback), A6 (`method` enum drift), A7 (inert run scoping in `NodeRegistry`), A8 (`allow_coordinates`/`allow_global` unenforced), A9 (unscoped `AppProvider`), dev-mode grant-all, wall-clock timeout only in the JS runtime, `WAITING_USER` semantics. Each is a prediction until its failing test is written and run. The network-security-config drift is a fact about the tree (grep and `ls`), not about runtime behaviour.
- **Reference projects** — read, never built or run. ClosePaw, opendroid, android-agent, sannabotapp and ARIA behaviour is what their source and their own documents say; ARIA's documents claim more than its code shows.
- **Licences** — my reading of the licence files in the archives, not legal advice. ARIA has no licence file and its owner is unverified; sannabotapp's README says "MIT" with no licence file; android-agent has none; opendroid's licence text edits a phrase of Apache-2.0 §4(d).
- **External facts** — Chaquopy versions and artifact sizes, the Google Play 16 KB page-size rule, LiteRT-LM's licence: fetched from web pages on the dates stated in the documents. Still UNVERIFIED: LiteRT-LM's minimum API and ABIs, per-wheel 16 KB behaviour under Chaquopy, how non-OpenAI vendors behave on OpenAI-compatible endpoints, Android 15 `dataSync` limits.
- **Numbers** — every budget, threshold and limit in `docs/AGENT_RUNTIME.md`, `docs/MODEL_GATEWAY.md` and spike S6 is a placeholder to be tuned by measurement.
- **Devices** — the Realme C3 (Android 11) and Galaxy M31 (Android 12) are owner-stated; their RAM and display values are unverified, and older text still says "Android 10".

## 5. Errors found and fixed during this pass

- Wrong upstream path in RM-E5 (row 13) and stale Kotlin/Python line counts (row 15).
- The X-09 task title contained a literal failing tag, which the tracker parser counted as a failing item; reworded.
- Three tasks tagged failing for a missing PyYAML were re-run with it present and retagged `(UNIT-TESTED)` — unticked, see `docs/MIGRATION.md` §5.
- `tools/doc_check.py` first flagged references that were legitimate (upstream paths quoted from reference projects, files the owner's docs name that were already missing); each exception is in its `KNOWN_MISSING` table with a reason, and six real documents that were missing from the `docs/README.md` index were added.
- Drift that was already in the owner's tree is listed in `docs/MIGRATION.md` §7 and was not changed.

## 6. Process notes

- Delegated helper agents hit the provider usage limit twice and wrote nothing (checked by file modification times). All content was therefore written serially, and some documents are terser than first planned.
- The Gemini and GLM plans were read earlier in the work and not re-read in the final pass; `docs/REFERENCES.md` §4 attributes only what could be verified from the GPT bundles.

## 7. Reproduce

```bash
python3 tools/doc_check.py && python3 tools/progress.py --check
bash scripts/test-python.sh        # needs a git index: git init && git add -A && git commit
diff -rq <delivered-tree> . -x __pycache__ -x .pytest_cache -x '*.pyc'
```

Next verification step for the owner: `docs/PROGRESS.md` Epoch V (X-07…X-09) — the first Gradle build and JVM sweep on a machine that can reach the Maven repositories.
