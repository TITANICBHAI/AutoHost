# PLAN_MERGE — how the two planning packs were merged (2026-11-03)

*Written by the planning agent. **Planning only: no application code, build file, manifest or schema was changed** (checked below). This is a historical record; the live instructions are `HANDOFF.md` §0 and `AGENTS.md`.*

## 1. Inputs
| Input | What it is | Role |
|---|---|---|
| `AutoHost-main.zip` | the owner's repository: all code, the crash-fix pass of Session 20 (`CRASH_FIX_PLAN.md`, never compiled), the tracker with P1-09 and X-04 ticked | **newest on code** — kept unchanged |
| `AutoHost-main_plan.zip` (pack A) | a v4 planning pack built on the owner's code of 2026-11-01 | **base of the merged plan** |
| `autohost-planning-pack.zip` (pack B) | a second v4 planning pack derived from the owner's code of 2026-10-27, with staged ARIA/ClosePaw sources and complete reference snapshots | donor of selected content; preserved verbatim in `archive/pack-B/` |

Common ancestor of the documents: the owner's docs of 2026-10-27. The owner's docs in `AutoHost-main.zip` equal that ancestor except for `docs/PROGRESS.md`, `worklog.md` and the new crash-fix files, so pack A's documents could be applied with a three-way merge (`git merge-file`, base = pack B's saved copy of the ancestor) and produced two textual conflicts only (the dashboard block, which is regenerated, and the P1-09 line).

## 2. Verdict: pack A is superior and is the base
1. **Licence discipline.** Pack A snapshots only Apache-2.0 files whose project carries a LICENSE (ClosePaw, opendroid) and locks ARIA, android-agent and sannabotapp as ideas-only (D-17, ADR-088). Pack B staged verbatim ARIA files — which have no LICENSE — into module folders for later adaptation.
2. **Gating and honesty.** Pack A puts *verify what exists* (Epoch V, ADR-097) in front of new code, tags every static finding as a prediction until a failing test reproduces it (ADR-089), and ships `docs/VALIDATION_REPORT.md` listing what was and was not checked. Pack B plans on top of code that has never been built.
3. **Tooling that checks the documents.** Pack A's `tools/doc_check.py` (paths, task/ADR/gate/RM ids, docs index) and ladder-aware `tools/progress.py`, each with tests, versus pack B's checker which mostly guards its own staged copies.
4. **Document integrity.** Pack B's `docs/DECISIONS.md` contains duplicated and contradictory ADR blocks, and its task and ADR numbers collide with pack A's; pack A's numbering is consistent with the owner's tracker.
5. **Scope control.** Pack A keeps the agent runtime optional, last and behind spikes (Phases 12–16, local inference NO-GO until S6); it adds the hardened-action findings A1–A9 that the agent would otherwise stand on.

**What pack B did better, and was ported:** the security-boundary rules, the architecture and idle-cost tests, the language-responsibility table, an agent-feasibility spike, two deferred-feature decisions, the ARIA and ClosePaw assessments, the observed-versions table, the "not used" list and a CI workflow.

## 3. Ported from pack B (all renumbered to this tracker's IDs)
| Content | Now |
|---|---|
| Security boundaries: the run token is a bearer capability; secrets are profile-scoped; the SDK and simulator never persist secrets; raw manifests fail closed | ADR-099; tasks X-12, X-13, X-21; `plan.md` §9.1 `x-secret` rule and the run-token bullet |
| Architecture rules enforced by tests; idle-cost guard; language responsibilities | ADR-100; tasks X-19, X-20; `plan.md` §5.8 |
| Agent feasibility spike before the agent loop is built | spike S8 (`docs/SPIKES.md`); task P13-20 |
| Script-versus-flow runtime cost comparison | task P15-09 |
| Cross-session agent memory and skills; MCP tools inside the agent | deferred decisions D-20, D-21 |
| Three risk rows; the stage table | `plan.md` risk table; `plan.md` §23 |
| ARIA audit, ClosePaw assessment, licence register | `reference/findings/aria_audit_packB.md`, `reference/findings/closepaw_assessment_packB.md`; pointer rows in `docs/REUSE_MATRIX.md` §J |
| Observed versions; "not used, by decision" list | `docs/VERSIONS.md`; `THIRD_PARTY_NOTICES.md` |
| CI workflow | `drafts/ci.workflow.yml` (task X-08; decision D-22) |

