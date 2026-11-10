# STATUS — rewrite this file at the end of every session (do not append). Keep it under 150 lines.

Updated: <date> · by: <session> · branch: <work branch>

## Now
**Session 1 — apply the starter pack** (`work/plan/S01_apply_pack.md`). Next after it: Session 2 (read the first CI digest, fix what it shows).

## Works (evidence required: CI run link, test names, or owner screenshot)
- Python SDK + simulator and the repo tooling: all suites pass (checked in a sandbox on 2026-11-07: SDK 436 tests, root
  tests all OK). Re-run `scripts/test-python.sh` and put today's numbers here.
- (Nothing is verified on Android yet. The app has never been launched on a device or emulator. D-24 / D-25.)

## Broken or unverified (item ids from work/plan)
- C-01 CI ran the wrong Gradle tasks (all variants) and never ran `:runtime:agent` / `:core:llm` → fixed by `jvm-tests.yml`, unverified until it runs.
- C-02 `:capabilities:data` test hang · C-03 `:runtime:flow` test hang · C-04 manifest lacked backup rules · C-05 FileProvider exposed `files/`
  → patches applied in Session 1, unverified until CI runs.
- Unknown: the other failing `:app` unit tests (see the digest).
- U-01 task list hides tasks made from examples/imports · U-02 Back button · S-01 schedules need JSON → not started.

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
