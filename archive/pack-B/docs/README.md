# docs/ index
| File | Purpose | Updated by |
|---|---|---|
| `PROGRESS.md` | Tickable tracker: status, % done, evidence | agent, every task |
| `GATES.md` | Exact procedure + evidence for every gate | planner; agent may refine steps |
| `REPO_LAYOUT.md` | File-level layout: modules, packages, resources, on-device paths | planner; agent updates with an ADR |
| `ANDROID_MANIFEST.md` | Manifest, permissions per flavor, services, XML | planner; agent on change |
| `UI_SPEC.md` | Screens, navigation, tokens, UI state, copy | planner; agent on change |
| `DEVAPI.md` | Dev API JSON-RPC methods + MCP tools | planner; agent on change |
| `CONVENTIONS.md` | Standards | planner |
| `FIXTURE_APP.md` | Test app contract | agent keeps in sync with code |
| `REFERENCES.md` | Lessons from reference projects, licence rules | planner |
| `DECISIONS.md` | ADRs + open decisions | agent + planner |
| `DEVICES.md` | Devices, verification matrix | agent (with user input) |
| `SPIKES.md` | Phase 0 results | agent |
| `REUSE_MATRIX.md` | What AutoHost takes from ARIA and ClosePaw, per file, with the evidence that proves it | planner; agent updates rows as files are integrated |
| `MIGRATION_NOTES.md`, `migration-manifest.tsv` | Source → staged path map, SHA-256, statuses (`staged` / `integrated` / `dropped`) | generated once; agent edits the manifest rows |
| `VALIDATION_REPORT.md` | Checks actually run vs not run, exact errors and environment limits | agent re-runs at each milestone |
| `PACK_MERGE.md`, `CHANGES_FROM_AUTOHOST_ZIP.md` | How the v4 planning pack is merged into the repository, and what it changed | planner; read once |
| `progress.html` | Static dashboard from `tools/progress.py --html` | generated |
Also at the repo root: `HANDOFF.md` (state and exact next task), `reference/` (pristine ARIA and ClosePaw snapshots, never built) and `*/imported/` (staged sources, never built).
Generated (do not hand-edit; created in Phase 5): `CAPABILITIES.md`, `SDK.md`, `capabilities.json`, the Skill-format agent pack.
