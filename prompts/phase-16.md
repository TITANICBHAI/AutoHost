# Phase 16 — Optional local inference (kickoff prompt, v4)

Read `AGENTS.md`, `plan.md` (§18 Phase 16), `docs/LOCAL_INFERENCE.md`, `docs/SPIKES.md` (S6), `docs/REUSE_MATRIX.md` (§E), `docs/MODEL_GATEWAY.md`, `docs/GATES.md` (Gate 16).

## Goal
Spike S6 first; if it passes: an isolated `:llm` process, a verified model-artifact pipeline, a LiteRT-LM provider behind a `LocalModel` seam, a selection policy that keeps local models to READ_ONLY/REVERSIBLE work, licence acceptance UI, a latency profile; a clean-room llama.cpp fallback only if LiteRT-LM fails. Phase 16 can be dropped without affecting Phases 12–15. Details: `docs/LOCAL_INFERENCE.md`, ADR-092.

## Tasks (from `docs/PROGRESS.md` Phase 16)
- **P16-01** S6 harness and test plan (structure adapted from RM-E8) [M]
- **P16-02** Isolated `:llm` process: bound-service contract, load/unload/cancel, no Python and no broker access [L]
- **P16-03** Model artifact pipeline (adapt RM-E1/E2): pinned revision, size + SHA-256 before native load, resumable download into `noBackupFilesDir` [L]
- **P16-04** `LiteRtLmProvider` behind the `LocalModel` seam with a fail-closed `PromptBudget` (adapt RM-E3) [L]
- **P16-05** Selection policy: local models only for READ_ONLY/REVERSIBLE work; RAM gate from AVAILABLE memory; thermal-status listener [M]
- **P16-06** Clean-room llama.cpp JNI fallback — only if LiteRT-LM fails S6 [L]
- **P16-07** Model licence acceptance UI; "no publisher provenance" label for local imports [S]
- **P16-08** Latency profile (p95 of the last ≤ 20 samples per device+model) and the budget-exceeded notice [S]

## Constraints
- Spike S6 comes first; local inference is **NO-GO for agent loops** until S6 passes on both owner phones (ADR-092). Isolated `:llm` process; weights are never bundled; no model licence terms are asserted by the app or the docs.
- Phase 16 can be dropped without affecting Phases 12–15; a recorded NO-GO is a valid outcome.
- **Start only after X-09** (Epoch V exit review, ADR-097); Python-only and tooling items may start earlier.
- Tag and tick a tracker item only at the level it really proved (`(WRITTEN)` → `(COMPILED)` → `(UNIT-TESTED)` → `(INTEGRATION-TESTED)` → ticked); `(real device)` items need a `device: <model>, Android <n> / API <n>` evidence clause; emulator and simulator runs are never real-device (ADR-087).
- Predicted defects get a failing test first; if one does not reproduce, record that and keep the test (ADR-089).
- Carry forward `AGENTS.md` §3–§4: fail closed, no app-specific code outside `samples/` and `testing/`, `SDK_INT` only in `ApiGate`/`availability()`, one broker path, secrets only in `SecretStore`, no hand edits to generated files.
- Adapted code follows `docs/REUSE_MATRIX.md` §0 (header, notice row, ported test, ladder evidence); only ClosePaw and opendroid files are eligible.

## Done when
Gate 16 items G16-a … G16-d are ticked with evidence (*real device*: G16-a, G16-b, G16-c, G16-d); `python3 tools/progress.py --check` and `python3 tools/doc_check.py` pass; deviations are reported in `docs/DECISIONS.md`.
