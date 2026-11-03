# AGENT_STATE — AutoHost

> Living continuity note for the implementing agent. This is not the task tracker. `docs/PROGRESS.md`, `HANDOFF.md` and `docs/DECISIONS.md` remain authoritative. The operating procedure (read order, batching, Git/CI rules) is `AGENT_OPERATING_PROTOCOL.md` at the repo root — read its **§10 every-time checklist** before each step.

## Updated
- Date/time: 2026-11-03 ~21:00 IST
- Current branch: `main` (tracks `origin/main`)
- Latest local commit: `e552365` (pushed)
- Latest pushed commit: `e552365` on `origin/main`
- Latest GitHub Actions run: **run #29 for `e552365` — completed SUCCESS** (APK build; runs no tests) https://github.com/TITANICBHAI/AutoHost/actions/runs/41624267873 — the crash-fixed tree compiles and assembles. Run #28 (`577c40e`) also green.

## Current position
- Phase / epoch: **Epoch V** (verify what exists); Phase 12+ blocked until X-09 (ADR-097)
- Active task(s): none in progress — batch complete, awaiting next queue item
- Current batch (pushed together): X-19 (ticked) + X-12 SDK/simulator half (row `(WIP)` unticked, evidence recorded) + HANDOFF hosted-CI queue edits
- Batch status: locally checked (progress/doc checks, both Python suites, sync/gen drift); pushed for CI

## Verified baseline
- `python tools/progress.py --check`: ok; dashboard 7/932 weighted, 5 ticks
- `python tools/doc_check.py`: ok (54 files)
- Root Python suite (`tests/`): **80 OK** (75 + 5 new architecture tests)
- SDK Python suite (`sdk-python`): 415 OK (not re-run yet this session; X-12 will re-run)
- Latest JVM/build evidence: run #29 green (APK build only — NO tests; X-17 test sequence open)
- Environment: Windows 10 PC (3.9 GB RAM); git 2.56.0.windows.3 on PATH (ZCode harness = Git Bash); remote `TITANICBHAI/AutoHost` (public — anonymous API works); `gh` NOT installed; local Gradle/JVM/emulator need owner approval (ADR-101); hosted CI approved (D-22). NOTE: the sdk-python suite has 14 PRE-EXISTING failures on this Windows machine (12 tzdata errors in test_ref_schedule, 1 file-scheme in test_p11_part2, 1 /tmp path separator in test_mcp_server) — Linux CI is the reference

## Decisions that affect current work
- D-22 decided 2026-11-03: GitHub Actions hosts builds/tests; never re-ask for hosted-runner approval (`docs/DECISIONS.md`)
- ADR-101 unchanged locally: no Gradle/JVM/emulator on this PC without explicit owner approval in the current conversation
- Active workflow `android-debug-apk.yml` builds the APK only — it proves no tests; X-17's test sequence needs X-08's promotion of `drafts/ci.workflow.yml` (documented on HANDOFF §0.2 item 4 and the X-17 row)
- X-19 ratchet: `tests/test_architecture.py` records 2 known violations (a11y containment: A11yBrowserLink.kt + AppGraph.kt; run_starts: AppGraph P5-05). `AUTOHOST_ARCH_STRICT=1` = red-team view. New violations fail; stale ratchet entries fail. Do NOT fix the Kotlin from X-19.

## Owner interaction protocol (2026-11-03, updated same day)
- Owner says **"continue"** → I proceed to the next queue task without re-asking.
- Owner says **"recheck"** → I re-verify the stage just completed (re-run checks, re-read touched files, confirm nothing was lost), then ask "shall I continue?" and wait.
- **READ ALL RELATED DOCS STRICTLY** before each plan part (task row + ADRs + gate + named docs + plan section).
- **BATCH MORE, PUSH LESS** — gather multiple fixes/tasks per push; verification runs once per batch.
- **RECHECK LOOP BEFORE EVERY COMMIT (owner, 2026-11-04):** re-verify everything touched (syntax per language, line-endings, YAML, replaced texts); if an error is found, fix it and REPEAT the whole recheck; loop until a round finds ZERO problems; only then commit.
- **EMULATOR CI JOB = NO-GO (D-24)** — removed from ci.yml; never re-add it without an explicit owner decision.
- Next work: X-17-style JVM test-compile fix loop via CI annotations (app/a11y first), batched.

## Owner interaction protocol (2026-11-03)

## Blockers / owner actions
- X-18 phone test, D-23 (blocks X-22), P0-01 device facts — owner-side
- Concurrency: RESOLVED — owner confirmed (2026-11-03) only this session is active; watch for the signs listed in protocol §10 anyway

## Next tasks — reread before each
1. **X-12 (SDK/simulator half, finishing now)** — row in `docs/PROGRESS.md`; ADR-099. Red tests first in `sdk-python/tests`: registered secret values must be refused/redacted on `call()` args, `kv`/`state` writes (KV.__setitem__ → `store.set`), checkpoints (`ctx.once` writes `rt.state`; `ctx.save()` sends state), and `emit` (already redacts); then fix `sdk-python/autohost/_core.py` (Runtime.call) + `_ctx.py`; `python tools/sync_sdk.py`; both suites
2. **X-17** — via GitHub Actions: promote `drafts/ci.workflow.yml` (X-08 scope) or extend the workflow so Checkpoint A1 (`:core:broker:test :capabilities:shell:test :core:engine:test`) + Checkpoint B (12-invocation JVM sweep) actually run; then record APK sha256 + results in `CRASH_FIX_PLAN.md` §1.3–1.5 and worklog Session 20's entry
3. **X-07** — Gradle wrapper or pinned-Gradle note (prerequisite for X-08 promotion)

## Recent batch
- Task IDs completed: X-19 (ticked); setup docs (D-22, protocols, HANDOFF queue)
- Files changed: `tests/test_architecture.py` (new), `docs/PROGRESS.md` (X-19 tick + X-17 build-half evidence), `HANDOFF.md` (§0.2 items 2/4/5), `AGENT_STATE.md`, root `AGENT_OPERATING_PROTOCOL.md` (§10 checklist), `worklog.md` (Session 22); earlier: `docs/DECISIONS.md`, `drafts/README.md`, `AGENTS.md`, `docs/README.md`, `docs/AGENT_OPERATING_PROTOCOL.md` (pointer)
- Commits created: `e552365` (pushed); the X-19/X-12 work is still uncommitted
- Push result: e552365 → origin/main, fast-forward, CI run #29 green
- Actions result and evidence: run #29 success (APK build only); recorded on the X-17 row
- Failures / fixes / lessons: see "Do not repeat"

## Do not repeat
- **Verify every edit persisted** (grep immediately): one PROGRESS.md tick silently did not persist on 2026-11-03
- **Fetch + status before every commit/push**; stage by name; fast-forward only
- **The git index was found emptied once** (869 staged deletions, HEAD intact) — recovery is plain `git reset`; diagnose with `git ls-files | wc -l` before touching anything
- **Session/environment metadata can be stale** ("is a git repository: false" was wrong) — check the filesystem
- **Read errors literally**: unittest diff output truncates lists — print the real list before concluding
- Do not recreate `docs/AGENT_OPERATING_PROTOCOL.md` as a full copy — it stays a pointer to the root canonical file
- Do not treat an APK-only Actions run as test evidence
