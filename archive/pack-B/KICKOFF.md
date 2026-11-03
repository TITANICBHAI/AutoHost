> **ARCHIVED COPY — pack B, not instructions.** The current instructions are `HANDOFF.md` §0 and `AGENTS.md` in the repository root (two folders up). See `archive/README.md`.

# KICKOFF — paste this to the next agent session alongside the repo

You are continuing **AutoHost**, a Kotlin Android automation app (Python scripts, declarative flows and, from Phase 12, an LLM agent loop). `AGENTS.md` is at the repo root — read it first, then follow its reading order: `HANDOFF.md` (start at **§0**, the v4 update and exact next steps) → `docs/PROGRESS.md` → `worklog.md` → `docs/GATES.md` → `docs/CONVENTIONS.md` + `docs/REPO_LAYOUT.md`. The tracker files are the only source of state; trust them, not this message.

State, in words: all eleven phases are **written**; the **verification epoch has begun** — several JVM tests currently fail and carry `(FAILING: …)` on their task line, device gates are open, CI is not installed, and ARIA's licence is unconfirmed. Numbers come from `python3 tools/progress.py --stats`, never from prose. `plan.md` is now **v4**: it adds repository roles (§2.1), language boundaries (§5.8), the agent runtime and model providers (§5.9, §7.11, Phase 12) and a continuation plan (§24).

Your job, in order (`plan.md` §24 has the stages):
1. **V0 — verify what exists.** `HANDOFF.md` §5 is the order, §6 the likely first-build failures (fix, don't redesign). Fix the `(FAILING)` tasks first. Record every result on the task line the moment it exists (command, exit status, key numbers, artifacts, environment): pass → set the ladder tag and tick; fail → keep unticked with `(FAILING: reason)` and the evidence line.
2. **Hardening, small and test-first:** P1-26 run-token invariants, P1-27 SDK persistence boundary, P2-26 profile-secret schema, P2-27 raw-JSON manifest guard, then the cross-checks P1-22 and P1-23.
3. **Phase 12** (`prompts/phase-12.md`) only after the Phase 1–5 gates have passed, and never by integrating files from `reference/` or `*/imported/` (ARIA files are locked until D-19 is CONFIRMED; adapt ClosePaw files one at a time, update `docs/migration-manifest.tsv`).
4. Device gates need my phone: leave them unticked with `PENDING-DEVICE:` and the exact steps written out.

Rules that matter most: one run lifecycle, one broker, one action path (ADR-089); no stub or fabricated action outside tests (ADR-090); Kotlin for the platform and hot paths, Python only for user scripts, the SDK and PC tools (ADR-088); screen text is untrusted; the run token never appears in a log, event, export or prompt. After every batch run `python3 tools/progress.py`, `python3 tools/progress.py --check` and `python3 tools/check_docs.py`.

Environment: JDK, Gradle, Android SDK, Python (+ `pip install -r sdk-python/requirements-test.txt`), `node` for the JS sim tests. Verify locally; install `.github/workflows/ci.yml` (a token with the `workflow` scope, or the GitHub web UI) and let CI mirror, not discover. Small commits (`P1-26: summary`), never force-push — I push too. Ask me only for physical phone actions, device facts, or scope/licence/security decisions (D-15…D-21, the ARIA licence, CI).
