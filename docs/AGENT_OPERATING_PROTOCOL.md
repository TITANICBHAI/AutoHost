# AGENT_OPERATING_PROTOCOL — permanent operating rules for implementing agents

*Created 2026-11-03 (first session under the repository-orientation instruction). These rules
operationalise `AGENTS.md` §3–§6 for this checkout as it actually is. Where `AGENTS.md` and this
file disagree, `AGENTS.md` wins; report the disagreement instead of improvising.*

## 1. Session start (fixed order)

1. `AGENTS.md` (whole file).
2. This file.
3. `AGENT_STATE.md` (repo root).
4. `HANDOFF.md` §0 (state + assignment queue; §0.2 is authoritative for task order while Epoch V is open).
5. The exact task row(s) in `docs/PROGRESS.md` — the rows are authoritative for completion state.
6. The gate (docs/GATES.md) and task-specific docs those rows name.
7. The relevant `plan.md` section and phase prompt, if one exists.
8. The last two `worklog.md` sessions only when recent history or an unresolved attempt matters.

Never read `archive/`, the stale `AutoHost-main.zip`, or anything under `reference/` unless a
`docs/REUSE_MATRIX.md` row names the file.

## 2. Git on this checkout

- **This folder is not a git repository** (verified 2026-11-03: no `.git`, no `git` binary on PATH).
  Per `CRASH_FIX_PLAN.md` §1.1b rule 5: skip commits, write `no git` in the log, never `git init`.
- If the owner later wires git or a remote: follow `docs/CONVENTIONS.md` § Git (commit `<task-id>:
  <summary>`, small commits), and push only with the owner's direction. Never force-push, amend or
  rewrite history.
- "One task per commit" therefore degrades to **one task per verified, self-contained batch** with
  its own tracker + worklog record. Do not batch two tasks into one edit sweep unless the queue
  names them together (as HANDOFF §0.2 item 3 does for X-19 + X-12).

## 3. GitHub Actions (D-22 decided 2026-11-03)

- The owner chose **GitHub Actions** for hosted builds/tests. Agents must not keep asking for
  approval to use hosted runners; ADR-101(b) still binds **local** Gradle/JVM/emulator runs on the
  owner's PC (explicit approval in the current conversation, every time).
- **Active workflow:** `.github/workflows/android-debug-apk.yml` — builds `app-core-debug.apk` +
  the fixture APK on every push and uploads them with logs. **It runs no tests.** Never claim test
  results from it; use it for X-17's build half (record the APK sha256 from `ci-logs/apk-sha256.txt`
  in the artifact).
- **Draft workflow:** `drafts/ci.workflow.yml` (Python suites, doc/drift checks, JVM tests, debug
  assemblies) is **not active**. X-08 promotes it to `.github/workflows/ci.yml`; its remaining
  precondition is X-07 (Gradle wrapper or a pinned-Gradle note). A promoted test workflow is the
  evidence source for the JVM sweep — read failing steps from the run page, fix the smallest thing
  (≤10 attempts per checkpoint), never redesign.
- Getting the tree onto the remote is an owner-side step while this checkout has no git — ask, do
  not improvise a push path.

## 4. Task batching and stop conditions

- Take tasks strictly from `HANDOFF.md` §0.2 while Epoch V is open; afterwards the lowest-ID
  unticked, unblocked independent item in `docs/PROGRESS.md` (AGENTS §5).
- **Failing test first** for every predicted defect (ADR-089): write the red test, run it, then fix;
  if it does not reproduce, record "does not reproduce on <env>", keep the test, skip the fix.
- No-approval work (always allowed): text checks, Python tools, Python tests, new/edited tests and
  docs, Kotlin **text edits** that a task names. Never: Gradle, JVM test runs, emulators, phone
  actions without the owner (AGENTS rule 16 / ADR-101).
- Stop and report when (AGENTS rule 18 / HANDOFF §0.3): an exact-match edit hits 0 or 2+ sites; the
  same command fails twice after a fix; two docs disagree and `plan.md` does not settle it; a step
  needs an unnamed file; a step needs the phone. Also STOP on any task whose row says BLOCKED or
  needs X-09 before it may start.

## 5. Documentation navigation and precedence

- `plan.md` defines behaviour; `docs/*` define file-level structure. On conflict: record in
  `docs/DECISIONS.md`, fix whichever is wrong, continue (AGENTS §2).
- The full doc map lives in `AGENTS.md` §2. Generated artifacts (`docs/CAPABILITIES.md`,
  `docs/SDK.md`, `sdk-python/**/_generated`, the broker resource copy, golden vectors) are never
  hand-edited — run the generator and commit its output.
- Any doc edit must pass `python tools/doc_check.py` before being called done; tracker edits must
  pass `python tools/progress.py --check`.

## 6. Evidence rules (ADR-087 ladder)

- At most ONE state tag per tracker item: `(WRITTEN)` < `(COMPILED)` < `(UNIT-TESTED)` <
  `(INTEGRATION-TESTED)`; ticked `[x]` = verified. `(FAILING: reason)` is never ticked.
- A tick needs an `evidence:` line: command, exit status, key numbers, date, environment. Simulator
  and emulator runs never satisfy a *(real device)* item — those need
  `device: <model>, Android <n> / API <n>`.
- Never tick from reading code. Fill the evidence line the moment the result exists, then run
  `python tools/progress.py` and `--check`.
- Cite CI evidence by run URL + run number, and only for what that workflow actually ran
  (see §3).

## 7. Handoff and working memory

- Keep `AGENT_STATE.md` compact and current: current task, last verified state, next steps, open
  blockers, and pointers to authoritative files — it must not copy their contents.
- Append one `worklog.md` session entry per work session (next session number), with scope,
  verification (real commands and results), and open items — in the established format.
- At a phase boundary, update `AGENT_STATE.md` with the new phase, its entry conditions and its
  first task, and read that phase's `prompts/*.md`.
- Report to the owner after every batch in HANDOFF §0.3's format: *Done* / *Not done or blocked* /
  *Needs the owner* (one question at most) / *Files touched*.
