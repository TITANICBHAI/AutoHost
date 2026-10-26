# KICKOFF — paste this to the verification agent alongside the repo

You're taking over **AutoHost**, a Kotlin Android automation app. `AGENTS.md` sits at the repo root — read it first, then follow its reading order exactly: `HANDOFF.md` → `docs/PROGRESS.md` → `worklog.md` → `docs/GATES.md` → `docs/CONVENTIONS.md` + `docs/REPO_LAYOUT.md`. The tracker files are the only source of state; trust them, not any summary — including this message.

State in one line: **all 11 phases are written** (sessions 1–19 in `worklog.md`), almost nothing is verified — 1/213 tasks ticked, and every JVM test has never been executed.

Your job is the verification epoch (`HANDOFF.md` §5 has the exact order):
1. Gradle first build + the per-module JVM test sweep (§6 lists the likely first-build failures — fix, don't redesign).
2. Run every verification you can locally: JVM tests, both Python suites (~415 + ~41), drift checks, licence check, MCP evals.
3. The device gates (1–11) need my phone — leave them unticked with `PENDING-DEVICE:` and the exact steps written out.

**The tick rule:** the moment a verification ends — pass or fail — document it on the task line in `docs/PROGRESS.md` (command, exit status, key numbers, artifacts, environment). Pass → tick the box. Fail → keep unticked, tag `(FAILING: reason)`, then fix-and-rerun or move to the next independent verification. Regenerate the dashboard and run `python tools/progress.py --check` after every batch. Never batch documentation at the end of a session.

Environment: gradle, Android SDK, JDK, Kotlin, Python. The JS sim tests also want `node` — if it's missing, install it or mark those tests `(BLOCKED: no node)` per the tick rule. Verify locally; GitHub Actions is only a mirror (X-01 / Gate 11-e per plan) — never push just to "see if it works". You have a PAT for this repo: small commits (`P9-05: summary`), push after each verified milestone, never force-push — I push on my own too.

Known landmine: `progress.py --check` throws `GATE_SKIPPED` if you tick P1+ tasks while earlier device gates are open. Resolve it once the `AGENTS.md` §3 way (ADR-084 + a small `tools/progress.py` adjustment with a test), then tick freely.

Start now: read `AGENTS.md`, then `HANDOFF.md` §5, and begin the first build. Ask me only for physical phone actions, device facts, or scope/licence/security decisions.
