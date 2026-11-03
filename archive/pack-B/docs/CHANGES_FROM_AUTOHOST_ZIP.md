# CHANGES_FROM_AUTOHOST_ZIP — what this pack changes relative to `AutoHost-main.zip` (2026-10-27)
Computed by comparing the delivered tree with the archive you supplied. **No application source under `app/`, `core/`, `runtime/`, `capabilities/`, `plugin-sdk/`, `testing/` or `sdk-python/` was modified.** Only documents, tracker tooling, tests of that tooling, the restored CI workflow, the reference/staging directories and a comment block appended to `settings.gradle.kts` (not shipped; see `docs/PACK_MERGE.md`) changed.

- Modified files: **27** · added files: **1175** · removed files: **0**

## Modified files
- `AGENTS.md`
- `HANDOFF.md`
- `KICKOFF.md`
- `README.md`
- `THIRD_PARTY_NOTICES.md`
- `docs/ANDROID_MANIFEST.md`
- `docs/CONVENTIONS.md`
- `docs/DECISIONS.md`
- `docs/DEVAPI.md`
- `docs/DEVICES.md`
- `docs/FIXTURE_APP.md`
- `docs/GATES.md`
- `docs/PROGRESS.md`
- `docs/README.md`
- `docs/REFERENCES.md`
- `docs/REPO_LAYOUT.md`
- `docs/SPIKES.md`
- `docs/UI_SPEC.md`
- `docs/VERSIONS.md`
- `plan.md`
- `prompts/phase-0.md`
- `prompts/phase-1.md`
- `samples/press-button-flow/README.md`
- `settings.gradle.kts`
- `tools/progress.py`
- `verify/AGENTS.md`
- `verify/KICKOFF.md`

## Added files
- `.github/workflows/ci.yml`
- `docs/CODE_DISTRIBUTION.md`
- `docs/MIGRATION_NOTES.md`
- `docs/PACK_MERGE.md`
- `docs/REUSE_MATRIX.md`
- `docs/VALIDATION_REPORT.md`
- `docs/migration-manifest.tsv`
- `prompts/phase-12.md`
- `tests/test_tools.py`
- `tools/check_docs.py`
- `tools/loc_report.py`
- **ai/cloud/imported/ (staged sources)**: 26 files
- **ai/local/imported/ (staged sources)**: 8 files
- **app/imported/ (staged sources)**: 32 files
- **core/designsystem/imported/ (staged sources)**: 17 files
- **core/model/imported/ (staged sources)**: 9 files
- **native/llama-jni/imported/ (staged sources)**: 5 files
- **reference/ (pristine snapshots)**: 1050 files
- **runtime/agent/imported/ (staged sources)**: 17 files

## Removed files
- (none)

## How to merge
See `docs/PACK_MERGE.md`. The lean pack ships only the documents, tracker tooling, `reference/` and the staged `imported/` copies from these lists (not `settings.gradle.kts`, whose comment block is given in that file). Your coding agent's own edits to `docs/PROGRESS.md`, `docs/DECISIONS.md`, `docs/GATES.md`, `AGENTS.md`, `HANDOFF.md`, `plan.md` and the verification files were preserved and extended, not replaced; the `(FAILING)` tags and their evidence lines are untouched except where the planner re-ran a Python check and recorded the result next to the earlier note.
