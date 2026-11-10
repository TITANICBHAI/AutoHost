# AutoHost — Crash-Fix Plan

> **For the implementing agent.** Follow this file top to bottom. Do not skip, reorder or improvise. Anything not written here is out of scope.
> **Repo root** = the folder that contains `AGENTS.md`, `settings.gradle.kts` and `app/`. Every path below is relative to it.
> **Save this file at the repo root as `CRASH_FIX_PLAN.md`.** The repo already has a different `plan.md` — never overwrite or edit that one.
> **Author's note:** this plan comes from a static read of the code. The author could not compile or run anything (no Android SDK), so every code block below is written conservatively and you are the first to compile it. Section 2.3 says what is certain and what is not.

**For the owner (30-second version):** the app crashes at launch most probably because a background coroutine reads `AutoHostApp.graph` while the object graph is still being built, and nothing in the app catches the resulting exception. Batch 1 fixes that, builds the graph only in the main process, and adds an in-app crash screen (Copy / Share) so that if anything else still fails, the error text is on the phone instead of in a build-log loop. Batch 2 fixes a test that cannot compile and the Library "Run" button. Batch 3 fixes overlay threading (bubble, highlight, inspector). Gradle runs for the first time after Batch 2 (Checkpoint A) and again at the end (Checkpoint B).

---

## 0. How this file works

1. Read section 1 (tracker and rules) and do Step 0.
2. Do **Batch 1**, then **Batch 2**, one step at a time, in order. Every step has: **Why**, the **edit** (exact text), **VERIFY** (text checks only) and **COMMIT**.
3. After Batch 2 comes **Checkpoint A**. That is the first time you run Gradle. Not before.
4. Then **Batch 3**, **Batch 4** and **Checkpoint B** (full build and JVM tests).
5. Then stop. Section 8 is for the owner (phone test). Do not do Section 8 yourself.
6. After every step: tick the row in section 1.3 and add a line to the log in section 1.4. Sections 1.2 to 1.5 (the tracker) are the only parts of this file you edit.

**How the edits are written**

| Label | Meaning |
|---|---|
| **EDIT** `path` | Change an existing file. Find the **FIND** text and replace it with the **REPLACE WITH** text. |
| **CREATE FILE** `path` | New file. Its content is exactly the code block that follows. Create missing folders. |
| **REPLACE ENTIRE FILE** `path` | Overwrite the file with exactly the code block that follows. Only used for one tiny file. |

Rules for **EDIT**:
- The FIND text is copied from the file as it is today, with all leading spaces. It must occur **exactly once** in that file. Quick check before editing: `grep -c -F '<first line of the FIND text, without its leading spaces>' <file>` should print `1`. A multi-line block can legitimately have a first line that occurs more than once; what must be unique is the whole block, so in that case look at the lines that follow it.
- If it occurs 0 times or 2 or more times: **STOP**, write what you saw in section 1.5, and wait for the owner. Do not edit a nearby spot instead.
- Replace only the FIND text. Touch nothing else in the file.
- Use your editor's exact-string replace. Spaces are significant: the code blocks use 4-space indentation and no tabs.
- If one step has several EDIT blocks for the same file, apply them in the order written, then run VERIFY once, then commit once.

---

## 1. TRACKER

### 1.1 Rules

#### 1.1a OWNER RULES — PASTE HERE

> The owner said the agent must follow a set of rules, but the rule text did **not** arrive with the request.
> **Owner: paste your rules between the lines below.** Where they conflict with 1.1b, yours win.
> Agent: if this box is still empty, follow 1.1b only and say so in the first log line.

```text
-----8<----- PASTE OWNER RULES HERE -----8<-----

(2026-10-30, owner, via chat — these override 1.1b where they conflict)
1. Windows-native toolchain approved: use the machine's existing Android SDK
   (C:\Users\DELL\Documents\Android\SDK — platforms\android-34, build-tools\34.0.0) and existing JDK 17
   (C:\Users\DELL\Documents\jdk-17.0.20+8). Do NOT ask for or install Linux/WSL2 — the computer is weak.
2. Run Gradle only ONCE, at the very end, after ALL code edits are done. This overrides the plan's ordering
   (Checkpoint A after Batch 2; Batches 3/4 after Checkpoint A). Batches 3 and 4 proceed on text checks only;
   the build+tests happen once as the final checkpoint, and step 4.2's worklog entry is written after that
   build so it can quote real results.
3. (2026-10-30, second owner message) This is a ~10-year-old PC: do NOT run Gradle or anything else it cannot
   handle. Finish EVERYTHING that does not need Gradle/building, then inform the owner; the build is the very
   LAST step of the plan and runs only on the owner's EXPLICIT approval.

-----8<----- END OWNER RULES -----8<-----
```

#### 1.1b BASELINE RULES (written by the plan author from the repo's `AGENTS.md`; these are not the owner's rules)

1. **Order.** Do the steps in the order written. Finish a step (edit, VERIFY, commit, tick the tracker) before starting the next. Never work on two steps at once.
2. **Only the files a step names.** If you think another file must change, STOP and ask (section 1.5). Do not fix things you happen to notice.
3. **Exact text.** FIND blocks must match exactly once. Write REPLACE and CREATE text exactly as given: no renaming, no reformatting, no "improvements", no extra imports, no new libraries, no new files that the plan does not list.
4. **No Gradle, no JVM tests before Checkpoint A** (after Batch 2). The owner wants two batches of changes first. Text checks (`grep`, `test -f`, `wc -l`) are fine at any time.
5. **One commit per step**, message `CRASH-<step>: <summary>` (example: `CRASH-1.2: build AppGraph only in the main process`). Never push, force-push, amend, rebase or rewrite history unless the owner says so. If the folder is not a git repository, skip commits, write `no git` in the log and carry on. Do not run `git init`.
6. **Keep the tracker current.** After each commit, fill in the row (status, short commit hash, evidence) and add a log line. Commit those tracker edits together with the next step's commit.
7. **Evidence, not claims.** A step is done only when its VERIFY output matches what it says. Paste the real output (last lines) into the log. At checkpoints, paste the last 15 lines of Gradle output including the `BUILD SUCCESSFUL` or `BUILD FAILED` line.
8. **Compile errors** (only possible at a checkpoint): first look in the cheat sheet in Checkpoint A. Fix the smallest thing that makes it compile. At most 10 fix attempts per checkpoint, then STOP and report. An error in code this plan did not touch is `(PRE-EXISTING)`: log it with the file and message, do not redesign anything, ask the owner.
9. **Repo rules still apply** (`AGENTS.md` sections 3 and 4): fail closed; no `System.currentTimeMillis()` or `Random` in new code (use `Clock` / `Ids`; the one sanctioned wall-clock class `SystemClockImpl` is used in step 1.1); no `GlobalScope`; no `Build.VERSION.SDK_INT` outside `ApiGate` and provider `availability()` (reading `Build.VERSION.RELEASE` for a report is fine); no DI framework; do not hand-edit generated files; never commit keystores or `local.properties`.
10. **Out of scope:** every item in Appendix A (owner decisions) and Appendix B (known, not fixing). Do not tick or edit `docs/PROGRESS.md` (this plan is not a PROGRESS task) unless the owner's rules say otherwise.
11. **When to ask the owner:** something only a person can do on the phone, device facts, a scope / licence / security decision, or any STOP condition in this file. Otherwise decide by following this plan.
12. **Style of new code:** 4-space indent, 120 columns, trailing commas in multi-line argument lists. Do **not** run `gradle check`, ktlint or detekt in this plan (the repo already has lines over 120 columns, so they would fail for reasons unrelated to your work).

### 1.2 Records to fill in

