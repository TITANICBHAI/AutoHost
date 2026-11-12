# STATUS — rewrite this file at the end of every session (do not append). Keep it under 150 lines.

Updated: 2026-11-12 · by: Session 2 (+ S03 smoke test) · branch: work/s02-green · last pushed commit: bf14f3e

## Now
**S04 (`work/plan/S04_task_list_back_setup.md`)**: steps 1–6 are written, compile and pass unit tests on CI (run 14, commit bf14f3e), but nobody has seen them run.
Left in S04: step 7 (Robolectric test that a freshly installed task shows up in Library) and the Studio task list (`TaskStudioScreen` still lists the dev folder only). Then S05 (schedule form screen).
Owner: please open a pull request from `work/s02-green` into main (the API token here cannot create one).

## Works (evidence: CI run 11, commit dc45104 — all five workflows green: jvm-tests, jvm-core, jvm-capabilities, Android debug APK, ci)
- **1349 JVM tests, 0 failed, 0 Kotlin compile errors** in 29 modules (`ci-results` branch, `latest.md`, run 11). Python root suite 70 tests OK (`ci` workflow).
- **The app starts on the JVM**: `app/src/testCoreLite/.../LaunchSmokeTest.kt` (Robolectric, SDK 35) builds the app graph and the first screen without a crash. `:app` 188 tests, 0 failed.
  This is a JVM simulation, NOT a phone: nothing here proves a real device launch, permissions, the accessibility service, or the UI looking right.
- Debug APK builds on CI (artifact of the `Android debug APK` workflow). Not installed or run anywhere by me.
- Real product bugs found by the tests and fixed in Session 2 (each has its own commit, `S02-…`):
  · model gateway never retried: every 429/5xx returned at once (`core/llm` LlmGateway).
  · wireless pairing crashed: HKDF with an empty salt → "Empty key" (`capabilities/shell` WirelessPairing).
  · tool catalog read JSON `null` as the string "null" (host tools had capability "null") (`runtime/agent` ToolCatalog).
  · agent completion treated an answer without a readable `confirmed` field as a user rejection; now Unverified (fail closed).
  · logcat line regex, ADB auth order (signature, then public key), `shell,v2` CLSE fallback, HTTP engine watcher/headers, no-tool nudge counting, stuck-warning at 3 screens (earlier S02 commits).
- Earlier session facts still true: Python SDK/simulator suites pass; `ScheduleForm` + `NavStack` logic verified by the golden vectors and unit tests (now also run by Gradle on CI).

## Broken or unverified
- **Never run on a device or emulator** (D-24/D-25). Everything above the JVM line is unverified: Compose screens, back handling, rotation, Setup scrolling, StrictMode, permissions.
- 4 modules produce no test results because they have no tests (`runtime/api`, `runtime/python`, `plugin-sdk`, `testing/fixture-app`); not an error, but also not covered.
- Style baseline: 18963 ktlint/detekt findings (report only, never fails the job). Not addressed.
- Robolectric is in the licence allowlist as MIT "as far as known" — owner should confirm against the upstream LICENSE.
- Written, compiled, unit-tested, not yet seen on a screen: `TaskCatalog`, the new Library (cards with Ready/Running chip, last result, search, + and ⋮ menu; Record/Studio/Ask behind the Labs switch in Settings, default off), Surfaces pickers on the combined list, `NavStack` in `MainActivity`, Setup scroll, debug StrictMode. 16 new tests (`TaskListModelTest`).
- Library gaps: no "Needs setup" chip yet (no cheap data source); Settings is reached from the ⋮ menu or Setup; the Labs switch is only in Settings.
- Not done: the Studio task list still reads the dev folder only; the Schedule screen (S05 steps 2–4: schedules still need JSON on screen, S-01).
- Release signing (S06) needs owner steps (key + secrets); a phone session (S07/S08) needs the owner.

## Needs you (owner decisions, still open)
1. Open a pull request `work/s02-green` → main (or tell me to merge differently).
2. A phone session: install the debug APK from the `Android debug APK` run and tell me what you see (Setup, Library, Back button).
3. Labs switch default (on/off), FileProvider removal, signing key/secrets, naming "Tasks" vs "Automations".

## Frozen (Labs)
Ask/agent runtime, Task Studio, Recorder, plugins/app packs, webhooks, Dev API/MCP, reverse bridge, browser, vision, `full` flavor. Kept compiling and tested; not extended.

## Next 5
1. S04 leftovers: Studio list, Robolectric Library test.
2. S05: schedule form screen without JSON (uses `testing/golden/schedule_form_vectors.json`).
3. S06: stable signing (owner steps first).
4. S07: first run on a phone (owner).
5. S08: fix what the phone shows.

## Lessons (from this and earlier sessions)
- Read the digest's `details.md` (all errors and failing tests) before touching code; the 150-line digest hides most of it.
- Most remaining "failures" after the compile errors were wrong TESTS, not wrong code: `Job.join()` does not throw for a cancelled job; `advanceUntilIdle()` returns at once when only `backgroundScope` work is left; a stuck detector stops 5 identical screens; an empty `packages` grant means "nothing"; the selector mode is `eq`.
- But four were real product bugs (list above). Check which it is by reading the code, not by making the test pass.
- Heredoc edits half-persist; use exact find/replace and re-read the file. Keep CRLF as found.
- A new interface method breaks every fake; a new parameter must reach every call site.
- A test that waits on a clock needs a clock that moves (`FakeClock(autoAdvancePerReadMs=…)`).
- Robolectric 4.16.1 supports SDK 36 only on JDK 21; CI uses JDK 17, so tests pin SDK 35.
