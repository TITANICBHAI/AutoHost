# Phase 12 — Agent runtime and model providers (kickoff prompt)

Read `AGENTS.md`, `HANDOFF.md`, `plan.md` (§5.8, §5.9, §7.11, §14.7, §18 Phase 12), `docs/REUSE_MATRIX.md` (R-10, R-11, R-13, R-14, R-17), `docs/GATES.md` (Gate 12), `docs/DECISIONS.md` (ADR-089, ADR-090, ADR-092, ADR-094 to ADR-096), `docs/SPIKES.md` (S7 verdict), `docs/FIXTURE_APP.md` (agent scenarios), `docs/UI_SPEC.md` (S21, S22).

## Goal
An LLM-driven run that observes the screen as compact text, proposes structured tool calls, has each one validated and executed through the broker, verifies the result and stops cleanly — inside host-owned budgets, with sensitive apps denied and nothing fabricated when no model is available.

## Tasks (`docs/PROGRESS.md`, Phase 12; may start once the gates of phases 0–5 are ticked)
P12-01 … P12-15 in dependency order: SPI, cloud provider, settings and budgets, `CAP-ai.model`, tool registry, agent runtime, sensitive-app policy, traces, context builder, run requests, hybrid use, overlay, evaluation harness, local provider (conditional), vision (optional).

## Constraints
- The agent is a `RuntimeProvider`: one run lifecycle, one broker, one action path. Phases are events, not run statuses.
- Preflight cancel and deadline before every blocking step; reserve budget before every side effect; account usage right after. Provider limits are advisory.
- Vendor SDK types stay inside `:ai:cloud`. No stub providers outside test source sets. HTTPS by default. No silent fallback provider.
- Screen text is untrusted. No raw chain-of-thought is stored or shown. The first slice is a host-internal run request; the public manifest type waits for Gate 12a–e and the raw-JSON guard (P2-27).
- Memory and skills are deferred until D-20 is decided.

## Done when
Gate 12 items `G12-a … G12-f` ticked with evidence (real device where marked); checks green; report deviations.
