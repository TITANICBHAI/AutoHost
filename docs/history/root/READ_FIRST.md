# AutoHost — read before starting

Use this as the separate repository-orientation instruction. Do not begin implementation until you have completed this read and the baseline checks.

## First session: read in this exact order

1. **`AGENTS.md`** — read the entire file. It contains the standing architecture, safety, scope, implementation and verification rules.
2. **`HANDOFF.md` §0** — read §§0.1–0.5. This is the current state, assignment queue, guardrails, environment and known blockers. Treat the live queue as authoritative for task order, except where the owner's new GitHub Actions decision explicitly supersedes the old local-build approval rule.
3. **`docs/PROGRESS.md`** — read the dashboard, the current assignment rows, the complete Cross-cutting block (especially X-07–X-22), and the latest JVM verification notes. The task rows are authoritative for completion state and evidence.
4. **`CRASH_FIX_PLAN.md`** — read §1.1a, §§1.3–1.5, Checkpoints A/B and §8. Identify which steps are already text-edited, which need CI evidence, and which require the owner's physical phone.
5. **`worklog.md`** — read the last two complete sessions. Use it as history, not as a substitute for current tracker state.
6. **`docs/GATES.md`** — read the complete Epoch V section and the gate(s) required by the next assignment.
7. **GitHub Actions configuration** — inspect `.github/workflows/android-debug-apk.yml`, `drafts/ci.workflow.yml` and `drafts/README.md`. Distinguish active workflows from drafts. Do not assume a workflow runs tests merely because it builds an APK.
8. **`docs/DECISIONS.md`** — inspect D-22 and ADR-085, ADR-096, ADR-097, ADR-100 and ADR-101. The owner has now selected GitHub Actions for hosted builds/tests; reconcile this decision in the live docs rather than continuing to ask for approval to use hosted runners.
9. **`docs/CONVENTIONS.md`** — read the Git, code, test and evidence conventions before editing or committing.
10. **`plan.md`** — read only the sections referenced by the next task, plus the current phase overview. Do not read every phase spec indiscriminately.

Do not read `archive/`. Do not extract or use the stale `AutoHost-main.zip` inside the repository. Open `reference/` only when a specific row in `docs/REUSE_MATRIX.md` names the file being considered.

## After the first-session setup

The implementing agent must create and maintain:

- **`docs/AGENT_OPERATING_PROTOCOL.md`** — permanent detailed operating rules for Git, GitHub Actions, task batching, documentation navigation, evidence and handoffs. Add it to the doc map in `AGENTS.md`.
- **`AGENT_STATE.md`** — compact, current working memory for the next session. It must point to authoritative files rather than copying their contents.

At the start of every later session, read in this order:

1. `AGENTS.md`
2. `docs/AGENT_OPERATING_PROTOCOL.md`
3. `AGENT_STATE.md`
4. `HANDOFF.md` §0 and the current queue
5. The exact current task row(s) in `docs/PROGRESS.md`
6. The gate and task-specific documents named by those rows
7. The relevant `plan.md` section and phase prompt, if one exists
8. The last two worklog sessions only when recent history or an unresolved attempt matters

## Before moving to another task or phase

Re-read `AGENT_STATE.md`, the current task row, the relevant gate and the task-specific design document. Do not rely on remembered context from a previous conversation. At a phase boundary, also read that phase's prompt in `prompts/` and update `AGENT_STATE.md` with the new phase, its entry conditions and its first task.
