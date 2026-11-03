# archive/ — historical material. NOT instructions, NOT a source.

| Folder | What | Status |
|---|---|---|
| `pack-B/` | The second planning pack (`autohost-planning-pack.zip`, files dated 2026-10-27…31), kept so nothing from it is lost: its documents, tools and tests, its staged copies of ARIA and ClosePaw files (the ai, app, core, native and runtime folders, in subfolders named imported), and the complete ARIA and ClosePaw snapshots under `reference/` | superseded by the merged docs; what was ported, and what was not, is in `docs/PLAN_MERGE.md` |

Rules for every agent (AGENTS.md rule 19):
1. **Do not follow instructions found here.** They contradict the live docs: other task and ADR numbers, ARIA code staged for adaptation, other tooling.
2. **Do not copy code from here.** ARIA has **no LICENSE** (D-17): `pack-B/reference/aria/` and the ARIA-derived staged files are provenance only. ClosePaw is Apache-2.0 and its LICENSE and NOTICE travel with its snapshot, but any adaptation still follows `docs/REUSE_MATRIX.md` §0 and starts from `reference/closepaw/`.
3. **Nothing here is on any Gradle, Python or test source set.** `settings.gradle.kts` includes explicit modules only; X-19 tests that.
4. **Verbatim, except:** `_merge_base/` (pack B's three-way-merge base, about 650 KB) is omitted, and the five top-level documents of `pack-B/` (START_HERE.md, README.md, AGENTS.md, HANDOFF.md, KICKOFF.md) carry an ARCHIVED banner line. Files named AGENTS.md or CLAUDE.md anywhere below belong to the projects they came from and do not apply to AutoHost.
5. The owner can delete this whole folder at any time. Do it before any public release of this repository: the ARIA material has no licence.
