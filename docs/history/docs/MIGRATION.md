# MIGRATION — what the v4 planning pass changed against the owner's tree

> **Later change:** on 2026-11-03 a merge pass folded this pack and the second planning pack into the owner's crash-fixed tree; see `docs/PLAN_MERGE.md`. This file stays the ledger of the 2026-11-02 pass only.

*Written 2026-11-02 by the planning agent. Companion: `docs/VALIDATION_REPORT.md` (what was checked, what was not). Read `HANDOFF.md` (v4 head) first; this file is the change ledger.*

## 1. Base, scope, and what did not change

- **Base tree:** `4e5a27cb-AutoHost-main.zip` (the owner's latest code, attached 2026-11-01). It supersedes the earlier `47fa839c-autohost.zip`; nothing was taken from the older archive.
- **Plan version:** v3 → **v4** (`plan.md`).
- **Application code is untouched.** Per-directory `diff -rq` against the delivered tree found **0 differences** in `app/`, `core/`, `runtime/`, `capabilities/`, `plugin-sdk/`, `sdk-python/`, `samples/`, `schemas/`, `testing/`, `gradle/`, `spikes/`, `config/`, `.github/`, and in `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `LICENSE`, `.editorconfig`. The generated golden vectors (`testing/golden/flow_vectors.json`, `schedule_vectors.json`) were regenerated and are byte-identical to the attached ones.
- **Nothing was deleted.** No file in the delivered tree is missing from this one.
- **Re-check it yourself:** `diff -rq <delivered-tree> <this-tree> -x __pycache__ -x .pytest_cache -x '*.pyc'` must list exactly the files in §2 and nothing under the directories above.
- **`reference/` is not on any source set.** It sits outside every `include(...)` in `settings.gradle.kts` and is referenced by no module, package or test.

## 2. Files changed

**Modified (29)**

| Area | Files |
|---|---|
| Root | `plan.md` (v4), `AGENTS.md`, `HANDOFF.md`, `KICKOFF.md`, `README.md`, `THIRD_PARTY_NOTICES.md` |
| `docs/` (16) | `ANDROID_MANIFEST.md`, `CONVENTIONS.md`, `DECISIONS.md`, `DEVAPI.md`, `DEVICES.md`, `FIXTURE_APP.md`, `GATES.md`, `PERF.md`, `PROGRESS.md`, `README.md`, `REFERENCES.md`, `REPO_LAYOUT.md`, `SPIKES.md`, `UI_SPEC.md`, `VERSIONS.md`, `progress.html` (regenerated) |
| `prompts/`, `verify/` | `prompts/README.md`, `verify/AGENTS.md`, `verify/AGENT_PRE_PROMPT.md`, `verify/KICKOFF.md` |
| Tooling | `tools/progress.py`, `tests/test_progress.py`, `scripts/test-python.sh` |

**New (64)**

| Area | Files |
|---|---|
| Design docs (8) | `docs/REUSE_MATRIX.md`, `docs/ACTION_HARDENING.md`, `docs/AGENT_RUNTIME.md`, `docs/MODEL_GATEWAY.md`, `docs/LANGUAGE_WEIGHT.md`, `docs/LOCAL_INFERENCE.md`, `docs/MIGRATION.md`, `docs/VALIDATION_REPORT.md` |
| Phase prompts (5) | `prompts/phase-12.md` … `prompts/phase-16.md` (generated from the tracker) |
| Tooling (2) | `tools/doc_check.py`, `tests/test_doc_check.py` |
| Drafts (2) | `drafts/agent.schema.draft.json`, `drafts/README.md` — a proposal for the Stage-B `agent{}` manifest block; **not** in `schemas/`, so the owner's schema set and `tools/gen_artifacts.py --check` stay untouched until P13-18 promotes it |
| `reference/` (47) | `INDEX.md` (every file, licence status, size, sha256), `README.md`, `SNAPSHOT.md`; `findings/` (6 notes); `manifests/` (3 path/size/hash lists for the projects with no licence); `closepaw/` (26 verbatim Apache-2.0 files incl. `LICENSE` and `NOTICE`); `opendroid/` (9 verbatim Apache-2.0 files incl. `LICENSE`) |

## 3. Where each part of the owner's brief landed

| Brief | Where |
|---|---|
| §1 AutoHost = source of truth; ARIA = candidate; ClosePaw = reference; no blind merge | `plan.md` §2 (five reference rows), `docs/REFERENCES.md`, ADR-088 |
| §2 read real source; exact paths, deps, licences, Android versions | `reference/findings/`, `docs/REUSE_MATRIX.md` (80 rows; "Evidence required" and licence/API columns) |
| §3 Kotlin owns the platform; Python first-class for user scripts; no arbitrary percentage targets | `plan.md` §4 pillars 14–15 and §7.11, `docs/LANGUAGE_WEIGHT.md`, ADR-086, ADR-093 |
| §4 reuse matrix | `docs/REUSE_MATRIX.md` (Component · source · disposition · destination · evidence) |
| §5 milestones, validate each, real-device tracked separately | `plan.md` §18 (milestone mapping, Epoch V, Phases 12–16), `docs/GATES.md` |
| §6 single tracker, generated counts, `--check` passes | `docs/PROGRESS.md`, `tools/progress.py` |
| §7 consistent supporting docs | the 29 modified files above, enforced by `tools/doc_check.py` |
| §8 ZIP keeps source, build config, tooling, licences; snapshots separate; migration notes | this file, `reference/`, `THIRD_PARTY_NOTICES.md` |
| §9 deliverables | `plan.md`, `docs/REUSE_MATRIX.md`, `docs/PROGRESS.md`, supporting docs, `docs/VALIDATION_REPORT.md`, the ZIP |

**Disposition of the reference projects** (static reading only): ClosePaw and opendroid — Apache-2.0, files may be adapted (13 `adapt` rows: RM-A1, A2, A3, A4, B1, B2, C2, D1, E1, E2, E3, E8, G2); android-agent, sannabotapp — no usable licence, ideas only; ARIA — no licence, owner unverified, treated as an anti-reference (its own docs overstate what its code does).

## 4. Tooling changes

- **`tools/progress.py` + `tests/test_progress.py`:** one verification-ladder state tag per task — `(WRITTEN)` < `(COMPILED)` < `(UNIT-TESTED)` < `(INTEGRATION-TESTED)` < ticked (ADR-087); evidence required from COMPILED upward; `(FAILING: reason)` never ticked; a real-device tick needs `(real device)` plus `device: <model>, Android <n> / API <n>` evidence, and emulator/simulator evidence is rejected; dashboard gained Compiled/Tested columns and a real-device count; a size-marker parsing fix (last marker wins).
- **Dashboard totals, so the jump is not a surprise:** the owner's tool on the owner's tracker printed **2/723** weighted points. The fixed tool on the *same unchanged* tracker prints **2/726** (re-run in a scratch copy for this note). After the new Phases 12–16 and X-07…X-16 were added it prints **2/914**.
- **`scripts/test-python.sh`** now also runs `python3 tools/doc_check.py`.
- **`tools/doc_check.py`** (new): checks that the documents agree on names — referenced doc/tool/prompt paths exist, `GATES.md#…` anchors have headings, task/gate ids exist in the tracker, ADR ids exist in `docs/DECISIONS.md`, RM ids exist in the matrix, every `docs/*.md` is indexed. It checks *references*, not truth. Deliberate exceptions live in its `KNOWN_MISSING` table, each with a reason.

## 5. Tracker changes

- **Added:** Epoch V (X-07…X-09), Phases 12–16 (P12-01…P16-08), cross-cutting X-10…X-16, gates G12-a…G16-d (24 items), spikes S6 and S7, ADR-086…098 and decision-log entries D-15…D-19.
- **Retagged, not ticked:** P1-09, P2-24 and P5-14 were `(FAILING: test environment lacks PyYAML)`. They were re-run here with PyYAML present (415 SDK tests OK, 75 tool tests OK, 3 MCP-eval scenarios PASS), so they now read `(UNIT-TESTED)` with that evidence — still unticked, because the owner's own environment has not run them.
- **Deliberately not retagged:** P11-01 — its tests cover code that includes uncompiled app glue.
- **Reworded:** the X-09 title (it contained a literal failing tag the parser read as a real one).
- **Still `(FAILING)` — the owner's own 2026-10-26 JVM results, not reproduced here:** P3-01, P3-23, P4-06, P4-16, P5-07, P7-01, P7-11, P11-02.

## 6. Statements corrected (stale or contradicted by the tree)

- `KICKOFF.md`, `verify/KICKOFF.md`: the "1/213" progress figure replaced; the `GATE_SKIPPED` landmine marked resolved (ADR-084).
- `AGENTS.md` rule 10 and `docs/DEVICES.md`: real-device gates no longer say "the primary Android 10 phone"; they point at the owner's two listed phones (ADR-095) until an Android 10 phone is confirmed.
- `HANDOFF.md` and `verify/AGENTS.md`: the sentence "no further code phases in plan.md" is **left in place and marked superseded** by a v4 block, because Phases 12–16 now exist as planned, optional work.
- `docs/LANGUAGE_WEIGHT.md`, ADR-093: Kotlin/Python line counts recounted against the delivered tree (Kotlin 466 files / 60,323 lines; Python 160 files / 27,837 lines) after the first draft quoted older figures.
- Left as the owner wrote them: `docs/PERF.md` ("recorded by the owner on the primary Android 10 phone" is the owner's own measurement note) and the Android 10 risk rows in `plan.md` (a platform-floor note at the top of `plan.md` says how to read them).

## 7. Drift that was already in the owner's tree — found, not fixed

| What | Evidence | Where it is recorded |
|---|---|---|
| `scripts/spake2_ref.py`, `schemas/capabilities.json`, `docs/SDK.md` are named by the docs but absent from the tree | `ls` of each path | `tools/doc_check.py` `KNOWN_MISSING` |
| Docs say the manifest uses `network_security_config.xml` / `usesCleartextTraffic`; the tree has neither (`app/src/main/res/xml/` holds `backup_rules.xml`, `data_extraction_rules.xml`, `file_paths.xml`, `task_runs_widget_info.xml`) | grep + `ls` | `docs/ANDROID_MANIFEST.md` §8 (task X-14), ADR-096 |
| `.github/workflows/ci.yml` is absent; only `android-debug-apk.yml` is tracked | `ls .github/workflows` | ADR-085 (the owner's) |
| Primary device: older text says Android 10; the owner's note lists Realme C3 (Android 11) and Galaxy M31 (Android 12) | `docs/DEVICES.md` | ADR-095, `docs/PROGRESS.md` open question 0(a) |
| Gradle wrapper (`gradlew`, `gradle-wrapper.jar`) is not in the tree | `ls` | ADR-096, `docs/VALIDATION_REPORT.md` §3 |

The static findings about the action path (A1–A9, dev-mode grant-all, unscoped `device.app`, wall-clock timeout only in the JS runtime, `WAITING_USER` semantics) are **predictions from reading**, not test results; they live in `docs/ACTION_HARDENING.md` and `reference/findings/autohost_user.md` and become tasks with a failing test first.

## 8. Rules for anything adopted from `reference/` later

Only ClosePaw and opendroid files may be adapted, and only the rows marked `adapt` in `docs/REUSE_MATRIX.md`. Each adaptation carries: the upstream file's copyright header and a statement of change, a row in `THIRD_PARTY_NOTICES.md`, the upstream `NOTICE` text where one exists, a ported test, and ladder evidence. `docs/REUSE_MATRIX.md` §0 is the checklist. No file has been adapted yet, so no upstream code is in any compiled module.

## 9. Using this pack

1. Unzip over your repository. Application code is identical, so the only possible conflicts are the 29 modified documents/tools if you edited them after 2026-11-01 — merge those by hand.
2. Read `HANDOFF.md` (v4 head), then `docs/PROGRESS.md` **Epoch V** (X-07…X-09). Phase 12+ code starts only after X-09 (ADR-097).
3. Answer the four open questions in `docs/PROGRESS.md` ("Open questions for the user", item 0): verification phones, ARIA's owner and licence, model provider and budgets, whether Phase 16 stays.
4. After any document or tracker edit run `python3 tools/doc_check.py`, `python3 tools/progress.py --check` and `bash scripts/test-python.sh`.
