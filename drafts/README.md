# drafts/ — unpromoted drafts (v4 planning pass)

Files here are **proposals**. They are not referenced by any Gradle source set, generator, validator or test, and nothing has been compiled or run against them.

| File | What | Source of truth | Promotion path |
|---|---|---|---|
| `agent.schema.draft.json` | Draft JSON Schema (2020-12) for the Stage-B `agent{}` manifest block | `docs/AGENT_RUNTIME.md` §4.2 (`config.agent`) and §15 (Stage B sketch) only | P13-18: add the block to `schemas/task.schema.json`, `ManifestValidator`, `TaskManifest`, migrations and regenerate (`tools/gen_artifacts.py`) — only after gates G13-a…f pass on a real device |
~ | `ci.workflow.yml` | PROMOTED 2026-11-03 (X-08): the verification workflow now lives at `.github/workflows/ci.yml` (Gradle 8.13 pinned, an emulator job added, debug assemblies deduplicated into `android-debug-apk.yml`) | the second planning pack's `ci.yml`, adapted | — promoted; this row is kept until the next drafts cleanup |

Rules: a draft is deleted or promoted, never left to drift; a draft never becomes the reference for code before its promotion task is ticked; `verify` and the `goal` templating syntax are deliberately undecided here.
