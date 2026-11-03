# AGENT_STATE — working memory for the next session

*Updated: 2026-11-03. Read `docs/AGENT_OPERATING_PROTOCOL.md` first; this file only points at
authoritative sources.*

## Where the project stands

- Phases 1–11 written, unverified; Epoch V (verify-what-exists) is open; Phase 12+ code starts only
  after X-09 (ADR-097). Queue: `HANDOFF.md` §0.2. Dashboard: `docs/PROGRESS.md` (regenerate with
  `python tools/progress.py`).
- Crash-fix pass (`CRASH_FIX_PLAN.md`) fully text-edited (Batches 1–4, session 20); Checkpoints A/B
  were never run — that is **X-17**.

## Environment facts (this checkout, verified 2026-11-03)

- Windows 10 PC, weak (3.9 GB RAM). **Not a git repository** — no commits possible; write `no git`.
- Python works: `python tools/progress.py --check` ok, `python tools/doc_check.py` ok (53 files),
  `tests/` suite 75 OK, `sdk-python` suite 415 OK, licence check ok.
- The ZCode harness provides a Git Bash shell; the owner's rules still forbid Gradle/JVM/emulator
  runs locally without explicit approval (ADR-101, `CRASH_FIX_PLAN.md` §1.1a).
- **D-22 decided 2026-11-03:** the owner chose GitHub Actions for hosted builds/tests. Active
  workflow `android-debug-apk.yml` builds the APK only (no tests). `drafts/ci.workflow.yml` waits
  for X-08 (after X-07). Getting the tree pushed is an owner-side step.

## Current assignment (HANDOFF §0.2)

1. ✅ Orientation checks (all green, 2026-11-03).
2. ✅ Build-approval question superseded for hosted runners by the D-22 decision (docs reconciled).
3. **X-19** (`tests/test_architecture.py`) and **X-12 SDK/simulator half** — no Gradle needed.
   Status: see the task rows in `docs/PROGRESS.md` and this file's "Last session" below.
4. X-17 via GitHub Actions once the tree is on the remote (owner step) — Checkpoint A then B per
   `CRASH_FIX_PLAN.md`, Windows commands in `HANDOFF.md` §0.4 if the owner approves a local run.
5. Then Epoch V proper (X-07, X-08, JVM `(FAILING)` sweep, ladder retag, X-09).

## Last session (2026-11-03)

- D-22 reconciled in `docs/DECISIONS.md` (D-22 row + ADR-101 note) and `drafts/README.md`.
- Created `docs/AGENT_OPERATING_PROTOCOL.md` and this file; both added to the `AGENTS.md` doc map.
- X-19: `tests/test_architecture.py` — status recorded on the X-19 row in `docs/PROGRESS.md`.
- X-12 SDK/simulator half: status recorded on the X-12 row in `docs/PROGRESS.md`.
- Worklog entry: Session 22 in `worklog.md`.

## Open blockers / owner items

- X-18 phone test, D-23 (FileProvider scope, blocks X-22), P0-01 device facts — all owner-side.
- Pushing this tree to a git remote so GitHub Actions can run (prerequisite for X-17 via CI).

## Next-session entry point

Read `HANDOFF.md` §0.2 again, then pick up at the first unticked item of the queue above. Do not
trust this file over a task row; the rows in `docs/PROGRESS.md` and the checks
(`progress.py --check`, `doc_check.py`) are the truth.