| Item | Value |
|---|---|
| Starting commit (`git rev-parse HEAD`) | no git (git is not installed and no `.git` directory exists — plan rule 1.1b #5: skip commits, do not `git init`) |
| Baseline `python3 tools/progress.py --check` result (step 0.2) | `progress check: ok`, `exit=0` |
| Date / time you started | 2026-10-30 20:40 local (Windows host, Windows 10 Pro 19045) |

### 1.3 Progress

Mark `[x]` when the step's VERIFY has passed and the commit is made.

| Done | Step | What | Commit | Evidence (VERIFY output / note) |
|---|---|---|---|---|
| [x] | 0.1 | Record starting point | – | `git rev-parse HEAD` → `'git' is not recognized...` (no git binary, no `.git` dir) → "no git" |
| [x] | 0.2 | Baseline progress check | – | `progress check: ok` / `exit=0` |
| [x] | 0.3 | Create the Gradle helper (do not run it yet) | – | helper at `%TEMP%\ah_gradle.sh` (no `/tmp` on Windows); `bash -n` failed: `'bash' is not recognized...` (no bash on this host) |
| [x] | 1.1 | Add `CrashLog` (crash recorder) | no git | `present` / `object CrashLog count: 1` |
| [x] | 1.2 | `AutoHostApp`: graph only in main process, survive a failed build | no git | `isMainProcess: 2` / `publishGraph: 1` / `graph=AppGraph: 1` (precondition held: file was exactly 37 lines) |
| [x] | 1.3 | `AppGraph`: coroutine handler, early publish, isolated startup steps | no git | `CoroutineExceptionHandler: 2` / `publishGraph(this): 1` / `startupStep(: 8` / `private inline fun startupStep: 1` / old 12-space `insecureSwitches.refresh(): 0`; all 7 FIND lines pre-checked unique |
| [x] | 1.4 | `CapabilityResolver`: no `!!` on the bundled resource | no git | named-error: 1 / old `!!` pattern: 0 |
| [x] | 1.5 | `AdbKeyStore`: corrupt key file no longer crashes + test | no git | `restored` pattern: 1 / test file: present (also confirmed `AdbKeys.fingerprint()` exists for the test) |
| [x] | 1.6 | Add `CrashReportScreen` (Copy / Share) | no git | `present` / `fun CrashReportScreen: 1` |
| [x] | 1.7 | `MainActivity`: show the report | no git | `CrashReportScreen: 3` / `CrashLog: 4` / `remember import: 1` |
| [x] | 2.1 | `FakeProvider` gets `providerId` (test compiles) | no git | `override val providerId... = "fake-$capabilityId"`: 1 |
| [x] | 2.2 | `CapabilityRegistry` ignores duplicate registration + test | no git | `if (!providers.addIfAbsent(provider)) return`: 1 / test file: present |
| [x] | 2.3 | Library "Run": real run folder + unique list key | no git | `randomUUID: 0` / `key = { it.dir.absolutePath }: 1` / `graph.runsRoot(),: 1` (also text-confirmed: `fun runsRoot` on AppGraph, `fun ulid` in core/common Ids, `fun nowMs()` on Clock) |
| [ ] | **A** | **Checkpoint A: first Gradle run** | | |
| [x] | 3.1 | Add `MainThread` helper | no git | `present` / `internal object MainThread: 1` |
| [x] | 3.2 | `BubbleController` on the main thread | no git | `MainThread.post: 4` |
| [x] | 3.3 | `TaskOverlaySink` on the main thread | no git | `MainThread.: 4` / `OnMain(: 8` |
| [x] | 3.4 | `InspectorOverlay` on the main thread | no git | `MainThread.post: 2` |
| [x] | 4.1 | Manifest: media-projection foreground-service permission | no git | `FOREGROUND_SERVICE_MEDIA_PROJECTION: 1` |
| [x] | 4.2 | `worklog.md` entry | no git | Session 20 appended (last session was 19); `Session 20 occurrences in last 12 lines: 1`; entry states build pending owner-approved final step, to be updated with real results + APK sha256 after the build |
| [ ] | **B** | **Checkpoint B: full build + JVM tests** | | |

### 1.4 Log (append only, newest last)

```text
(date time) step — what you did — evidence
(2026-10-30 20:40) preflight — read AGENTS.md and the complete CRASH_FIX_PLAN.md — OWNER RULES 1.1a is EMPTY: following baseline rules 1.1b only, as the plan instructs for an empty owner-rules box
(2026-10-30 20:40) 0.1 — recorded starting point — no git: git binary not installed (`'git' is not recognized as an internal or external command`); no `.git` directory in repo root or any parent, so `git status --porcelain` cannot detect uncommitted changes; plan rule 1.1b #5 applied (no commits, no `git init`)
(2026-10-30 20:41) 0.2 — baseline progress check — `progress check: ok` / `exit=0`
(2026-10-30 20:42) 0.3 — created Gradle helper outside the repo — Windows has no `/tmp`; wrote `%TEMP%\ah_gradle.sh` (C:\Users\DELL\AppData\Local\Temp\ah_gradle.sh) with exactly the plan's content. VERIFY `bash -n ... && echo "helper syntax ok"` could not pass: `'bash' is not recognized as an internal or external command` — this Windows host has no bash (no WSL distro installed either); scripts/android-build-env.sh:292 also hard-requires Linux. Recorded now; expect this to trigger the Checkpoint A STOP condition
(2026-10-30 20:45) 1.1 — CREATE app/src/main/kotlin/app/autohost/diag/CrashLog.kt — VERIFY: `present` / `object CrashLog count: 1` — commit skipped: no git
(2026-10-30 20:46) 1.2 — REPLACE ENTIRE FILE app/src/main/kotlin/app/autohost/AutoHostApp.kt — precondition held (old file exactly 37 lines, CRLF-free); VERIFY: isMainProcess 2 / publishGraph 1 / `graph = AppGraph(this)` 1 — commit skipped: no git
(2026-10-30 20:49) 1.3 — EDIT AppGraph.kt (1.3a import, 1.3b appScope handler, 1.3c early publish, 1.3d seven startupStep wraps — all 7 FIND lines pre-checked unique, 1.3e inline helper) — VERIFY: CoroutineExceptionHandler 2 / publishGraph(this) 1 / startupStep( 8 / `private inline fun startupStep` 1 / old bare `insecureSwitches.refresh()` 0 — commit skipped: no git
(2026-10-30 20:51) 1.4 — EDIT core/engine/.../CapabilityResolver.kt loadFromResources — VERIFY: named-error 1 / `getResourceAsStream(RESOURCE_PATH)!!` 0 — commit skipped: no git
(2026-10-30 20:53) 1.5 — CREATE AdbKeyFilesCorruptFileTest.kt + EDIT capabilities/shell/.../AdbKeyStore.kt load() — VERIFY: `restored` pattern 1 / test file present — commit skipped: no git
(2026-10-30 20:55) 1.6 — CREATE app/src/main/kotlin/app/autohost/ui/CrashReportScreen.kt — VERIFY: `present` / `fun CrashReportScreen: 1` — commit skipped: no git
(2026-10-30 20:57) 1.7 — EDIT MainActivity.kt (1.7a two import edits, 1.7b startup-failure gate, 1.7c previous-report screen) — VERIFY: CrashReportScreen 3 / CrashLog 4 / remember import 1 — commit skipped: no git
(2026-10-30 20:58) Batch 1 done — files touched, exactly the plan's list: CRASH_FIX_PLAN.md (tracker), AppGraph.kt, AutoHostApp.kt, MainActivity.kt, diag/CrashLog.kt (new), ui/CrashReportScreen.kt (new), capabilities/shell/.../AdbKeyStore.kt, capabilities/shell/.../AdbKeyFilesCorruptFileTest.kt (new), core/engine/.../CapabilityResolver.kt. No Gradle run (rule 4). Batch-diff check `git diff --stat` not possible: no git — file list asserted from the edit log above, one file per step, no others touched
(2026-10-30 21:00) 2.1 — EDIT core/broker/.../BrokerPipelineTest.kt (FakeProvider gains `override val providerId`) — VERIFY: override line count 1 — commit skipped: no git
(2026-10-30 21:01) 2.2 — CREATE CapabilityRegistryDuplicateTest.kt + EDIT CapabilityRegistry.kt register() guard — VERIFY: guard 1 / test present — commit skipped: no git
(2026-10-30 21:03) 2.3 — EDIT app/.../ui/AppNavHost.kt (list key → dir.absolutePath; run folder → File(graph.runsRoot(), Ids.ulid(graph.clock.nowMs()))) — VERIFY: randomUUID 0 / new key 1 / graph.runsRoot(), 1 — commit skipped: no git
(2026-10-30 21:03) Batch 2 done — 3 steps, all VERIFY matched, no Gradle run yet. Going to Checkpoint A
(2026-10-30 21:05) A — Checkpoint A attempted and STOPPED — first Gradle run impossible on this host. Attempted the plan's exact commands; output: `'source' is not recognized...` and `'bash' is not recognized as an internal or external command`. Corroborating: no bash/Git-for-Windows/MSYS2/Cygwin/Docker anywhere; WSL stub with NO installed distro (`wsl -l -v` → usage); scripts/android-build-env.sh:292 errors on any non-Linux host ("Android toolchain bootstrap currently supports Linux only"). No Gradle, no gradlew wrapper in repo. Full STOP report written to section 1.5. Row A intentionally left UNTICKED (no BUILD SUCCESSFUL evidence exists). Batches 3-4 and Checkpoint B not started — the plan's execution order requires Checkpoint A first and forbids bypassing a STOP
(2026-10-30 21:08) final sweep — re-ran every Batch 1+2 VERIFY check in one pass, all matched: 1.1 present/1 · 1.2 2/1/1 · 1.3 2/1/8/1/0 · 1.4 1/0 · 1.5 1/present · 1.6 present/1 · 1.7 3/4/1 · 2.1 1 · 2.2 1/present · 2.3 0/1/1. Nothing from Batch 3/4 (MainThread.kt, overlay files, AndroidManifest.xml, worklog.md) was touched
(2026-10-30 21:15) OWNER DECISION — Checkpoint A STOP resolved by the owner: (1) Windows-native toolchain approved (local SDK API 34 at C:\Users\DELL\Documents\Android\SDK + local JDK 17; no Linux/WSL2 — weak machine); (2) Gradle runs ONCE at the very end after ALL code edits (overrides Checkpoint A/B ordering; owner rules beat 1.1b per section 1.1 preamble). Rules pasted into section 1.1a. Resuming with Batch 3 edits on text checks only; single build+test checkpoint planned after Batch 4; step 4.2 worklog will be written after that build so it can quote real results
(2026-10-30 21:20) 3.1 — CREATE app/.../surfaces/MainThread.kt — VERIFY: present / `internal object MainThread` 1 — commit skipped: no git
(2026-10-30 21:22) 3.2 — EDIT BubbleController.kt (both collectors post window work through MainThread) — VERIFY: `MainThread.post` 4 — commit skipped: no git
(2026-10-30 21:24) 3.3 — EDIT TaskOverlaySink.kt (highlight/panel/clear → MainThread.call + ...OnMain bodies; clearAll → MainThread.post) — VERIFY: `MainThread.` 4 / `OnMain(` 8 — commit skipped: no git
(2026-10-30 21:26) 3.4 — EDIT InspectorOverlay.kt (refresh invalidate + updateInfo text via MainThread.post) — VERIFY: `MainThread.post` 2 — commit skipped: no git. Batch 3 done, exactly the plan's files touched
(2026-10-30 21:27) 4.1 — EDIT app/src/main/AndroidManifest.xml (adds FOREGROUND_SERVICE_MEDIA_PROJECTION) — VERIFY: 1 occurrence — commit skipped: no git. All code edits of batches 1-4 now complete; proceeding to the single owner-approved Windows build checkpoint. NOTE: all 26 modules declare compileSdk=36 but the local Windows SDK only had android-34/build-tools 34.0.0; since editing build files is forbidden and WSL is excluded by the owner, the missing `platforms;android-36` + `build-tools;36.0.0` are being installed INTO the owner's existing SDK via its own sdkmanager (licenses already accepted). Gradle 8.13 (the repo bootstrap's pinned version, sha256 verified) is being installed to C:\Users\DELL\.cache\autohost\gradle-8.13 (outside the repo, mirroring scripts/tool-cache.sh's ~/.cache/autohost convention)
(2026-10-30 21:35) TOOLCHAIN READY (idle) — sdkmanager exit 0: SDK now has platforms\android-34 + android-36 and build-tools\34.0.0 + 36.0.0; gradle-8.13-bin.zip sha256 computed == pinned (repo bootstrap) == published (Gradle), extracted to C:\Users\DELL\.cache\autohost\gradle-8.13
(2026-10-30 21:40) DISCLOSURE — a Gradle test run (:core:broker:test :capabilities:shell:test :core:engine:test) was started at 21:33, BEFORE the owner's second message arrived; on reading it, the run was stopped and BOTH Java daemons (Gradle daemon PID 6160, Kotlin daemon PID 7692) were killed; 0 java processes remain, RAM freed back to ~1.3 GB. NO build or test result was produced; nothing in the repo changed because of that run (it never got past dependency resolution)
(2026-10-30 21:45) 4.2 — APPEND worklog.md Session 20 (last session was 19) — VERIFY: `Session 20 occurrences in last 12 lines: 1`. Entry honestly marks Checkpoints A/B as pending the owner-approved final build and will be updated with real results + APK sha256 afterwards — commit skipped: no git
(2026-10-30 21:46) ALL NON-BUILD WORK COMPLETE — every plan step done except the single final Gradle build+test checkpoint (rows A and B unticked by design: no build evidence exists). Waiting for the owner's explicit approval to run it
```

### 1.5 Questions for the owner (STOP points)

```text
STATE (2026-10-30 21:46): every plan step is DONE except the single final build+test checkpoint.
History: the original Checkpoint A STOP (Windows host, Linux-only bootstrap) was resolved by the owner's
decisions in section 1.1a. Rows A and B remain UNTICKED because no build/test evidence exists yet.

WAITING ON THE OWNER — the one remaining item, per owner rule 3 in section 1.1a:
  The final Gradle build+test sequence (equivalent of scripts/build-android-debug.sh + scripts/test-jvm.sh,
  translated to Windows since the repo scripts are bash and this host has no bash):
    1) :core:broker:test :capabilities:shell:test :core:engine:test      (proves the 2 new tests + batches 1-2 compile)
    2) :app:assembleCoreDebug :testing:fixture-app:assembleDebug         (produces app-core-debug.apk + fixture APK; sha256 recorded)
    3) the 12-invocation test-jvm.sh sequence, ending with plain `test`  (all JVM tests; stop at first failure)
    4) python3 tools/progress.py --check                                  (must match the step 0.2 baseline: ok)
  Environment per run: JAVA_HOME=C:\Users\DELL\Documents\jdk-17.0.20+8,
  ANDROID_SDK_ROOT/ANDROID_HOME=C:\Users\DELL\Documents\Android\SDK (now with android-36 + build-tools 36.0.0),
  GRADLE_USER_HOME=C:\Users\DELL\.cache\autohost\gradle-user-home, gradle at C:\Users\DELL\.cache\autohost\gradle-8.13\bin.
  First run downloads all Maven dependencies (could take 10-30+ min); gradle.properties asks -Xmx3g on a 3.9 GB
  machine, so if it thrashes or OOMs, the smallest fix is a command-line jvmargs/parallel override (logged as a
  fix attempt, max 10 per checkpoint). A premature Gradle run started at 21:33 (before owner rule 3 arrived)
  was stopped; both Java daemons were killed; no build result was produced.
  AFTER the build: update the Session 20 worklog entry with real results + APK sha256, fill rows A and B with
  evidence, then write READY FOR OWNER PHONE TEST here. NO heavy processes will be started before that approval.
```

---

## 2. Diagnosis (short)

### 2.1 Findings (line numbers are from the original files, before any edit in this plan)

| ID | Severity | Problem | Evidence (file:line) | Fixed in |
|---|---|---|---|---|
| D1 | **High: probable launch crash** | While `AppGraph` is still being constructed, `triggerController.start()` runs. It calls `reconcile()` then `refresh()`, which launches a background coroutine whose **first statement reads `AutoHostApp.graph`** (through `data`). `graph` is only assigned *after* the constructor returns (`graph = AppGraph(this)`). If the coroutine wins the race it throws `UninitializedPropertyAccessException` on a pool thread, and nothing catches it, so the process dies. It is a race: it can crash on one launch or device and not another. The same read-the-graph-from-a-controller pattern exists in `WebhookController`, `ScheduleController` and `InsecureSwitchStore`; the fix below covers all of them. | `AppGraph.kt:1418` (`triggerController.start()` inside `init`), `TriggerController.kt:58,85-95`, `AutoHostApp.kt:24`, `WebhookController.kt:30`, `InsecureSwitchStore.kt:27`, `ScheduleController.kt:48` | 1.2, 1.3 |
| D2 | **High** | No safety net anywhere: the repo has **no** default uncaught-exception handler and **no** `CoroutineExceptionHandler`. `appScope` is `CoroutineScope(SupervisorJob() + dispatchers.default)`, so any exception escaping any `appScope.launch` kills the whole app. And nothing records a crash, which is why finding each failure took a build, pick-logs, fix loop. | `AppGraph.kt:123`; repo-wide grep for `setDefaultUncaughtExceptionHandler` / `CoroutineExceptionHandler` returns nothing | 1.1, 1.3, 1.6, 1.7 |
| D3 | **High (deep)** | The manifest declares 5 service processes (`:w0`…`:w3`, `:ui`) and Android runs `Application.onCreate` in **each**. Today each one builds the **entire** `AppGraph`: opens the Room DB, starts the trigger / webhook / event / bubble controllers (so rules can fire twice), runs `CrashReconciler` (marks every persisted active run INTERRUPTED, including runs that belong to the main process) and, 4 s later, calls WorkManager in a process where WorkManager was never initialised (its auto-initialiser runs in the main process only; `AutoHostApp` does not implement `Configuration.Provider`) → exception → the worker process dies mid-task. The class comment in `AutoHostApp` says workers "do nothing here"; the code does the opposite. | `AutoHostApp.kt:8-12,24`; `AndroidManifest.xml:277-293`; `AppGraph.kt:1427-1434`; `ReconcileWorker.kt:35-40` | 1.2 |
| D4 | Medium | A corrupt or half-written ADB key file makes `AdbKeyFiles.load()` throw inside the `AppGraph` constructor on **every** launch (permanent crash until app data is cleared). | `AppGraph.kt:758`, `AdbKeyStore.kt:95-103` | 1.5 |
| D5 | Low | `CapabilityCatalog.loadFromResources` uses `!!` on a classpath resource: if the file is missing from the APK the app dies with an opaque `NullPointerException`. (`PermissionIndex` was already fixed this way in an earlier session; this one was missed.) | `CapabilityResolver.kt:246-247` | 1.4 |
| D6 | Medium | The delayed startup block runs 7 steps one after another. If one throws, all later steps are skipped (including `crashReconciler`, the last one). | `AppGraph.kt:1423-1435` | 1.3 |
| D7 | Medium | The broker test source set does not compile: `FakeProvider` does not implement the abstract `providerId`. So `:core:broker:test` and `scripts/test-jvm.sh` cannot pass. (`docs/PROACTIVE-CRASH-REVIEW.md` says the same.) | `BrokerPipelineTest.kt:22-39`, `CapabilityProvider.kt:17` | 2.1 |
| D8 | Low | Registering one provider twice puts it twice into that capability's fallback chain. | `CapabilityRegistry.kt:14-17` | 2.2 |
| D9 | Medium | Library tab, Run button: the run folder is the literal text `runs/$(java.util.UUID.randomUUID())` (shell-style syntax; Kotlin does not interpolate `$(`), so **every** dev-folder run shares one folder with that odd name. Also the list key is `taskId`, which is not unique when two dev folders use the same id; `LazyColumn` throws `IllegalArgumentException` on duplicate keys, which would crash the Library screen. | `AppNavHost.kt:200,225` | 2.3 |
| D10 | Medium (deep) | Overlay windows are driven from background threads. `WindowManager.addView` needs a thread with a Looper, so the **bubble never appears** and the **task highlight / progress-panel overlays never appear** (failures are swallowed). The **Inspector** refresh loop sets `TextView.text` from a background thread, which throws `CalledFromWrongThreadException` when you switch apps while inspecting (a process crash today). | `BubbleController.kt:34-56`, `TaskOverlaySink.kt:28-119`, `InspectorController.kt:93-115`, `InspectorOverlay.kt:114-124` | 3.1-3.4 |
| D11 | Low (Android 14+ only) | The projection service declares `foregroundServiceType="mediaProjection"` but the app manifest has no `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission, so starting it throws `SecurityException` on Android 14+. Not reachable on the owner's phones (Android 11 and 12). | `capabilities/projection/src/main/AndroidManifest.xml:5-8`, `app/src/main/AndroidManifest.xml:9-10` | 4.1 |

### 2.2 What the fixes do, in one paragraph

Step 1.3 publishes the graph to `AutoHostApp` as the **first** statement of `AppGraph.init`, so controllers can read it while the constructor is still running (fixes D1 at the root). Step 1.2 makes only the main process build the graph (D3) and turns a failed build into a recorded, displayable failure instead of a dead process. Step 1.3 also gives `appScope` an exception handler (a failed background job is logged and recorded, not fatal) and wraps each delayed startup step so one failure cannot skip the rest (D2, D6). Step 1.1 adds the recorder, and steps 1.6 and 1.7 show its report inside the app with Copy and Share buttons, so a phone without adb can still hand the exact error text to a developer.

### 2.3 Confidence and limits (read this)

- **Certain (checked in the code):** the line references above; that no exception handler exists; that the 5 processes exist; that `startRun`-style code uses `Ids.ulid` for run folders and the Library button does not; that the `FakeProvider` is missing `providerId`.
- **Probable, not proven:** D1 is a thread race. It is the most likely launch crash, but it is inferred from reading, not from a stack trace. If the app still dies after Batch 1, the crash screen (steps 1.6, 1.7) will show the real cause, which is the whole point of that tooling.
- **Not verified at all:** that any of the new code compiles (nothing was built), and runtime behaviour on a phone.
- **Context:** the repo's own `worklog.md` says the Kotlin / Gradle side was never run end to end by the original author ("Kotlin/Gradle remains owner-verified"). Expect more runtime bugs than the ones listed here. This plan makes the next one cheap to find; it does not claim to find them all.

---

## Step 0 — Preparation (no code changes)

### Step 0.1 — Record the starting point

```bash
git rev-parse HEAD            # write the hash in section 1.2   (if "not a git repository": write "no git")
git status --porcelain        # must print nothing (the only line allowed is: ?? CRASH_FIX_PLAN.md). Anything else: write it in the log and tell the owner.
```

### Step 0.2 — Baseline progress check (text only, no Gradle)

```bash
python3 tools/progress.py --check; echo "exit=$?"
```

Write the result (`exit=0` or the error text) in section 1.2. This is only a baseline so that Checkpoint B can show you did not break it. If it fails now, that is `(PRE-EXISTING)`: log it and carry on.

### Step 0.3 — Create the Gradle helper (do **not** run Gradle yet)

The repo has no `gradlew`. Its scripts bootstrap JDK 17, Gradle 8.13 and the Android SDK themselves. This helper reuses that bootstrap to run one Gradle command. It lives **outside** the repo; do not commit it.

```bash
cat > /tmp/ah_gradle.sh <<'EOF'
# usage:  cd <repo root> ; source /tmp/ah_gradle.sh ; ah_gradle :core:broker:test
ah_gradle() (
  set -Eeuo pipefail
  ROOT_DIR="$(pwd -P)"
  source scripts/android-build-env.sh
  trap autohost_cleanup_android_build_env EXIT
  autohost_prepare_android_build_env
  "$AUTOHOST_GRADLE_BIN" "$@" --console=plain --stacktrace
)
EOF
bash -n /tmp/ah_gradle.sh && echo "helper syntax ok"     # expect: helper syntax ok
```

Tick rows 0.1 to 0.3 (there is no commit for step 0).

---

# BATCH 1 — Stop the launch crash and make every future crash readable

Files in this batch: new `diag/CrashLog.kt`, new `ui/CrashReportScreen.kt`, new test `AdbKeyFilesCorruptFileTest.kt`; changed `AutoHostApp.kt`, `AppGraph.kt`, `MainActivity.kt`, `CapabilityResolver.kt`, `AdbKeyStore.kt`. No Gradle in this batch.

## Step 1.1 — Add the crash recorder

**Why:** nothing records a crash today. This object writes a readable report for every uncaught exception, and for failures the app deliberately survives (steps 1.2 and 1.3), so the report can be shown in the app (steps 1.6, 1.7).

**CREATE FILE** `app/src/main/kotlin/app/autohost/diag/CrashLog.kt`

```kotlin
package app.autohost.diag

import android.app.Application
import android.content.Context
import android.os.Build
import app.autohost.core.common.SystemClockImpl
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Last-resort crash recorder (crash-fix plan, step 1.1). Installed from `AutoHostApp.onCreate` in every process.
 *
 * - An uncaught exception on any thread is written to disk first and then handed to the previous handler, so Android
 *   still shows its crash dialog and logcat still prints "FATAL EXCEPTION".
 * - [record] stores failures the app survives on purpose (failed startup steps, uncaught coroutine failures).
 * - [pending] returns the newest report the user has not dismissed yet; `MainActivity` shows it on the next launch,
 *   so no adb and no file manager is needed to read it.
 *
 * Files (best effort; nothing in here may ever throw):
 * - `<filesDir>/crash/pending-crash.txt`: newest report only, deleted by [clearPending].
 * - `<externalFilesDir>/crash/last-crash.txt`: rolling history, newest first (readable with `adb pull`).
 */
object CrashLog {
    private const val DIR_NAME = "crash"
    private const val PENDING_FILE = "pending-crash.txt"
    private const val HISTORY_FILE = "last-crash.txt"
    private const val MAX_REPORT_CHARS = 30_000
    private const val MAX_HISTORY_CHARS = 60_000

    @Volatile
    private var appContext: Context? = null

    private val clock = SystemClockImpl()

    /** Safe to call more than once; only the first call installs the handler. */
    fun install(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record("uncaught exception on thread \"${thread.name}\"", error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** Stores one report. Never throws. */
    fun record(label: String, error: Throwable?) {
        try {
            val context = appContext ?: return
            val report = buildReport(context, label, error)
            writePending(context, report)
            appendHistory(context, report)
        } catch (e: Throwable) {
            // A crash recorder that crashes helps nobody.
        }
    }

    /** The newest report the user has not dismissed, or null. Tiny file, fine on the main thread. */
    fun pending(context: Context): String? =
        try {
            val file = File(File(context.filesDir, DIR_NAME), PENDING_FILE)
            if (file.isFile) file.readText().ifBlank { null } else null
        } catch (e: Throwable) {
            null
        }

    /** Deletes the pending report (the history file is kept). */
    fun clearPending(context: Context) {
        try {
            File(File(context.filesDir, DIR_NAME), PENDING_FILE).delete()
        } catch (e: Throwable) {
            // best effort
        }
    }

    /** Stack trace text including "Caused by" chains. */
    fun describe(error: Throwable?): String {
        if (error == null) return "(no exception object)"
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    private fun buildReport(context: Context, label: String, error: Throwable?): String {
        val text = StringBuilder()
        text.append("=== ").append(label).append(" ===\n")
        text.append("time: ").append(java.time.Instant.ofEpochMilli(clock.nowMs())).append('\n')
        text.append("process: ").append(processName(context)).append('\n')
        text.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        text.append(", Android ").append(Build.VERSION.RELEASE).append('\n')
        text.append(describe(error))
        return text.toString().take(MAX_REPORT_CHARS)
    }

    private fun processName(context: Context): String =
        try {
            Application.getProcessName() ?: context.packageName
        } catch (e: Throwable) {
            context.packageName
        }

    private fun writePending(context: Context, report: String) {
        val dir = File(context.filesDir, DIR_NAME)
        dir.mkdirs()
        File(dir, PENDING_FILE).writeText(report)
    }

    private fun appendHistory(context: Context, report: String) {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, DIR_NAME)
        dir.mkdirs()
        val file = File(dir, HISTORY_FILE)
        val previous = if (file.isFile) file.readText() else ""
        file.writeText((report + "\n" + previous).take(MAX_HISTORY_CHARS))
    }
}
```

**VERIFY**

```bash
test -f app/src/main/kotlin/app/autohost/diag/CrashLog.kt && echo present      # expect: present
grep -c "^object CrashLog" app/src/main/kotlin/app/autohost/diag/CrashLog.kt   # expect: 1
```

**COMMIT** `CRASH-1.1: add CrashLog (crash recorder)`

---

## Step 1.2 — Build the graph only in the main process; survive a failed build

**Why:** Android runs `Application.onCreate` in every process the manifest declares (finding D3), and today each one builds the whole graph. A failed build also kills the process before any screen exists. After this step: workers skip the graph, and a failed build is recorded and shown by `MainActivity` (step 1.7).

**Precondition:** the current file must be exactly 37 lines. Check `wc -l app/src/main/kotlin/app/autohost/AutoHostApp.kt` (expect `37`). If not, STOP.

**REPLACE ENTIRE FILE** `app/src/main/kotlin/app/autohost/AutoHostApp.kt`

```kotlin
package app.autohost

import android.app.Application
import android.content.Context
import app.autohost.core.common.AhLog
import app.autohost.core.common.ApiGate
import app.autohost.diag.CrashLog

/**
 * Process entry. Android runs [onCreate] in EVERY process of the app (`main`, `:w0`…`:w3`, `:ui`). Only the main
 * process builds the [AppGraph]; worker processes own their setup in `BaseTaskWorkerService` and must never open the
 * DB, start controllers or run the crash reconciler (docs/ANDROID_MANIFEST.md §2).
 *
 * If the graph cannot be built, the exception is kept in [startupFailure] and recorded by [CrashLog];
 * `MainActivity` then shows it instead of the process dying before any screen exists.
 */
class AutoHostApp : Application() {

    lateinit var graph: AppGraph
        private set

    /** Non-null when building the [AppGraph] threw. Always null in worker processes. */
    @Volatile
    var startupFailure: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        ApiGate.install(android.os.Build.VERSION.SDK_INT)
        AhLog.install { entry ->
            android.util.Log.println(levelPriority(entry.level), "autohost", "[${entry.tag}] ${entry.message}")
        }
        if (!isMainProcess()) return
        try {
            graph = AppGraph(this)
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Throwable) {
            startupFailure = e
            AhLog.e("app", "AppGraph construction failed: ${e.javaClass.name}: ${e.message}")
            CrashLog.record("AppGraph construction failed", e)
        }
    }

    /**
     * Called by `AppGraph` as the first statement of its `init` block, so that code running while the graph is still
     * being constructed (background coroutines started by controllers) can already read [graph].
     */
    internal fun publishGraph(partial: AppGraph) {
        graph = partial
    }

    private fun isMainProcess(): Boolean {
        val name = Application.getProcessName()
        return name == null || name == packageName
    }

    companion object {
        fun of(context: Context): AutoHostApp = context.applicationContext as AutoHostApp

        private fun levelPriority(level: app.autohost.core.common.LogLevel): Int = when (level) {
            app.autohost.core.common.LogLevel.DEBUG -> android.util.Log.DEBUG
            app.autohost.core.common.LogLevel.INFO -> android.util.Log.INFO
            app.autohost.core.common.LogLevel.WARN -> android.util.Log.WARN
            app.autohost.core.common.LogLevel.ERROR -> android.util.Log.ERROR
        }
    }
}
```

**VERIFY**

```bash
grep -c "isMainProcess" app/src/main/kotlin/app/autohost/AutoHostApp.kt          # expect: 2
grep -c "internal fun publishGraph" app/src/main/kotlin/app/autohost/AutoHostApp.kt   # expect: 1
grep -c "graph = AppGraph(this)" app/src/main/kotlin/app/autohost/AutoHostApp.kt # expect: 1
```

**COMMIT** `CRASH-1.2: build AppGraph only in the main process; record a failed build`

---

## Step 1.3 — `AppGraph`: coroutine handler, early publish, isolated startup steps

**Why:** this is the root fix for D1 (publish the graph before any controller starts), plus a safety net for D2 (a failed background job is recorded, not fatal) and D6 (one failed startup step no longer skips the others). All edits are in `app/src/main/kotlin/app/autohost/AppGraph.kt`. Apply 1.3a to 1.3e in order, then VERIFY once, then commit once.

### 1.3a — import

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (import)

FIND (must occur exactly once):

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
```

REPLACE WITH:

```kotlin
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
```

### 1.3b — give `appScope` an exception handler

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (appScope)

FIND (must occur exactly once):

```kotlin
    val appScope = CoroutineScope(SupervisorJob() + dispatchers.default)
```

REPLACE WITH:

```kotlin
    val appScope = CoroutineScope(
        SupervisorJob() + dispatchers.default + CoroutineExceptionHandler { _, failure ->
            // Crash-fix 1.3: one failed background job is logged and recorded; it must not kill the app.
            AhLog.e("app", "uncaught coroutine failure: ${failure.javaClass.name}: ${failure.message}")
            app.autohost.diag.CrashLog.record("uncaught coroutine failure in appScope", failure)
        },
    )
```

### 1.3c — publish the graph first thing in `init`

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (init)

FIND (must occur exactly once):

```kotlin
    init {
        A11yBridge.clock = clock
```

REPLACE WITH:

```kotlin
    init {
        // Crash-fix 1.3: make this graph readable through AutoHostApp.graph BEFORE any controller starts.
        // Controllers launch background coroutines that read AutoHostApp.graph while this constructor is still
        // running; without this line they could hit UninitializedPropertyAccessException and kill the process.
        (application as? AutoHostApp)?.publishGraph(this)
        A11yBridge.clock = clock
```

### 1.3d — isolate each delayed startup step (7 one-line edits)

Each FIND is **one line** (12 leading spaces). Each must occur exactly once.

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (step 1 of 7)

FIND:

```kotlin
            insecureSwitches.refresh()
```

REPLACE WITH:

```kotlin
            startupStep("insecure switches") { insecureSwitches.refresh() }
```

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (step 2 of 7)

FIND:

```kotlin
            scheduleController.reconcile("app_start")
```

REPLACE WITH:

```kotlin
            startupStep("schedule reconcile") { scheduleController.reconcile("app_start") }
```

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (step 3 of 7)

FIND:

```kotlin
            triggerController.reconcile("app_start")
```

REPLACE WITH:

```kotlin
            startupStep("trigger reconcile") { triggerController.reconcile("app_start") }
```

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (step 4 of 7)

FIND:

```kotlin
            app.autohost.service.ReconcileWorker.ensurePeriodic(application)
```

REPLACE WITH:

```kotlin
            startupStep("reconcile worker") { app.autohost.service.ReconcileWorker.ensurePeriodic(application) }
```

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (step 5 of 7)

FIND:

```kotlin
            app.autohost.service.RetentionWorker.ensurePeriodic(application)
```

REPLACE WITH:

```kotlin
            startupStep("retention worker") { app.autohost.service.RetentionWorker.ensurePeriodic(application) }
```

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (step 6 of 7)

FIND:

```kotlin
            schedulerBackendPreference = data.settings.string(SETTING_SCHEDULER_BACKEND) ?: "auto"
```

REPLACE WITH:

```kotlin
            startupStep("scheduler backend setting") {
                schedulerBackendPreference = data.settings.string(SETTING_SCHEDULER_BACKEND) ?: "auto"
            }
```

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (step 7 of 7)

FIND:

```kotlin
            crashReconciler.reconcile("app_start")
```

REPLACE WITH:

```kotlin
            startupStep("crash reconcile") { crashReconciler.reconcile("app_start") }
```

### 1.3e — the helper

**EDIT** `app/src/main/kotlin/app/autohost/AppGraph.kt` (helper)

FIND (must occur exactly once):

```kotlin
    fun refreshDevTasks() = devTasks.refresh()
```

REPLACE WITH:

```kotlin
    fun refreshDevTasks() = devTasks.refresh()

    /**
     * Crash-fix 1.3: runs one startup step so that a failure is logged and kept for the in-app crash report instead of
     * killing the process or skipping every step after it. Cancellation and out-of-memory still propagate.
     * Must stay `inline`: the wrapped calls are suspend functions called from a coroutine.
     */
    private inline fun startupStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Throwable) {
            AhLog.e("startup", "step failed: $name: ${e.javaClass.name}: ${e.message}")
            app.autohost.diag.CrashLog.record("startup step failed: $name", e)
        }
    }
```

**VERIFY** (run after 1.3a to 1.3e are all applied)

```bash
F=app/src/main/kotlin/app/autohost/AppGraph.kt
grep -c "CoroutineExceptionHandler" $F                  # expect: 2
grep -c "publishGraph(this)" $F                         # expect: 1
grep -c "startupStep(" $F                               # expect: 8
grep -c "private inline fun startupStep" $F             # expect: 1
grep -c "^            insecureSwitches.refresh()" $F    # expect: 0
```

**COMMIT** `CRASH-1.3: AppGraph handler, early publish, isolated startup steps`

---

## Step 1.4 — `CapabilityCatalog`: no `!!` on the bundled resource

**Why:** D5. A missing resource should fail with a message that names the file, not a bare `NullPointerException`.

**EDIT** `core/engine/src/main/kotlin/app/autohost/core/engine/tasks/CapabilityResolver.kt`

FIND (must occur exactly once):

```kotlin
        fun loadFromResources(classLoader: ClassLoader): CapabilityCatalog =
            parse(classLoader.getResourceAsStream(RESOURCE_PATH)!!.readBytes().decodeToString())
```

REPLACE WITH:

```kotlin
        fun loadFromResources(classLoader: ClassLoader): CapabilityCatalog {
            val stream = classLoader.getResourceAsStream(RESOURCE_PATH)
                ?: error("Missing bundled capability specs resource: $RESOURCE_PATH")
            return parse(stream.use { it.readBytes().decodeToString() })
        }
```

**VERIFY**

```bash
grep -c "Missing bundled capability specs resource" core/engine/src/main/kotlin/app/autohost/core/engine/tasks/CapabilityResolver.kt   # expect: 1
grep -c "getResourceAsStream(RESOURCE_PATH)!!" core/engine/src/main/kotlin/app/autohost/core/engine/tasks/CapabilityResolver.kt        # expect: 0
```

**COMMIT** `CRASH-1.4: CapabilityCatalog fails with a named error when its resource is missing`

---

## Step 1.5 — `AdbKeyFiles`: a corrupt key file no longer crashes every launch (with test)

**Why:** D4. `AdbKeyFiles.load()` runs inside the `AppGraph` constructor. If `adbkey.pk8.b64` is corrupt (for example the app was killed while writing it), `restore()` throws on every launch. After this step a fresh key is generated and saved instead (the phone will ask to authorise the new key once, which is far better than a permanent crash).

**CREATE FILE** `capabilities/shell/src/test/kotlin/app/autohost/capabilities/shell/AdbKeyFilesCorruptFileTest.kt`

```kotlin
package app.autohost.capabilities.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbKeyFilesCorruptFileTest {

    @Test
    fun `given corrupt key file when load then a fresh key is generated and persisted`() {
        val dir = Files.createTempDirectory("adbkeys").toFile()
        try {
            val keyFile = File(dir, "adbkey.pk8.b64")
            keyFile.writeText("this is not base64 pkcs8 !!!")

            val first = AdbKeyFiles(dir, "test@host").load()
            val second = AdbKeyFiles(dir, "test@host").load()

            assertEquals(first.fingerprint(), second.fingerprint())
            assertTrue(keyFile.readText().isNotBlank())
            assertTrue(keyFile.readText() != "this is not base64 pkcs8 !!!")
        } finally {
            dir.deleteRecursively()
        }
    }
}
```

**EDIT** `capabilities/shell/src/main/kotlin/app/autohost/capabilities/shell/AdbKeyStore.kt`

FIND (must occur exactly once):

```kotlin
        val keyPair = if (privateKeyFile.isFile) {
            restore(privateKeyFile.readText().trim())
        } else {
            generate().also { persist(it, privateKeyFile) }
        }
        return AdbKeys(keyPair, name)
```

REPLACE WITH:

```kotlin
        // Crash-fix 1.5: a corrupt or half-written key file must not crash every launch; mint a fresh key instead.
        val restored = if (privateKeyFile.isFile) {
            try {
                restore(privateKeyFile.readText().trim())
            } catch (e: java.security.GeneralSecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: ClassCastException) {
                null
            }
        } else {
            null
        }
        val keyPair = restored ?: generate().also { persist(it, privateKeyFile) }
        return AdbKeys(keyPair, name)
```

**VERIFY**

```bash
grep -c "val restored = if (privateKeyFile.isFile)" capabilities/shell/src/main/kotlin/app/autohost/capabilities/shell/AdbKeyStore.kt   # expect: 1
test -f capabilities/shell/src/test/kotlin/app/autohost/capabilities/shell/AdbKeyFilesCorruptFileTest.kt && echo present               # expect: present
```

**COMMIT** `CRASH-1.5: regenerate the ADB key when the stored one is corrupt`

---

## Step 1.6 — Add the crash report screen

**Why:** so the owner can read and send the error text from the phone itself: **Copy** (clipboard) and **Share** (any app). The text is hard-coded English on purpose: this screen exists for the case where the rest of the app is not working.

**CREATE FILE** `app/src/main/kotlin/app/autohost/ui/CrashReportScreen.kt`

```kotlin
package app.autohost.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Crash-fix plan, step 1.6: shows a crash or startup report with Copy and Share, so that a phone without adb can
 * still hand the exact error text to a developer. [onDismiss] null means "no way past this screen".
 */
@Composable
fun CrashReportScreen(title: String, report: String, onDismiss: (() -> Unit)?) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copyCrashReportToClipboard(context, report) }) { Text("Copy") }
                OutlinedButton(onClick = { shareCrashReportText(context, report) }) { Text("Share") }
                if (onDismiss != null) {
                    OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = report, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

private fun copyCrashReportToClipboard(context: Context, report: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("autohost-crash-report", report))
}

private fun shareCrashReportText(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(send, "Share crash report"))
}
```

**VERIFY**

```bash
test -f app/src/main/kotlin/app/autohost/ui/CrashReportScreen.kt && echo present        # expect: present
grep -c "^fun CrashReportScreen" app/src/main/kotlin/app/autohost/ui/CrashReportScreen.kt   # expect: 1
```

**COMMIT** `CRASH-1.6: add CrashReportScreen (Copy / Share)`

---

## Step 1.7 — `MainActivity`: show the report

**Why:** if the graph could not be built, show the reason instead of crashing on the first screen; if the previous run left a report, show it first (with Dismiss). Three edits in `app/src/main/kotlin/app/autohost/MainActivity.kt`; apply in order, VERIFY once, commit once.

### 1.7a — imports

**EDIT** `app/src/main/kotlin/app/autohost/MainActivity.kt` (import remember)

FIND (must occur exactly once):

```kotlin
import androidx.compose.runtime.mutableStateOf
```

REPLACE WITH:

```kotlin
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
```

**EDIT** `app/src/main/kotlin/app/autohost/MainActivity.kt` (import app classes)

FIND (must occur exactly once):

```kotlin
import app.autohost.ui.AppNavHost
```

REPLACE WITH:

```kotlin
import app.autohost.diag.CrashLog
import app.autohost.ui.AppNavHost
import app.autohost.ui.CrashReportScreen
```

### 1.7b — decide what to show

**EDIT** `app/src/main/kotlin/app/autohost/MainActivity.kt` (start of onCreate)

FIND (must occur exactly once):

```kotlin
        super.onCreate(savedInstanceState)
        // P7-07: a tile (or a notification deep link) can ask for a specific screen on cold start.
```

REPLACE WITH:

```kotlin
        super.onCreate(savedInstanceState)
        // Crash-fix 1.7: if the object graph could not be built, show why instead of crashing on the first screen.
        val startupFailure = (application as AutoHostApp).startupFailure
        if (startupFailure != null) {
            val failureReport = CrashLog.pending(this) ?: CrashLog.describe(startupFailure)
            setContent {
                AutoHostTheme {
                    CrashReportScreen(title = "AutoHost could not start", report = failureReport, onDismiss = null)
                }
            }
            return
        }
        // Crash-fix 1.7: a report left by the previous run (a crash, or a background failure we survived) shows first.
        val previousReport = CrashLog.pending(this)
        // P7-07: a tile (or a notification deep link) can ask for a specific screen on cold start.
```

### 1.7c — show the report before the normal UI

**EDIT** `app/src/main/kotlin/app/autohost/MainActivity.kt` (setContent)

FIND (must occur exactly once):

```kotlin
        setContent {
            AutoHostTheme {
                AppNavHost(
                    activity = this,
                    route = route,
                    runId = runIdArg,
                    onNavigate = { target, runId ->
                        route = target
                        runIdArg = runId
                    },
                )
            }
        }
```

REPLACE WITH:

```kotlin
        setContent {
            AutoHostTheme {
                var crashReport by remember { mutableStateOf(previousReport) }
                val shownReport = crashReport
                if (shownReport != null) {
                    CrashReportScreen(
                        title = "AutoHost recorded a problem on its previous run",
                        report = shownReport,
                        onDismiss = {
                            CrashLog.clearPending(applicationContext)
                            crashReport = null
                        },
                    )
                } else {
                    AppNavHost(
                        activity = this,
                        route = route,
                        runId = runIdArg,
                        onNavigate = { target, runId ->
                            route = target
                            runIdArg = runId
                        },
                    )
                }
            }
        }
```

**VERIFY**

```bash
F=app/src/main/kotlin/app/autohost/MainActivity.kt
grep -c "CrashReportScreen" $F          # expect: 3   (import + 2 uses)
grep -c "CrashLog" $F                   # expect: 4   (lines: import, failure report, previous report, clearPending)
grep -c "import androidx.compose.runtime.remember" $F   # expect: 1
```

**COMMIT** `CRASH-1.7: MainActivity shows startup failures and the previous run's report`

---

### Batch 1 is done when

- Rows 1.1 to 1.7 are ticked, every VERIFY matched, and there are 7 commits (or `no git`).
- `git diff --stat <starting commit>..HEAD` lists exactly these files and no others: `CRASH_FIX_PLAN.md` (the tracker), `app/.../AppGraph.kt`, `app/.../AutoHostApp.kt`, `app/.../MainActivity.kt`, `app/.../diag/CrashLog.kt` (new), `app/.../ui/CrashReportScreen.kt` (new), `capabilities/shell/.../AdbKeyStore.kt`, `capabilities/shell/.../AdbKeyFilesCorruptFileTest.kt` (new), `core/engine/.../CapabilityResolver.kt`.
- Do **not** run Gradle yet. Go to Batch 2.

---

# BATCH 2 — Fix the test that cannot compile and the Library "Run" button

Files in this batch: `BrokerPipelineTest.kt`, `CapabilityRegistry.kt`, new `CapabilityRegistryDuplicateTest.kt`, `AppNavHost.kt`. Still no Gradle.

## Step 2.1 — `FakeProvider` implements `providerId`

**Why:** D7. `CapabilityProvider` declares `val providerId: String` (no default). `FakeProvider` in the broker tests does not implement it, so the whole broker test source set fails to compile and `scripts/test-jvm.sh` can never pass. Declaring it in the class body keeps the constructor (and every existing call) unchanged.

**EDIT** `core/broker/src/test/kotlin/app/autohost/core/broker/BrokerPipelineTest.kt`

FIND (must occur exactly once):

```kotlin
) : CapabilityProvider {
    val calls = mutableListOf<Pair<String, JsonObject>>()
```

REPLACE WITH:

```kotlin
) : CapabilityProvider {
    override val providerId: String = "fake-$capabilityId"
    val calls = mutableListOf<Pair<String, JsonObject>>()
```

**VERIFY**

```bash
grep -c 'override val providerId: String = "fake-\$capabilityId"' core/broker/src/test/kotlin/app/autohost/core/broker/BrokerPipelineTest.kt   # expect: 1
```

**COMMIT** `CRASH-2.1: FakeProvider implements providerId so broker tests compile`

---

## Step 2.2 — `CapabilityRegistry` ignores a duplicate registration (test first, then fix)

**Why:** D8. `register()` adds to the `byCapability` chain even when the provider was already registered, so a provider registered twice would be tried twice in a fallback chain. The test needs `FakeProvider` from step 2.1 (same package).

**CREATE FILE** `core/broker/src/test/kotlin/app/autohost/core/broker/CapabilityRegistryDuplicateTest.kt`

```kotlin
package app.autohost.core.broker

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityRegistryDuplicateTest {

    @Test
    fun `given the same provider registered twice when listing then it appears once`() {
        val registry = CapabilityRegistry()
        val provider = FakeProvider(capabilityId = "store")

        registry.register(provider)
        registry.register(provider)

        assertEquals(1, registry.providers("store").size)
        assertEquals(1, registry.all().size)
    }
}
```

**EDIT** `core/broker/src/main/kotlin/app/autohost/core/broker/CapabilityRegistry.kt`

FIND (must occur exactly once):

```kotlin
        providers.addIfAbsent(provider)
        byCapability.getOrPut(provider.capabilityId) { CopyOnWriteArrayList() }.add(provider)
```

REPLACE WITH:

```kotlin
        // Registering the same provider twice must not duplicate it in the fallback chain.
        if (!providers.addIfAbsent(provider)) return
        byCapability.getOrPut(provider.capabilityId) { CopyOnWriteArrayList() }.add(provider)
```

**VERIFY**

```bash
grep -c "if (!providers.addIfAbsent(provider)) return" core/broker/src/main/kotlin/app/autohost/core/broker/CapabilityRegistry.kt   # expect: 1
test -f core/broker/src/test/kotlin/app/autohost/core/broker/CapabilityRegistryDuplicateTest.kt && echo present                     # expect: present
```

**COMMIT** `CRASH-2.2: CapabilityRegistry ignores duplicate registration (+test)`

---

## Step 2.3 — Library "Run": real run folder and a unique list key

**Why:** D9. Two one-line fixes in `app/src/main/kotlin/app/autohost/ui/AppNavHost.kt`, both inside `LibraryScreen` where `graph` is already in scope. The run folder now follows the same pattern `AppGraph.startRun` uses (`File(runsRoot(), Ids.ulid(clock.nowMs()))`), and the list key becomes the task's folder path, which is unique per dev folder.

**EDIT** `app/src/main/kotlin/app/autohost/ui/AppNavHost.kt` (list key)

FIND (must occur exactly once):

```kotlin
                items(tasks, key = { it.taskId }) { task ->
```

REPLACE WITH:

```kotlin
                items(tasks, key = { it.dir.absolutePath }) { task ->
```

**EDIT** `app/src/main/kotlin/app/autohost/ui/AppNavHost.kt` (run folder)

FIND (must occur exactly once):

```kotlin
                                            runDir = java.io.File(activity.filesDir, "runs/$(java.util.UUID.randomUUID())").absolutePath,
```

REPLACE WITH:

```kotlin
                                            runDir = java.io.File(
                                                graph.runsRoot(),
                                                app.autohost.core.common.Ids.ulid(graph.clock.nowMs()),
                                            ).absolutePath,
```

**VERIFY**

```bash
F=app/src/main/kotlin/app/autohost/ui/AppNavHost.kt
grep -c 'randomUUID' $F                                  # expect: 0
grep -c 'key = { it.dir.absolutePath }' $F               # expect: 1
grep -c 'graph.runsRoot(),' $F                           # expect: 1
```

**COMMIT** `CRASH-2.3: Library Run uses a real run folder and a unique list key`

---

### Batch 2 is done when

Rows 2.1 to 2.3 are ticked, VERIFY matched, three commits made. Now go to Checkpoint A.

---

# CHECKPOINT A — first Gradle run (after Batches 1 and 2)

This is the first time anything is compiled. Everything from Batches 1 and 2 is compiled together. Work from the repo root.

**If Gradle itself cannot start** (cannot download the JDK, Gradle or the Android SDK, no disk, no network, licence prompt): **STOP**. Paste the exact error in section 1.5 and wait for the owner. Do not try another toolchain, do not edit build files.

```bash
source /tmp/ah_gradle.sh

# A1: the three JVM modules touched so far. Fast, and it proves the new tests compile and pass.
ah_gradle :core:broker:test :capabilities:shell:test :core:engine:test 2>&1 | tail -n 40
```

Expect `BUILD SUCCESSFUL`. Then prove the two new tests really ran:

```bash
find core capabilities -path '*build/test-results*' -name 'TEST-*CapabilityRegistryDuplicateTest.xml'   # expect: 1 path
find core capabilities -path '*build/test-results*' -name 'TEST-*AdbKeyFilesCorruptFileTest.xml'        # expect: 1 path
```

```bash
# A2: the real app. First run downloads a lot and can take 10 to 30 minutes.
bash scripts/build-android-debug.sh 2>&1 | tail -n 40
ls -l app/build/outputs/apk/core/debug/app-core-debug.apk       # expect: the file exists
unzip -l app/build/outputs/apk/core/debug/app-core-debug.apk | grep -c "autohost/capability-specs-w1.json"   # expect: 1
```

- If the `grep -c` prints `0`: the build succeeded but the bundled capability list is **not inside the APK**, which would make the app fail at startup with "Missing bundled capability specs resource". Do **not** try to fix it. Log it and tell the owner (section 1.5); it is a finding, not a plan step.
- If A1 or A2 fails: follow the rules below.

**When the build fails**

1. Read the first `e:` (error) line, not the last. Kotlin errors look like `e: file:///.../Foo.kt:12:5 Unresolved reference: Bar`.
2. Decide whose code it is. Files this plan created or edited: fix with the smallest change (cheat sheet below). Any other file: write `(PRE-EXISTING) <file>:<line> <message>` in the log and ask the owner; do not redesign.
3. Maximum 10 fix attempts for this checkpoint. Then STOP and report.
4. After each fix, re-run only the failing command. Commit fixes as `CRASH-A: <what>`.

**Compile-error cheat sheet** (errors this plan's own code could plausibly produce)

| Error text | Likely cause | Fix |
|---|---|---|
| `Unresolved reference: CrashLog` in `AutoHostApp.kt` or `MainActivity.kt` | import missing | add `import app.autohost.diag.CrashLog` (steps 1.2 and 1.7a show it) |
| `Unresolved reference: CrashLog` in `AppGraph.kt` | typo | the code uses the full name `app.autohost.diag.CrashLog.record(...)`; make it match exactly |
| `Unresolved reference: SystemClockImpl` in `CrashLog.kt` | import missing | `import app.autohost.core.common.SystemClockImpl` |
| `Suspension functions can be called only within coroutine body` inside `startupStep { ... }` | helper is not `inline` | it must read exactly `private inline fun startupStep(name: String, block: () -> Unit)` |
| `Cannot assign to 'graph': the setter is private` | `graph =` written outside `AutoHostApp` | only `AutoHostApp.onCreate` and `AutoHostApp.publishGraph` may assign it |
| `Unresolved reference: remember` in `MainActivity.kt` | import missing | `import androidx.compose.runtime.remember` |
| `Unresolved reference: weight` in `CrashReportScreen.kt` | `Modifier.weight` used outside a Column or Row body | it must sit inside the outer `Column { ... }` block exactly as written |
| `Conflicting overloads` / `Redeclaration` for `FakeProvider` | a second copy was pasted | there must be exactly one `class FakeProvider` in the broker tests |
| `'providerId' overrides nothing` | wrong interface | `CapabilityProvider.providerId` exists (`CapabilityProvider.kt:17`); check the class really implements `CapabilityProvider` |
| `Unresolved reference: fingerprint` in `AdbKeyFilesCorruptFileTest.kt` | wrong type | `AdbKeyFiles(dir, name).load()` returns `AdbKeys`, which has `fingerprint()`; check you did not change step 1.5's REPLACE text |

**Test failures.** A test in `CapabilityRegistryDuplicateTest` or `AdbKeyFilesCorruptFileTest` failing is your problem: re-read steps 2.2 and 1.5 and compare your edit with the plan. Any other failing test is `(PRE-EXISTING)` unless it fails only after your change: log the test name and the first line of its message, do not touch it.

**Checkpoint A is done when:** A1 prints `BUILD SUCCESSFUL` (or only `(PRE-EXISTING)` failures remain, logged), both new test files appear in the results, A2 produced `app-core-debug.apk`, and the `capability-specs` check printed `1` (or was reported). Paste the last lines of each command in the log. Tick row **A**. Then Batch 3.

---

# BATCH 3 — Overlay windows must be driven from the main thread

Files in this batch: new `surfaces/MainThread.kt`; changed `BubbleController.kt`, `TaskOverlaySink.kt`, `InspectorOverlay.kt` (all in `app/src/main/kotlin/app/autohost/surfaces/`). Gradle is allowed again only at Checkpoint B (or if the owner says so).

**Background (finding D10):** `WindowManager.addView` needs a thread with a Looper, and views may only be changed on the main thread. The bubble collectors, the broker (for task highlight / progress-panel overlays) and the inspector refresh loop all run on background threads, so the bubble and the task overlays silently never appear, and the inspector crashes when you switch apps while it is on. The fix is one small helper and a handful of call-site changes; the existing method bodies are not touched.

## Step 3.1 — Add the `MainThread` helper

**CREATE FILE** `app/src/main/kotlin/app/autohost/surfaces/MainThread.kt`

```kotlin
package app.autohost.surfaces

import android.os.Handler
import android.os.Looper
import app.autohost.core.common.AhLog
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Crash-fix plan, step 3.1. Overlay windows (`WindowManager.addView`) and view changes must run on the main thread,
 * but the controllers and the broker call into overlays from background threads, so every overlay entry point goes
 * through here.
 */
internal object MainThread {
    private const val TAG = "main-thread"
    private const val DEFAULT_TIMEOUT_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())

    fun isMain(): Boolean = Looper.myLooper() === Looper.getMainLooper()

    /** Fire and forget. Runs inline when already on the main thread. Never throws. */
    fun post(block: () -> Unit) {
        if (isMain()) {
            runGuarded(block)
        } else {
            handler.post { runGuarded(block) }
        }
    }

    /**
     * Runs [block] on the main thread and waits for its result for at most [timeoutMs].
     * Returns [fallback] when the main thread stays busy for longer than that, or when [block] throws. Never throws.
     */
    fun <T> call(fallback: T, timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: () -> T): T {
        if (isMain()) {
            return try {
                block()
            } catch (e: RuntimeException) {
                logFailure(e)
                fallback
            }
        }
        val task = FutureTask(Callable { block() })
        handler.post(task)
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            task.cancel(false)
            logFailure(e)
            fallback
        }
    }

    private fun runGuarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            logFailure(e)
        }
    }

    private fun logFailure(e: Exception) {
        AhLog.w(TAG, "overlay work failed: ${e.javaClass.simpleName}: ${e.message}")
    }
}
```

**VERIFY**

```bash
grep -c "^internal object MainThread" app/src/main/kotlin/app/autohost/surfaces/MainThread.kt   # expect: 1
```

**COMMIT** `CRASH-3.1: add MainThread helper for overlay work`

---

## Step 3.2 — `BubbleController`: window work on the main thread

**Why:** the two collectors in `start()` run on a background dispatcher and call `showOrUpdate`, `hide` and `overlay = null`. They now post that work to the main thread. This also makes the `overlay` field main-thread-only, which removes a race between the two collectors.

**EDIT** `app/src/main/kotlin/app/autohost/surfaces/BubbleController.kt` (runs collector)

FIND (must occur exactly once):

```kotlin
                if (shouldShow) {
                    showOrUpdate(runs.count { it.active }, runs.firstOrNull { it.active })
                } else {
                    hide()
                }
```

REPLACE WITH:

```kotlin
                // Crash-fix 3.2: window work must run on the main thread.
                if (shouldShow) {
                    val activeCount = runs.count { it.active }
                    val firstActive = runs.firstOrNull { it.active }
                    MainThread.post { showOrUpdate(activeCount, firstActive) }
                } else {
                    MainThread.post { hide() }
                }
```

**EDIT** `app/src/main/kotlin/app/autohost/surfaces/BubbleController.kt` (a11y connection collector)

FIND (must occur exactly once):

```kotlin
                if (service == null) {
                    overlay = null
                } else {
                    val active = graph.runStore.runs.value.filter { it.active }
                    val policyRuns = active.map { BubblePolicy.BubbleRun(it.runId, true, uiActing[it.runId] ?: false) }
                    if (BubblePolicy.decide(prefs.bubbleMode(), policyRuns)) showOrUpdate(active.size, active.firstOrNull())
                }
```

REPLACE WITH:

```kotlin
                if (service == null) {
                    MainThread.post { overlay = null }
                } else {
                    val active = graph.runStore.runs.value.filter { it.active }
                    val policyRuns = active.map { BubblePolicy.BubbleRun(it.runId, true, uiActing[it.runId] ?: false) }
                    if (BubblePolicy.decide(prefs.bubbleMode(), policyRuns)) {
                        val activeCount = active.size
                        val firstActive = active.firstOrNull()
                        MainThread.post { showOrUpdate(activeCount, firstActive) }
                    }
                }
```

**VERIFY**

```bash
grep -c "MainThread.post" app/src/main/kotlin/app/autohost/surfaces/BubbleController.kt   # expect: 4
```

**COMMIT** `CRASH-3.2: BubbleController drives the overlay from the main thread`

---

## Step 3.3 — `TaskOverlaySink`: window work on the main thread

**Why:** the broker calls `highlight`, `panel` and `clear` from background threads. Each public method now forwards to a private `...OnMain` method that holds the **unchanged** old body; `highlight`, `panel` and `clear` wait up to 1.5 s for the main thread and return the "failed" value (`false`, `false`, `0`) if it is too busy; `clearAll` is fire-and-forget. Four edits, each replacing only the first two lines of a method. Everything below those lines stays exactly as it is.

**EDIT** `app/src/main/kotlin/app/autohost/surfaces/TaskOverlaySink.kt` (highlight)

FIND (must occur exactly once):

```kotlin
    override fun highlight(runId: String, x: Int, y: Int, w: Int, h: Int, label: String?, durationMs: Long): Boolean {
        val service = A11yBridge.service.value ?: return false
```

REPLACE WITH:

```kotlin
    override fun highlight(runId: String, x: Int, y: Int, w: Int, h: Int, label: String?, durationMs: Long): Boolean =
        MainThread.call(false) { highlightOnMain(runId, x, y, w, h, label, durationMs) }

    private fun highlightOnMain(
        runId: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        label: String?,
        durationMs: Long,
    ): Boolean {
        val service = A11yBridge.service.value ?: return false
```

**EDIT** `app/src/main/kotlin/app/autohost/surfaces/TaskOverlaySink.kt` (panel)

FIND (must occur exactly once):

```kotlin
    override fun panel(runId: String, text: String, corner: String): Boolean {
        val service = A11yBridge.service.value ?: return false
```

REPLACE WITH:

```kotlin
    override fun panel(runId: String, text: String, corner: String): Boolean =
        MainThread.call(false) { panelOnMain(runId, text, corner) }

    private fun panelOnMain(runId: String, text: String, corner: String): Boolean {
        val service = A11yBridge.service.value ?: return false
```

**EDIT** `app/src/main/kotlin/app/autohost/surfaces/TaskOverlaySink.kt` (clear)

FIND (must occur exactly once):

```kotlin
    override fun clear(runId: String): Int {
        val service = A11yBridge.service.value ?: return 0
```

REPLACE WITH:

```kotlin
    override fun clear(runId: String): Int = MainThread.call(0) { clearOnMain(runId) }

    private fun clearOnMain(runId: String): Int {
        val service = A11yBridge.service.value ?: return 0
```

**EDIT** `app/src/main/kotlin/app/autohost/surfaces/TaskOverlaySink.kt` (clearAll)

FIND (must occur exactly once):

```kotlin
    fun clearAll() {
        val service = A11yBridge.service.value ?: return
```

REPLACE WITH:

```kotlin
    fun clearAll() {
        MainThread.post { clearAllOnMain() }
    }

    private fun clearAllOnMain() {
        val service = A11yBridge.service.value ?: return
```

**VERIFY**

```bash
F=app/src/main/kotlin/app/autohost/surfaces/TaskOverlaySink.kt
grep -c "MainThread\." $F          # expect: 4
grep -c "OnMain(" $F               # expect: 8
```

**COMMIT** `CRASH-3.3: TaskOverlaySink drives overlays from the main thread`

---

## Step 3.4 — `InspectorOverlay`: view changes on the main thread

**Why:** the inspector's refresh loop (background) calls `refresh()` and `updateInfo()`. `View.invalidate()` and `TextView.text = ...` on an attached view from a background thread throw `CalledFromWrongThreadException`; `updateInfo` has no guard, so today this crashes the app when you switch apps while inspecting.

**EDIT** `app/src/main/kotlin/app/autohost/surfaces/InspectorOverlay.kt` (refresh)

FIND (must occur exactly once):

```kotlin
        val canvas = canvasView ?: return false
        canvas.setNodes(nodesProvider())
        runCatching { canvas.invalidate() }
        return true
```

REPLACE WITH:

```kotlin
        val canvas = canvasView ?: return false
        canvas.setNodes(nodesProvider())
        // Crash-fix 3.4: invalidate() must run on the main thread; the refresh loop runs on a background dispatcher.
        MainThread.post { canvas.invalidate() }
        return true
```

**EDIT** `app/src/main/kotlin/app/autohost/surfaces/InspectorOverlay.kt` (updateInfo)

FIND (must occur exactly once):

```kotlin
    fun updateInfo(text: String) {
        infoRef?.text = text
    }
```

REPLACE WITH:

```kotlin
    fun updateInfo(text: String) {
        val info = infoRef ?: return
        // Crash-fix 3.4: setting text on an attached TextView must run on the main thread.
        MainThread.post { info.text = text }
    }
```

**VERIFY**

```bash
grep -c "MainThread.post" app/src/main/kotlin/app/autohost/surfaces/InspectorOverlay.kt   # expect: 2
```

**COMMIT** `CRASH-3.4: InspectorOverlay updates views on the main thread`

---

# BATCH 4 — Small hardening and the log entry

## Step 4.1 — Manifest: media-projection foreground-service permission

**Why:** D11. On Android 14+ a service declared with `foregroundServiceType="mediaProjection"` also needs this permission in the manifest, otherwise starting it throws `SecurityException`. One line, ignored by older Android versions.

**EDIT** `app/src/main/AndroidManifest.xml`

FIND (must occur exactly once):

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

REPLACE WITH:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

**VERIFY**

```bash
grep -c "FOREGROUND_SERVICE_MEDIA_PROJECTION" app/src/main/AndroidManifest.xml   # expect: 1
```

**COMMIT** `CRASH-4.1: declare FOREGROUND_SERVICE_MEDIA_PROJECTION for Android 14+`

---

## Step 4.2 — `worklog.md` entry

**Why:** the repo keeps an append-only `worklog.md` (`AGENTS.md`). Find the last session number, then append one new section at the **end** of the file. Do not edit older text.

```bash
grep -n '^## Session' worklog.md | tail -n 1      # today this prints Session 19; use the next number (20 unless something newer exists)
```

Append (fill in the real results; keep it short and factual):

```markdown

## Session 20 — crash-fix pass (CRASH_FIX_PLAN.md, batches 1 to 4)

**Scope delivered:** CrashLog + in-app crash report (Copy/Share); `AutoHostApp` builds the graph only in the main process and records a failed build; `AppGraph` publishes itself first in `init`, `appScope` has a `CoroutineExceptionHandler`, delayed startup steps are isolated; `CapabilityCatalog` named error; ADB key regenerates when corrupt (+test); `FakeProvider.providerId`; `CapabilityRegistry` duplicate guard (+test); Library Run folder and list key; overlay work on the main thread (bubble, task overlays, inspector); `FOREGROUND_SERVICE_MEDIA_PROJECTION`.

**Verification:** Checkpoint A: <paste result>. Checkpoint B: <paste result, APK sha256>. PRE-EXISTING failures found: <list or "none">.

**Open:** owner phone test (CRASH_FIX_PLAN.md section 8); owner decisions in its Appendix A.
```

**VERIFY**

```bash
tail -n 12 worklog.md | grep -c "Session 20"      # should print: 1
```

**COMMIT** `CRASH-4.2: worklog entry for the crash-fix pass`

---

# CHECKPOINT B — full build and all JVM tests (after Batches 3 and 4)

```bash
source /tmp/ah_gradle.sh

# B1: the app, with all four batches compiled together
bash scripts/build-android-debug.sh 2>&1 | tail -n 40
ls -l app/build/outputs/apk/core/debug/app-core-debug.apk       # expect: the file exists
sha256sum app/build/outputs/apk/core/debug/app-core-debug.apk   # write the hash in the log

# B2: every JVM test in the repo (long). The script stops at the first failing Gradle command.
bash scripts/test-jvm.sh 2>&1 | tail -n 60

# B3: repo bookkeeping must be the same as at step 0.2
python3 tools/progress.py --check; echo "exit=$?"
```

- If `test-jvm.sh` stops at a failure that is `(PRE-EXISTING)`, run the remaining `run_gradle ...` lines of that script one by one with `ah_gradle` (they are listed in `scripts/test-jvm.sh`) and log each result. Do not fix PRE-EXISTING failures.
- Failure handling is the same as in Checkpoint A (smallest fix, cheat sheet first, at most 10 attempts, then STOP).

**Checkpoint B is done when:** B1 produced the APK and you wrote its sha256 in the log; B2 finished (`Finished:` printed, or only logged PRE-EXISTING failures); B3 gives the same result as step 0.2.

Then write this in section 1.5 and **stop**:

```text
READY FOR OWNER PHONE TEST
APK: app/build/outputs/apk/core/debug/app-core-debug.apk   sha256: <hash>
PRE-EXISTING problems found: <list or none>
Owner decisions still open: Appendix A items
```

---

# 8. FOR THE OWNER — phone test (the agent does **not** do this section)

Your phones from the repo notes: Realme C3 (Android 11) and Samsung M31s (Android 12). Both are fine for this build.

1. **Uninstall the old build first** (Settings, Apps, AutoHost, Uninstall; or `adb uninstall app.autohost`). Old builds may have left a database or ADB key from earlier code, and the database has no automatic reset, so an old install can crash a new build for reasons unrelated to these fixes. Uninstalling also tests the real first-launch path.
2. **Install** `app/build/outputs/apk/core/debug/app-core-debug.apk` from the agent's build (the agent writes its sha256 in the tracker log; the file you install should match).
3. **Open the app and leave it open for about a minute.** Some startup steps run about 4 seconds after launch.
4. **Read what you see:**

| You see | Meaning | What to do |
|---|---|---|
| The Tasks (Library) screen, no report | Launch works | Go to step 5 |
| A screen titled **"AutoHost could not start"** | The object graph failed to build; the text is the exact reason | Tap **Copy** (or **Share**), paste the text to whoever is helping. There is no Dismiss on this screen on purpose |
| A screen titled **"AutoHost recorded a problem on its previous run"** | The last run crashed, or a background step failed and the app survived it | Tap **Copy**, paste the text, then **Dismiss** |
| The app closes at once with no screen | Something dies before any screen can show | Open it a second time: the report of that crash is saved and shows now. If it still closes: use the adb commands below |

5. **Quick behaviour checks** once it opens: the **Setup** tab opens; the **Library** list shows (add a dev-folder task if you have one and press **Run** once: a new folder with a long id name should appear under the app's `files/runs/`, not a folder named `$(java.util.UUID...)`); turn on the accessibility service, start a run of a UI-acting task and check that the **floating bubble** appears (it never could before); open the **Inspector** from the Surfaces screen, switch to another app and back: no crash.
6. **If you have adb** (optional, more detail):

```bash
adb logcat -b crash -d                                   # the Android crash buffer
adb logcat -d | grep -E "autohost|AndroidRuntime"        # app log lines
adb pull /sdcard/Android/data/app.autohost/files/crash/last-crash.txt     # rolling history of reports, newest first
```

Android 11 and newer hide `Android/data` from file-manager apps, so use the in-app **Copy / Share** buttons or adb.

**Send back:** the copied report text (or the adb output), the phone model, and one line on what you did just before.

---

# APPENDIX A — Owner decisions (the agent must NOT change these)

| # | Item | Facts | Why it needs you |
|---|---|---|---|
| A1 | **Dev-mode grants everything** | `GrantBuilder(permissionIndex, devMode = true)` at `AppGraph.kt:163` and `:340` grants every permission to every run until the "Phase 2 permission review" exists (`GrantBuilder.kt:44-47`). | Fine on your own phone while developing. Must change before you share a build with anyone. It is a security-posture decision, not a bug fix. |
| A2 | **FileProvider exposes the whole private files folder** | `app/src/main/res/xml/file_paths.xml` line 7 is `<files-path name="files" path="." />`, but the file's own comment says only task-data and cache folders should be exposed. The root `.` also covers `files/adb/adbkey.pk8.b64` (the ADB private key). | Not exploitable on its own (the provider is not exported; a URI is only ever handed out by code that asks for one), but one wrong `getUriForFile` call away from leaking the key. Narrowing it means knowing which sub-folders the app really shares. Decide first, then it is a one-line change. |
| A3 | **Docs say "Android 10 primary", your phones are Android 11 and 12** | `AGENTS.md` rule 10 says real-device gates run on "the primary Android 10 phone"; `.agents/memory/android-target-devices.md` records Realme C3 = Android 11 / API 30 and Samsung SM-M315F = Android 12 / API 31. | Which phone is the "gate" device is yours to say; then `docs/DEVICES.md` and the gate wording should be updated. No code change. |

---

# APPENDIX B — Known, not fixed in this plan

| # | Item | Note |
|---|---|---|
| B1 | **Grants snapshot** | `AppGraph.kt:250-254` passes `grantBuilder` into `RunOrchestrator` by value (`RunOrchestrator.kt:58`); `attachPluginPermissions` later assigns a new `GrantBuilder` (`AppGraph.kt:340`) that the orchestrator never sees. Only matters for plugin permissions. Fix needs a signature change (pass a provider lambda). |
| B2 | **RSA-2048 key generation on the main thread, first launch only** | `AppGraph.kt:758` builds the ADB key in the constructor. On a low-end phone this can take a few seconds once. Not a crash. Move to lazy later. |
| B3 | **`runBlocking` on the main thread in some UI paths** | Database access is all `suspend`/`Flow`, so no "database on main thread" crash, but UI can stall. |
| B4 | **Dev-folder scan runs on the main thread** | `LibraryScreen` calls `graph.refreshDevTasks()` inside `LaunchedEffect` (main dispatcher); file I/O there. |
| B5 | **Convention drift (cosmetic)** | `Build.VERSION.SDK_INT` is read outside `ApiGate` in many places (for example `DevApiWiring.kt`, `SurfacesScreen.kt`, `AndroidNotificationSink.kt`, `RunForegroundService.kt`, `AppGraph.kt:663,748`); `System.currentTimeMillis()` is used in `WebhookController.kt:39`. Against `AGENTS.md` rules, harmless at runtime. |
| B6 | **`./gradlew check` (ktlint, detekt)** | Not run by this plan. The repo has lines over 120 columns (for example in `TaskOverlaySink.kt` and `AppNavHost.kt`), so ktlint with `ignoreFailures = false` will fail until someone formats the code. `AGENTS.md` mentions `./gradlew check`, but the repo has no `gradlew`; the scripts in `scripts/` are what actually work. |
| B7 | **Upgrade installs and the database** | `AutoHostDatabase` is version 2 with only a 1→2 migration and no destructive fallback (by design). A future schema change needs a migration, and a build whose schema differs under the same version number crashes on an old install. This is why section 8 says to uninstall first. |
| B8 | **More latent bugs are likely** | See section 2.3: the code base was written without ever being built or run end to end. The crash screen is how the next ones get found. |

---

# APPENDIX C — What was checked, and how

| Claim | How it was established | Still unverified |
|---|---|---|
| Every FIND text in this file exists exactly once in the repo | A script applied every edit of this file to a scratch copy of the uploaded repo (exact-match, exactly-once check) | – |
| Line references in section 2.1 | Read from the uploaded repo | – |
| D1 launch-crash race | Code path read end to end: `AppGraph.kt:1418` → `TriggerController.start/reconcile/refresh` (`:85-95`) → `data` getter (`:58`) → `AutoHostApp.graph` assigned only after the constructor returns (`AutoHostApp.kt:24`); no handler anywhere | Not reproduced. It is a thread race |
| D3 multi-process | Manifest has 5 `android:process` services; no `Configuration.Provider`; WorkManager default initialiser is not removed (so it runs in the main process only) | Not observed on a device |
| New Kotlin compiles | Nothing was compiled (no Android SDK available to the author). Code written conservatively; imports and signatures cross-checked against the repo (`AhLog`, `SystemClockImpl`, `Ids.ulid`, `CapabilityProvider`, `AdbKeys.fingerprint`) | **Checkpoints A and B are the first compile** |
| Behaviour on a phone | – | Section 8 |
