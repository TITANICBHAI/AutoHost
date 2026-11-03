# PACK_MERGE — how to merge the v4 planning pack into your repository
The pack contains **only** documents, the tracker tooling, the `reference/` snapshots and the staged sources under `*/imported/`. It contains none of your application code or build files. Paths mirror the repository root. It was derived from `AutoHost-main.zip` (2026-10-27).

## 1. Merge
1. Commit or stash your work first.
2. Copy the pack's folders and files over the repository root.
3. Files that exist in both places are the modified ones listed in `docs/CHANGES_FROM_AUTOHOST_ZIP.md`; the pack keeps the version they were derived from in its own `_merge_base/` folder (not part of the repo). If one of them changed in your repository after that snapshot — most likely `docs/PROGRESS.md`, `HANDOFF.md`, `docs/DECISIONS.md`, `docs/GATES.md`, `AGENTS.md`, `worklog.md`-related notes, `tools/progress.py` — do not overwrite it. Run a three-way merge instead: `git merge-file <your file> _merge_base/<file> <pack file>`, then resolve any conflict markers by hand. That keeps your newer evidence and adds the v4 changes.
4. Apply the one change that is not shipped as a file. Append this comment block to `settings.gradle.kts` (it only documents the rule that `reference/` and `imported/` are never built):
```
// v4 (ADR-091, G1-i): NEVER include anything under reference/ or any imported/ directory. They hold pristine or staged
// ARIA/ClosePaw sources, not modules. tools/check_docs.py fails if one appears here.
// Planned, added only once they have a build file: ":runtime:agent", ":ai:api", ":ai:cloud", ":capabilities:ai";
// conditional on spike S6: ":ai:local" (+ native/llama-jni).
```
5. Add `.github/workflows/ci.yml` through the GitHub web UI or with a token that has the `workflow` scope. X-01, G11-e and P11-09 stay open until it has run green (ADR-085).
6. Verify (these all passed on the merged tree in the planning sandbox; see `docs/VALIDATION_REPORT.md`):
```
python3 tools/progress.py && python3 tools/progress.py --check
python3 tools/check_docs.py
python3 -m unittest discover -s tests -p "test_*.py"
```

## 2. What is in the pack
| Class | Files |
|---|---|
| Documents (modified, merge carefully) | `plan.md`, `AGENTS.md`, `HANDOFF.md`, `KICKOFF.md`, `README.md`, `THIRD_PARTY_NOTICES.md`, `docs/*.md` that already existed, `prompts/phase-0.md`, `prompts/phase-1.md`, `verify/AGENTS.md`, `verify/KICKOFF.md`, `samples/press-button-flow/README.md` |
| Documents (new) | `docs/REUSE_MATRIX.md`, `docs/MIGRATION_NOTES.md`, `docs/VALIDATION_REPORT.md`, `docs/CODE_DISTRIBUTION.md`, `docs/CHANGES_FROM_AUTOHOST_ZIP.md`, `docs/PACK_MERGE.md`, `prompts/phase-12.md`, `*/imported/README.md`, `reference/README.md` |
| Tracker tooling (replaces or adds) | `tools/progress.py` (v2: keeps `parse(text)` and your `tests/test_progress.py`; adds ladder, deps, FAILING and blocker consistency), `tools/check_docs.py`, `tools/loc_report.py`, `tests/test_tools.py`, `docs/migration-manifest.tsv`, `.github/workflows/ci.yml` |
| Reference material | `reference/aria/`, `reference/closepaw/` (pristine snapshots) and the byte-identical staged copies under `ai/*/imported/`, `app/imported/`, `core/*/imported/`, `native/llama-jni/imported/`, `runtime/agent/imported/` |

## 3. Not in the pack
Your application source and build files (except the settings comment above), and the three extra reference projects (`android-agent`, `OpenDroid`, `Sanna`), which stay in your own archives; `docs/REUSE_MATRIX.md` rows R-27 to R-29 cite paths inside them. `tools/progress.py` and the dashboard block in `docs/PROGRESS.md` go together: if you merge `docs/PROGRESS.md` by hand, regenerate the dashboard with `python3 tools/progress.py` afterwards.
