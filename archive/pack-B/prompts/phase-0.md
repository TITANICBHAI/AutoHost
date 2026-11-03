# Phase 0 — Feasibility, repository audit and foundation proof (kickoff prompt)

Read `AGENTS.md`, `HANDOFF.md`, `plan.md` (§2.1, §3, §5.8–§5.9, §18 Phase 0, §23), `docs/SPIKES.md`, `docs/DEVICES.md`, `docs/REUSE_MATRIX.md`, `docs/MIGRATION_NOTES.md`, `docs/GATES.md` (Gate 0), `docs/DECISIONS.md` (ADR-087 to ADR-096, D-15 to D-20).

## Goal
Test the riskiest assumptions on the real phone and on a real toolchain, and decide — with measurements — which ARIA and ClosePaw pieces AutoHost uses. Spike code is throwaway under `spikes/`; the audit and verdicts are the output.

## Tasks (`docs/PROGRESS.md`, Phase 0)
Open: P0-01, P0-03 … P0-32 (spikes S1–S5, devices, decisions), P0-33 toolchain, P0-40 licence blockers, P0-41 … P0-44 ARIA foundation proof (S6), P0-45 … P0-47 agent feasibility (S7), P0-48 cost baselines (S8), P0-49 owner decisions. Already done and evidenced: P0-34 … P0-39 and G0-e.

## Constraints
- Do not integrate any staged file: ARIA rows are locked until D-19 is CONFIRMED, and adapting anything is Phase 1 work.
- Build ARIA only in a scratch copy of `reference/aria/`; never add it to `settings.gradle.kts`.
- Record measured numbers, not impressions; a stub inference path never counts (assert `jniAvailable`).
- Writing and compiling Phase 1 code in parallel is allowed (ADR-086); ticking it is not until Gate 0.

## Done when
Gate 0 items `G0-a … G0-h` are ticked with evidence (state which are *real device*); `python3 tools/progress.py --check` and `python3 tools/check_docs.py` pass; report deviations.
