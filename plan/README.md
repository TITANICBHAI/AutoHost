# AutoHost agent pack
This source pack is kept inside `plan/` as requested; do not unzip or move it into the workspace root unless the user explicitly asks.

Start from the workspace root with `plan/AGENTS.md`, then `plan/TRACKER.md` and `plan/docs/PROGRESS.md`. The agent rules are part of `plan/AGENTS.md`. After explicit authorization to begin app-code work, use `plan/prompts/phase-0.md` and the phase-specific documents.

`docs/PROGRESS.md` contains the phase/task checkboxes and weighted progress. `TRACKER.md` records batches and exact verification results. From the workspace root, run `(cd plan && python tools/progress.py)` to regenerate the dashboard and `(cd plan && python tools/progress.py --check)` to validate the phase tracker.

The exact duplicate Markdown uploads are preserved with readable names in `source-uploads/`; the archive's `plan.md` and `docs/` files remain canonical.
