# Epoch V — verify what exists (kickoff prompt, merged plan 2026-11-03)

Read `AGENTS.md` (rules 1, 2 and 16–19), `HANDOFF.md` §0, `docs/PROGRESS.md` (the Cross-cutting block: X-17, X-18, X-07…X-09, then X-10…X-22), `CRASH_FIX_PLAN.md` (§1.3–1.5 and Checkpoints A and B), `docs/GATES.md` (Epoch V), `scripts/test-jvm.sh`, `scripts/build-android-debug.sh`, and ADR-084, ADR-085, ADR-087, ADR-096, ADR-097, ADR-101 in `docs/DECISIONS.md`. Do not read `archive/`; open `reference/` only through a `docs/REUSE_MATRIX.md` row.

## Goal
Turn "written" into "verified" for what already exists — starting with the one thing known to be broken: the app crashed at launch, and the crash-fix pass that should cure it was applied as text edits and **never compiled**. No new product code before X-09.

## Tasks (from `docs/PROGRESS.md`)
- **X-17** Crash-fix wrap-up: Checkpoint A then B of `CRASH_FIX_PLAN.md` — **only after the owner's explicit approval** to run Gradle [L]
- **X-18** (real device) The owner's phone launch test of the build from X-17 — the owner does it; you prepare the APK path, its sha256 and the exact steps [M]
- **X-07** Gradle wrapper, or the pinned Gradle documented in `scripts/tool-cache.sh` [S]
- **X-08** Verification workflow restored (`drafts/ci.workflow.yml` is the starting draft; the owner decides where builds run, D-22) [M]
- **X-09** Epoch V exit review [M]
- No-Gradle work that may start at once: **X-19** (`tests/test_architecture.py`) [M] and the SDK/simulator half of **X-12** [L]
- After X-17: the JVM halves of X-12 and X-13, then X-21, X-14, X-15, X-16, X-20. X-22 waits for the owner's decision D-23.

## Procedure
1. Orientation, always allowed: `python tools/progress.py --check`, `python tools/doc_check.py`, `python -m unittest discover -s tests -p "test_*.py"`. All must pass; if one fails, report its first failing line and stop.
2. Ask the owner, once: "May I run the Gradle build and JVM tests now? It uses most of this PC's RAM for 10–30+ minutes." No Gradle, JVM test run or emulator before an explicit yes.
3. While waiting: write **X-19** (stdlib Python; failing test first where a rule is already broken — record it, do not fix Kotlin) and the SDK/simulator half of **X-12** (failing test first in `sdk-python/tests`; fix; `python tools/sync_sdk.py`; both Python suites).
4. With approval, **X-17**: Checkpoint A (the Windows commands are in `HANDOFF.md` §0.4), then Checkpoint B in the order of `scripts/test-jvm.sh`. At most 10 fix attempts per checkpoint; smallest fix; the cheat sheet first; a failure in a file the crash-fix pass did not touch is logged `(PRE-EXISTING)`, not redesigned. Record the APK sha256 in `CRASH_FIX_PLAN.md` §1.5 and in `worklog.md` Session 20. Then tell the owner: "READY FOR OWNER PHONE TEST".
5. After the owner's phone result: X-07, X-08, every `(FAILING)` JVM task in `docs/PROGRESS.md`, the Python suites, the ladder retag with evidence, then X-09.

## How to prove it
- Every tick carries evidence on the task line, written the moment the result exists: command, exit status, key numbers, date, environment (`device: <model>, Android <n> / API <n>` for device items). A failing result keeps the box unticked and is tagged `(FAILING: reason)`.
- After every batch run `python tools/progress.py`, `python tools/progress.py --check` and `python tools/doc_check.py`.
- Report to the owner after every batch: *Done* (ids + evidence) · *Blocked* (id + reason) · *Needs you* (one question) · *Files touched*.

## Do not
- Do not start Gradle, JVM tests or an emulator without the owner's explicit approval (ADR-101).
- Do not start Phase 12+ code before X-09 is ticked; do not invent scope; do not tick from reading code.
- Do not redesign a failing component: fix the smallest thing, or tag it and move on.
- Do not use `archive/` or the stale `AutoHost-main.zip` at the repository root; do not copy code from `reference/`.