## 4. Taken from the owner's tree and folded into the plan
The crash-fix pass became **Epoch V step 0**: X-17 (Checkpoints A and B, only with the owner's explicit approval), X-18 (the owner's phone test) and X-22 (FileProvider scope, blocked on D-23); ADR-101 records the owner's build rules (no Gradle, JVM run or emulator without approval on a 3.9 GB Windows PC). X-09 now requires X-17 and X-18. The owner's ticks (P1-09, X-04) and `worklog.md` Session 20 are kept. Line citations to `AppGraph.kt` were refreshed against the crash-fixed file.

## 5. Left out of the live docs, and where it is
| Left out | Why | Where |
|---|---|---|
| Staged ARIA and ClosePaw sources, the component matrix R-01…R-29, the migration manifest | ARIA has no LICENSE (D-17); numbering collides; superseded by `docs/REUSE_MATRIX.md` | `archive/pack-B/` |
| Pack B's `HANDOFF.md`, `AGENTS.md`, `KICKOFF.md`, `PROGRESS`, `DECISIONS`, tools and tests | the merged versions replace them | `archive/pack-B/` |
| Complete ARIA and ClosePaw snapshots (25 MB) | unlicensed ARIA source must stay out of the live `reference/` (its rule 3) | `archive/pack-B/reference/` |
| Owner-run work: Gradle, JVM tests, the phone test | needs the owner's approval or phone | tasks X-17, X-18 |

## 6. Conflicts settled
- **Device baseline:** the two owner-listed phones (ADR-095); the older "primary Android 10 phone" wording is superseded.
- **ARIA:** locked (D-17), although pack B planned to adapt it.
- **X-19 is a Python test** (`tests/test_architecture.py`), not a JVM test, so it can run on the owner's PC while Gradle is still forbidden.
- **CI** is a draft in `drafts/`, not a live workflow: nothing is pushed on the owner's behalf.
- **Order of work:** the assignment queue in `HANDOFF.md` §0.2 overrides "lowest-ID first" until X-09.

## 7. Checks run in this pass
- `python tools/progress.py --check` → `progress check: ok` (dashboard after the merge: tasks 4/293, gate items 0/94, verified 1% — weighted 5/932 points).
- `python tools/doc_check.py` → `doc_check: ok (53 files)`.
- `python -m unittest discover -s tests -p "test_*.py"` → 75 tests OK; the `sdk-python` suite (`cd sdk-python && python -m unittest discover -s tests -t tests -p "test_*.py"`) → 415 tests OK.
- `tools/gen_artifacts.py --check` and `tools/sync_sdk.py --check` → in sync; `tools/licence_check.py` → ok (25 catalog dependencies); `tools/mcp_eval.py` → PASS ×3.
- `diff -rq` of the merged tree against the owner's original `AutoHost-main` (ignoring caches): 50 entries — documents, prompts, the tracker tools and their tests, one added line in `scripts/test-python.sh` (it now runs `tools/doc_check.py`), and the new folders `archive/`, `drafts/`, `reference/`. **No difference** under `app/`, `core/`, `capabilities/`, `runtime/`, `plugin-sdk/`, `sdk-python/`, `samples/`, `schemas/`, `testing/`, `spikes/`, `gradle/`, `config/`; no build file, manifest or schema changed.
- The 26 ClosePaw files in `reference/` were compared byte for byte with pack B's copy: identical.
- Environment: Linux sandbox, Python 3.13; no JDK, Gradle or Android SDK; nothing was compiled.

## 8. Not checked
Nothing was compiled or run beyond the Python tools and tests listed above: no Gradle, no Kotlin compile, no emulator, no phone. Every statement about Kotlin behaviour in the plan documents remains a prediction until a test reproduces it. "Pack A is better" is a planning judgement, not a measurement.
