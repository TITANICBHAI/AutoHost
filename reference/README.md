# reference/ — study material, not code

Purpose: keep the evidence behind the v4 reuse decisions next to the project, so a future agent can check a decision against the exact upstream text.

Rules
1. **Never on a source set.** Nothing here is referenced by `settings.gradle.kts`, any module, any Python package or any test. Do not add it.
2. **Never edit a snapshot.** Files under `closepaw/` and `opendroid/` are verbatim copies; their sha256 values are in `INDEX.md`. If upstream text must be adapted, copy the file into the owning module and follow `docs/REUSE_MATRIX.md` §0.
3. **Licence first.** Only Apache-2.0 files whose project carries a LICENSE are snapshotted (ClosePaw, opendroid). android-agent, sannabotapp and ARIA have no LICENSE (sannabotapp's README claims "MIT"): only path/size/hash manifests and the planning agent's own notes are kept — no source text.
4. **Findings are notes, not facts about a running system.** They come from static reading; each carries its own UNVERIFIED tags.
5. **Refresh** by re-extracting the named archive, re-running the copy, and updating `INDEX.md`/`SNAPSHOT.md` in one change.
6. **Pack B's notes.** `findings/aria_audit_packB.md` and `findings/closepaw_assessment_packB.md` are the second planning pack's assessments, copied verbatim on 2026-11-03. Task, ADR and R-numbers inside them are **that pack's**, not this tracker's. Where they disagree with `docs/REUSE_MATRIX.md`, the matrix wins.
7. **Complete snapshots live outside this folder.** Pack B's full copies of ClosePaw 0.1.0 (Apache-2.0; LICENSE and NOTICE included) and ARIA (**no LICENSE**) are under `../archive/pack-B/reference/`, so nothing was lost in the merge. ARIA is locked (D-17): provenance only, never copy from it; delete `archive/` before any public release. Rule 3 stays true for this folder.

Files: `INDEX.md` (every file, licence status, sha256), `SNAPSHOT.md` (source archives and method), `findings/`, `manifests/`, `closepaw/`, `opendroid/`.
