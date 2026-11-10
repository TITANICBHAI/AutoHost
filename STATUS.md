# STATUS — rewrite this file at the end of every session (do not append). Keep it under 150 lines.

Updated: 2026-11-10 · by: Session 1 + early parts of S04/S05 (all unverified on CI) · branch: work/s01-pack

## Now
**Push this branch, read the first CI digest, then Session 2 — fix what it lists** (`work/plan/S02_fix_what_ci_shows.md`). Session 1 is done locally (pack applied); it needs a push, a PR and the first `jvm-tests` run, which has not happened yet.

## Works (evidence required: CI run link, test names, or owner screenshot)
- Python side, after the reset and all six patches (sandbox run 2026-11-10, `scripts/test-python.sh`): SDK + simulator suite 436 tests OK,
  root suite 69 tests OK, `gen_artifacts --check`, `sync_sdk --check`, flow/schedule golden drift checks, `licence_check`, `mcp_eval` all OK.
- All six patches passed `git apply --check` and applied in order on the repo as uploaded.
- `ScheduleForm` (core/engine, S05 step 1): compiled with Kotlin 2.2.21 (same version as the repo) against copies of the model/engine sources and run on all 30 cases of
  `testing/golden/schedule_form_vectors.json`: valid cases give the exact reference rule AND the real `ScheduleEngine.nextFires` gives the reference next-5; invalid cases give exactly the listed
  error keys; form→rule→form→rule round-trips. The JUnit files (`ScheduleFormGoldenTest`, `ScheduleFormTest`) were NOT run by Gradle.
- `NavStack` (S04 step 4, pure back-stack): same local compile + an equivalent check run. `NavStackTest` was not run by Gradle.
- No CRLF text files found by `m0_reset.py --check-eol` (only a PNG false positive); golden vectors need no LF normalisation.
- (Nothing is verified on Android yet. The app has never been launched on a device or emulator. D-24 / D-25.)

## Broken or unverified (item ids from work/plan)
- C-01 CI ran the wrong Gradle tasks (all variants) and never ran `:runtime:agent` / `:core:llm` → fixed by `jvm-tests.yml`, unverified until it runs.
- C-02 `:capabilities:data` test hang · C-03 `:runtime:flow` test hang · C-04 manifest lacked backup rules · C-05 FileProvider exposed `files/`
  → patches applied in Session 1, unverified until CI runs.
- Unknown: the other failing `:app` unit tests (see the digest).
- Written but never compiled by Gradle (Compose and Android-module code; CI is the judge): `TaskCatalog` + Library list showing installed AND dev-folder tasks (U-01; installed tasks open their detail page),
  `NavStack` wired into `MainActivity` (U-02: BackHandler, state survives rotation), Setup screen scrolls + Always-on recipe collapsed, debug-only StrictMode (log only). Android API calls were type-checked against android-34 `android.jar`.
- Not done yet: `SurfacesScreen` and the Studio lists still read the dev folder only (S04 step 2); Library card status chips / last result / search / Labs switch (S04 step 3); the Schedule *screen* (form UI, Schedule tab, Developer switch: S05 steps 2–4);
  S03 launch smoke test; S02 (needs the first CI digest).
- S-01 schedules need JSON on screen → the pure form logic exists, the screen still shows the JSON editor.

## Frozen (Labs)
Ask/agent runtime, Task Studio, Recorder, plugins/app packs, webhooks, Dev API/MCP, reverse bridge, browser, vision, `full` flavor.

## Next 5
1. Session 1: apply the pack (docs reset, CI, test fixes, Python runner fixes). Push; read the first digest.
2. Session 2: fix what the digest lists, one module per push.
3. Session 3: Robolectric launch smoke (prove the app starts).
4. Session 4: one task list everywhere (E-01), Back stack, Setup scroll, StrictMode.
5. Session 5: schedule form without JSON (S-01) using `testing/golden/schedule_form_vectors.json`.

## Lessons (from the repo's own history)
- Heredoc edits half-persist; use exact find/replace and re-read the file.
- A new interface method breaks every fake: search all implementers first.
- A new parameter must be threaded through every call site.
- Int × Double is Double in Kotlin; missing imports are only found by the compiler (CI).
- A test that waits on a clock needs a clock that moves; a test that blocks a thread needs a real thread, not a virtual-time coroutine.
