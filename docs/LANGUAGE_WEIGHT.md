# LANGUAGE_WEIGHT — what each language costs on Android, what this tree carries, what to measure (v4)

*Status: analysis from static reading plus line counts taken on the owner's tree as delivered, 2026-11-01. **Nothing was compiled, installed or run on a phone.** Every runtime cost below is either sourced (URL), counted here, or labelled PREDICTED / UNMEASURED. Phase 15 (`docs/PROGRESS.md` P15-01…08, gates G15-a…d) produces the measurements; ADR-093 forbids percentage targets until then.*

## 1. The question and the short answer

The owner's complaint about the earlier plan was "uses python too much, cpu expensive". What this tree shows:

- **Python is not where the platform work happens.** Host, broker, accessibility, selector matching, run orchestration, schedules and UI are Kotlin (45,647 lines in 367 main files; 14,676 more lines of tests). Python on the device is the user-script SDK — 7,060 lines of envelope-client code — plus the embedded interpreter that runs user scripts.
- **What Python costs** is (1) the embedded interpreter's APK weight per ABI, (2) one worker process per Python run (start-up and resident memory), (3) the GIL (one Python thread executes at a time). None of the three has been measured on a phone — by us or, as far as the research found, by Chaquopy's own documentation.
- **Declarative flows, JS tasks and the v4 agent runtime run in the host process** with no interpreter and no worker process. Gate G6-a requires `ps` evidence that a flow run starts no `:w*` process; the agent (plan §7.11) is designed the same way.
- **There is no C/C++ source in this tree.** Native code arrives only through dependencies (the QuickJS binding AAR; Chaquopy's own runtime libraries).
- The honest remaining lever is **measure first, then choose** (§6): record the numbers (Phase 15), then decide between "keep", an ABI policy, a `lite` flavor without Python, and worker-pool tuning.

## 2. What is in the tree (counted 2026-11-01 on the owner's tree as delivered, `4e5a27cb-AutoHost-main.zip`, before this pass's edits)

Commands (repo root, excluding `reference/`, `build/`, `__pycache__`): `find . -name '*.kt' | wc -l` and `xargs cat | wc -l` (likewise `*.py`, `*.js`; `src/test` and `src/androidTest` count as tests). Raw line counts include blanks and comments — a size indicator, not a quality or effort measure.

| Language / area | Files | Lines | Notes |
|---|---:|---:|---|
| Kotlin — everything | 466 | 60,323 | all `*.kt` |
| Kotlin — main sources | 367 | 45,647 | host, broker, capabilities, runtimes, UI |
| Kotlin — tests (`src/test`, `src/androidTest`) | 99 | 14,676 | JVM + instrumented; status per `docs/PROGRESS.md` ladder |
| of which `runtime/flow` | 24 | 4,654 | declarative flow interpreter (in-process) |
| of which `runtime/js` | 11 | 1,655 | JS runtime seam; QuickJS touchpoint is `app/.../runtimejs/QuickJsEngineFactory.kt` |
| of which `runtime/python` (Kotlin side) | 8 | 509 | worker services that start the interpreter |
| **Python on the device** (`runtime/python/src/main/python/autohost`) | 42 | 7,060 | the SDK; byte-identical copy of `sdk-python/autohost` kept in sync by `tools/sync_sdk.py` |
| Python, PC only: simulator `sdk-python/autohost_sim` | 7 | 2,453 | runs scripts without a phone |
| Python, PC only: `autohost_cli` | 8 | 1,481 | Dev API client |
| Python, PC only: `autohost_ref` | 4 | 1,942 | reference implementations used by tests |
| Python, PC only: `sdk-python/tests` | 29 | 5,262 | SDK suite |
| Python, PC only: `tools/`, `tests/` | 10 + 3 | 1,644 + 239 | generators, checkers, tracker (as delivered; this pass adds `tools/doc_check.py` + `tests/test_doc_check.py` and edits `tools/progress.py` + `tests/test_progress.py`, not counted here) |
| Python — every `*.py` file counted once per path | 160 | 27,837 | includes the SDK twice (source + synced copy) and 696 lines elsewhere (samples, scripts) |
| JavaScript | 4 | 827 | prelude (twice: runtime resource + simulator), two sample files |
| C / C++ | 0 | 0 | none in this tree (`find` over `*.c *.cc *.cpp *.h *.hpp`) |

Reading: Kotlin main code is about 6.5 times the on-device Python. The on-device Python is a client of the broker; the capability work (UI, apps, notifications, network, files, schedules) executes in Kotlin.

## 3. How Python is hosted (from the build files and manifest)

- `runtime/python/build.gradle.kts`: Chaquopy plugin, `version = "3.13"`, `pip { install("requests") }`, `abiFilters += listOf("arm64-v8a", "x86_64")` (the comment says 3.13 is 64-bit only). The app module also sets `abiFilters` to the same two ABIs (`app/build.gradle.kts:20`). The pinned plugin is `chaquopy = "17.0.0"` (`gradle/libs.versions.toml:7`); 17.1.0 exists (§4) and is **not** adopted by this pass.
- Each Python run executes in its own OS process: `AndroidManifest.xml` declares `:w0`…`:w3` (comment: "one process each; recycled after every run") plus `:ui`. `BaseTaskWorkerService.kt:56` and `UiHandlerWorkerService.kt:90` call `Python.start(AndroidPlatform(this))` — one interpreter per process.
- A release build that keeps `x86_64` ships an emulator-only interpreter and its libraries to phones (§6 option C). Whether the release configuration filters it is **UNVERIFIED** here.
- Flavor dimension today: `tier` (`app/build.gradle.kts:24`). The `runtime` dimension (`py` | `lite`) is planned in P15-03, not present.

## 4. Cost model — what each language carries on Android

Tags: **[SOURCED]** primary source named; **[COUNTED]** this tree; **[GENERAL]** well-known Android engineering fact; **[PREDICTED]** reasoning from the above, unmeasured; **[UNMEASURED]** nobody has the number.

| Cost | Kotlin | Python via Chaquopy | C/C++ via NDK | JS via QuickJS binding |
|---|---|---|---|---|
| Runtime shipped | ART already on the device; the Kotlin stdlib/coroutines are DEX [GENERAL] | Interpreter + libraries per ABI. Maven index sizes for Python 3.13.16 (Chaquopy 17.1.0): `arm64-v8a` zip 7,309,656 B, `x86_64` zip 7,541,472 B, ABI-independent `stdlib-pyc` zip 4,334,436 B (`stdlib` zip 2,534,506 B) — about 11.1 MiB arm64 with the pyc stdlib, before pip packages or user code **[SOURCED: Maven Central `com/chaquo/python/target/3.13.16-0/`, read 2026-10-25; the sum is DERIVED]**. The real APK delta is **[UNMEASURED]**. | One `.so` per ABI you ship [GENERAL] | A native library inside the binding AAR; ABIs and size **[UNVERIFIED]** (`quickjs-kt-android` 1.0.3, `gradle/libs.versions.toml:15`) |
| Start-up | none beyond the app | Python.start() per process; no absolute figure found; only 2018/2019 relative statements about pre-compiled `.pyc` **[SOURCED: Chaquopy changelog 1.4.0, 6.3.0]**; Ours: **[UNMEASURED]** | none | engine creation per run **[UNMEASURED]** |
| Resident memory | shared with the app | per interpreter process: no measurement found anywhere **[UNMEASURED]**; four possible worker processes **[PREDICTED]** to cost more than one when all four run | per library | per engine **[UNMEASURED]** |
| CPU | ART compiles hot code | CPython bytecode interpreter; GIL: "only one of those threads will be executing at any given moment" **[SOURCED: Chaquopy cross.html]**; for a thin broker-client script most time is waiting on the broker **[PREDICTED]** | native speed | QuickJS interpreter **[PREDICTED]** |
| Isolation / failure | a crash kills the process | worker process per run isolates a crash and allows hard Stop by killing the process (G1-d) | a native crash is process death and cannot be caught — opendroid's own notes list a SIGABRT on an over-long prompt (`docs/REUSE_MATRIX.md` RM-E4) | binding-level |
| Library availability | JVM ecosystem | `pip` at **build time only**; since 17.0.0 `--only-binary`; numpy newest cp313 wheel is 1.26.2 on Chaquopy's index; no PEP 738 Android wheels found on PyPI for 34 of the 39 native packages scanned (numpy, pillow, opencv, scipy, pandas, cryptography among them) **[SOURCED: audit C 2026-10-25]** | any, but you build per ABI | pure JS |
| 16 KB page size | n/a | Chaquopy 17.0.0 supports 16 KB devices itself; "many existing Android wheels will still fail to load" — use Python 3.13+ (already used) **[SOURCED: changelog 17.0.0]**; per-wheel status **[UNVERIFIED]** | every `.so` must be 16 KB aligned; Play's rule applies from 2027-02-01 for targetSdk ≥ 35 **[researched earlier — verify the current rule before acting]** | binding `.so`: **[UNVERIFIED]** |
| Threads / processes | coroutines | `multiprocessing` mostly unavailable; one embedded interpreter per process **[SOURCED: android.html, faq.html]** | threads | one engine per run |

## 5. Where the plan already avoids Python (v3) and what v4 adds

- **Flows** (`runtime/flow`, 4,654 lines of Kotlin): declarative tasks, no worker, no interpreter. G6-a records `ps` + `dumpsys meminfo` against the Python version of the same task.
- **JS tasks** (`runtime/js`): QuickJS in the host process; G11-b runs all three runtimes side by side.
- **v4 agent runtime** (plan §7.11): in-process, pure JVM, model access over HTTPS from the host — no Python, no local inference by default (ADR-090, ADR-092).
- **v4 footprint track** (Phase 15): measurement harness (P15-01), Python-optional audit (P15-02), flavor `runtime` = `py` | `lite` (P15-03), ABI policy (P15-04), 16 KB audit (P15-05), worker-pool tuning (P15-06), R8 review (P15-07), accessibility event-mask audit (P15-08).

## 6. Options, with trade-offs (no percentage targets)

| Option | What it does | What it costs / risks | Decide after |
|---|---|---|---|
| A. Keep, and measure | Phase 15 records APK size per flavor/ABI, cold start, idle/in-run RSS, worker spawn time on both owner phones | effort only | — (do first) |
| B. `lite` flavor | A build without Chaquopy: flows, JS and agent work; a Python task gets a typed CompatReport instead of running | that build is not first-class Python; two variants to test | A shows the interpreter's real weight |
| C. ABI policy | Ship `arm64-v8a` only in release; keep `x86_64` for debug/emulator. Chaquopy's FAQ says APK splits and app bundles "won't help much" and recommends flavors with `abiFilters` **[SOURCED: faq.html]** | emulator-only release testing is lost; verify the release config | A |
| D. Worker-pool tuning | Fewer concurrent worker processes where measured RAM demands it | lower parallelism of Python runs | A (needs RAM numbers) |
| E. Move SDK logic to Kotlin | rejected: the SDK is a thin broker client, there is little CPU to save; it would break the "first-class Python" contract | high churn, no measured gain | never without a measured hot spot |
| F. Add C/C++ | rejected for now: no code needs it; adds NDK toolchain, per-ABI libraries, 16 KB duties and uncatchable crashes | — | a measured hot spot |

## 7. What this document does not claim

- No claim that Python "is" or "is not" the CPU problem: no profile exists. The previous plan's complaint is answered by design (Kotlin owns the platform; three in-process runtimes) and by a plan to measure, not by a number.
- No APK-size delta, start-up time or memory figure for Chaquopy on the owner's phones.
- No statement about `quickjs-kt-android`'s native library size, ABIs or 16 KB status.

## 8. Unknowns (all tracked as Phase 15 work)

1. Interpreter start-up and resident memory per worker on the Realme C3 and the Galaxy M31.
2. Real APK/AAB delta per ABI; which stdlib zip variant the plugin packages by default.
3. Whether the release configuration filters `x86_64`.
4. Chaquopy's wheel status for 16 KB and the licences of bundled components and wheels (not checked).
5. Whether Chaquopy 17.1.0 builds with this project's Gradle/AGP (not tried; the research found no Gradle bound in Chaquopy's docs).

*Sources: Chaquopy docs and changelog (`chaquo.com/chaquopy/doc/current/…`, raw RST on GitHub master, 17.1.0 dated 2026-10-12), Maven Central index for `com/chaquo/python/target/`, PyPI simple API scan — all read 2026-10-25 in this project's research notes; build files and manifest as cited above.*
