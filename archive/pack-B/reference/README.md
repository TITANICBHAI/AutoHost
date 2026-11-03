# reference/ — pristine snapshots, never built
Read-only copies of the two source projects, kept so the staged and adapted code can always be compared with its origin (`docs/REUSE_MATRIX.md`).

| Snapshot | Origin archive | SHA-256 of the archive | Omitted from the copy |
|---|---|---|---|
| `aria/` | `aria-ai-cpu-only-main.zip` | `d5e3a5d75d0081c3e62c9a31b0b77bfebdf2220153415349e8c16f70872944c5` | nothing |
| `closepaw/` | `closepaw-0_1_0.zip` | `7d1529b4e80a38e14586c85406e5a1bca4e7ce2bcd81bf9503ea94061148c42e` | 6 entries: `.codex`, `.claude`, `.agents`, `.cursorrules`, `GEMINI.md`, `AGENTS.md` (symlinks and ClosePaw's own `.claude/` agent configuration) |

Rules
- **Never include anything under `reference/` or any `imported/` directory in a Gradle build** (`settings.gradle.kts` enforces explicit includes; `tools/check_docs.py` fails if one appears). The two snapshots are separate Android applications; building them together would create two competing app implementations.
- Files named `AGENTS.md`, `CLAUDE.md` or similar inside `reference/` belong to those projects and **do not apply to AutoHost**; AutoHost's rules are the root `AGENTS.md`.
- Do not edit snapshot files. `docs/migration-manifest.tsv` records the SHA-256 of every staged file and `tools/check_docs.py` compares it with the pristine copy here.
- ARIA has no licence file (see `docs/REUSE_MATRIX.md` §E). ClosePaw is Apache-2.0: its `LICENSE` and `NOTICE` are preserved in `closepaw/`.
- The AutoHost tree the planner started from was `AutoHost-main.zip` (`dafc380c4b3a47a46548c938cbbaa6de53cc60f67128f96a749360dec46138e8`, 2026-10-27; no `.git` inside). Changes made to it are listed in `docs/CHANGES_FROM_AUTOHOST_ZIP.md`.
