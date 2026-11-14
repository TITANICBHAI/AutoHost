# STATUS — rewrite this file at the end of every session (do not append). Keep it under 150 lines.

Updated: 2026-11-14 · by: Session 2 (UI v2) · branch: work/s02-green · last pushed commit: 14561e8 (CI run 24: GREEN, 1451 JVM tests, APK builds)

## Now
UI v2 (`docs/UI_V2.md`) is in: three tabs **Tasks / Setup (Start here on first launch) / Settings**, quick macros, Add a task (template · macro · .zip import), feature catalog tested against the capability contract, optional-ADB access plan.
Nobody has seen any of it on a screen. Owner: please open a pull request `work/s02-green` → main (the token here cannot), install the debug APK from the `Android debug APK` run and tell me what the first launch looks like.

## Works (CI evidence only; JVM, not a phone)
- 1451 tests, 0 failed, 0 compile errors; Python root suite OK; Android debug APK builds; the app starts in the Robolectric launch smoke test (`LaunchSmokeTest`), now with the new tabs.
- Pure, tested UI logic in `app/.../ui2/`: `FeatureCatalog` (every one of the 47 capabilities → what the person must do; test fails if the contract and the catalog differ), `AccessPlan` (advanced-access routes, computer-free first), `Readiness`, `PhonePlan`, `GetStarted` (first-run checklist), `TodayModel`, `SettingsHubModel`, `macro/MacroCompiler` (compiled flow accepted by the real `FlowParser`, task by `TaskManifest`).
- Earlier real bugs fixed (LLM gateway retry, pairing HKDF, tool catalog null, completion fail-closed, etc.) — see git log `S02-…`.

## Broken or unverified
- **Never run on a device** (D-24/25). Compose layout, small-screen fit (320 dp, font 1.3), back handling, first-run flow, permissions: all unverified.
- Advanced access without a computer: only **Shizuku (Android 11+)** and **root** are wired. In-app Wireless-debugging pairing is a DRAFT (`exec` throws UNSUPPORTED). Plain ADB needs a computer. The UI says so.
- Not built: pick a whole folder as a task; write a Python script in the app; "Grant myself permanent permissions" (one-time secure-settings grant); Needs-setup filter chip; per-task "Can do" tab (uses `Readiness`); Studio list reads the dev folder only; Recorder saves `flow.json` without `task.json`, so recorded flows never reach the Library.
- Triggers are still created per task (task detail); the Schedules screen lists schedules only.
- Agent: no screenshot tool, no memory/subagents, local model only via a localhost OpenAI-compatible server (`docs/REFERENCE_COMPARISON.md`). Python SDK has no `llm` module.
- Style baseline: ~19k ktlint/detekt findings (report only). Robolectric licence row is "as far as known — confirm".

## Needs you
1. Pull request `work/s02-green` → main. 2. Phone session with the debug APK (first launch, Back, Settings hub, Add a task, macro). 3. Decisions: Labs default, FileProvider removal, signing key/secrets, tab names ("Tasks" vs "Automations").

## Next
1. Screenshot tool for the agent + Python `llm` module (small; capabilities exist).
2. Per-task "Can do" tab with Allow-all; Needs-setup chip.
3. Folder import and an in-app script editor.
4. More trigger events (location, Bluetooth, calendar); global trigger list in When.
5. S05 schedule form without JSON; S06 signing; S07/S08 phone session.

## Lessons
- Read `details.md` on the `ci-results` branch (all errors and failing tests) before touching code; fetch with `git fetch -q origin ci-results:refs/remotes/origin/ci-results`.
- Most "failures" after compile errors were wrong tests, but four were real product bugs: decide by reading the code.
- A completeness test against the contract caught a missing capability (`device.intent`) in the new catalog: generate UI from data, test the join.
- A negative test that greps copy ("no computer") must forbid asking, not mentioning.
- Missing imports (`dp`, `rememberSaveable`) are the usual first CI failure on Compose files: check imports when writing without a compiler.
- Keep LF/CRLF as found; use exact find/replace and re-read.
