# Phase 15 — Footprint: measure first (kickoff prompt, v4)

Read `AGENTS.md`, `plan.md` (§18 Phase 15), `docs/LANGUAGE_WEIGHT.md`, `docs/PERF.md` (§5), `docs/VERSIONS.md` (v4 notes), `docs/DEVICES.md`, `docs/GATES.md` (Gate 15).

## Goal
A measurement harness (APK size per flavor/ABI, cold start, resident memory, worker spawn), a Python-optional audit, flavor dimension `runtime` = `py` | `lite`, an ABI policy, a 16 KB page-size audit, worker-pool tuning from measured RAM, R8 review, an accessibility event-mask audit. No percentage targets. Details: `docs/LANGUAGE_WEIGHT.md`, ADR-093.

## Tasks (from `docs/PROGRESS.md` Phase 15)
- **P15-01** Measurement harness: APK size per flavor/ABI, cold start, resident memory, worker spawn time (both phones) [M]
- **P15-02** Python-optional audit: every code path that assumes the `python` runtime is registered (CompatReport, UI entry points, samples) [S]
- **P15-03** Flavor dimension `runtime` = `py` | `lite` (`lite` omits `:runtime:python` and the Chaquopy plugin) [L]
- **P15-04** ABI policy: release arm64-v8a only, debug adds x86_64; documented and enforced [S]
- **P15-05** 16 KB page-size audit of every shipped native library [S]
- **P15-06** Worker-pool tuning from measured RAM (adaptive worker count) [M]
- **P15-07** R8/shrink review with keep rules; size delta measured [S]
- **P15-08** Accessibility event-mask and `notificationTimeout` audit (RM-A8) with measured CPU effect [S]

## Constraints
- No percentage targets; no performance or size claim without a measurement recorded in `docs/PERF.md` with a device clause (ADR-093).
- `lite` must keep flow, JS and agent runs working and give Python tasks a typed CompatReport row.
- **Start only after X-09** (Epoch V exit review, ADR-097); Python-only and tooling items may start earlier.
- Tag and tick a tracker item only at the level it really proved (`(WRITTEN)` → `(COMPILED)` → `(UNIT-TESTED)` → `(INTEGRATION-TESTED)` → ticked); `(real device)` items need a `device: <model>, Android <n> / API <n>` evidence clause; emulator and simulator runs are never real-device (ADR-087).
- Predicted defects get a failing test first; if one does not reproduce, record that and keep the test (ADR-089).
- Carry forward `AGENTS.md` §3–§4: fail closed, no app-specific code outside `samples/` and `testing/`, `SDK_INT` only in `ApiGate`/`availability()`, one broker path, secrets only in `SecretStore`, no hand edits to generated files.
- Adapted code follows `docs/REUSE_MATRIX.md` §0 (header, notice row, ported test, ladder evidence); only ClosePaw and opendroid files are eligible.

## Done when
Gate 15 items G15-a … G15-d are ticked with evidence (*real device*: G15-a, G15-d); `python3 tools/progress.py --check` and `python3 tools/doc_check.py` pass; deviations are reported in `docs/DECISIONS.md`.
