# drafts/ — unpromoted drafts (v4 planning pass)

Files here are **proposals**. They are not referenced by any Gradle source set, generator, validator or test, and nothing has been compiled or run against them.

| File | What | Source of truth | Promotion path |
|---|---|---|---|
| `agent.schema.draft.json` | Draft JSON Schema (2020-12) for the Stage-B `agent{}` manifest block | `docs/AGENT_RUNTIME.md` §4.2 (`config.agent`) and §15 (Stage B sketch) only | P13-18: add the block to `schemas/task.schema.json`, `ManifestValidator`, `TaskManifest`, migrations and regenerate (`tools/gen_artifacts.py`) — only after gates G13-a…f pass on a real device |
| `ci.workflow.yml` | Draft verification workflow (Python suites, doc check, drift checks, JVM tests, debug assemblies); not active | the second planning pack's `ci.yml`, adapted | X-08: copy to `.github/workflows/ci.yml`; the D-22 precondition is decided (2026-11-03: the owner chose GitHub Actions) — the remaining precondition is X-07 pinning Gradle |

Rules: a draft is deleted or promoted, never left to drift; a draft never becomes the reference for code before its promotion task is ticked; `verify` and the `goal` templating syntax are deliberately undecided here.
