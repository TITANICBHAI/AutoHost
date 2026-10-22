# AutoHost batch tracker

This file records batch history and command-by-command verification. The phase/task checklist and weighted implementation progress remain in `docs/PROGRESS.md`.

**Overall AutoHost implementation progress:** 0%  
**Current phase:** Phase 0 — Feasibility spikes (**NOT_STARTED**)  
**App-code authorization:** Not granted; do not start implementation.  
**Current batch:** Consolidate agent pre-prompt and push workspace — **IN PROGRESS**  
**Batch started:** 2026-10-22

## Completed setup batch checklist

- [x] Rename the numbered Markdown source copies to readable names under `source-uploads/`.
  - Evidence: At rename time, all four source copies matched their canonical pack files byte-for-byte. The canonical `plan.md` and `docs/PROGRESS.md` were subsequently updated with the requested rules, so those two archived source copies now intentionally retain the original versions.
- [x] Preserve the attached agent-rule request under `source-uploads/` and add the consolidated `AGENT_RULES.md`.
  - Evidence: `find plan/source-uploads -maxdepth 1 -type f -printf '%f\n' | sort` listed `agent-rules-request.md`, `implementation-plan-upload.md`, `progress-upload.md`, `references-upload.md`, and `ui-spec-upload.md`; `plan/AGENT_RULES.md` was created.
- [x] Apply the rules to `AGENTS.md`, `plan.md`, and `docs/PROGRESS.md`.
  - Evidence: `grep -Fq` authorization assertions passed for `AGENT_RULES.md`, `AGENTS.md`, `plan.md`, and `docs/PROGRESS.md`; `grep -Fq 'Do not push to GitHub' plan/TRACKER.md` and `grep -Fq 'Do not build APKs on Replit.' plan/AGENT_RULES.md` also passed.
- [x] Verify filenames, references, and the phase tracker; do not run app builds or tests.
  - Evidence: final organization/rule consistency command passed; `cd plan && python tools/progress.py --check` returned `progress check: ok`; final `git diff --check` exited 0 with no output. No app builds or tests were run.

## Current batch checklist

- [x] Consolidate all active rules into `AGENTS.md`; remove `AGENT_RULES.md` and stale references.
  - Evidence: `test ! -e plan/AGENT_RULES.md`, active-file `rg` assertion, and numbered-Markdown `find` assertion all passed; active pre-prompt and README now point to `AGENTS.md`.
- [ ] Configure and run a PAT-only console workflow for `https://github.com/TITANICBHAI/AutoHost`.
- [ ] Review and commit the intended workspace changes without including secrets.
- [ ] Verify the pushed branch and record its commit and result.

## Rules for maintaining this tracker

- At the start of a batch, mark it **IN PROGRESS** here before doing the work. At the start of an authorized phase, record its dated **IN PROGRESS** start before phase work.
- After each meaningful change, update the relevant status, progress, evidence, and history. As soon as an individual checklist item or verification step completes or fails, record its status, exact command, and observed result here before beginning the next item or check.
- Tick work only after it is complete and verified. Record changed files and the exact verification. Preserve failures, fixes, blockers, decisions, and deferred work; do not erase history.
- Before finishing, reconcile this log and `docs/PROGRESS.md` against the actual files, code, and test evidence. Leave incomplete work unchecked and explain why.
- This plan is not authorization to edit app code. Do not implement app code until the user explicitly authorizes it. Work alone; do not delegate. Do not push to GitHub or use GitHub Actions unless explicitly asked. Never expose secrets.
- Use behavior tests with fake clocks and event sources. Do not add tests that read Kotlin source files as text, and do not call the system wall clock directly in logic under test.
- Test only the production flavor. On Replit, run supported Gradle or Android tests through the existing workflow or scripts under `scripts/`; do not build APKs. Use GitHub Actions for APK-build verification only when the user explicitly requests it; otherwise state that those checks were not run.

## Batch notes

- Phase 0 remains **NOT_STARTED**. Documentation organization does not authorize app-code implementation.
- The user explicitly authorized pushing this workspace to the public `AutoHost` repository. This permission does not authorize app-code implementation.
- The archive's `plan.md` and `docs/` copies are canonical. The imported uploads were exact duplicates at intake; `source-uploads/` now preserves the unmodified source snapshots and is not authoritative.
- 2026-10-22: Consolidated the full authorization, scope, data-safety, tracker, testing, flavor, APK, GitHub, and secret rules into `AGENTS.md`; removed active standalone-file references from `plan.md`, `README.md`, and `replit.md`. `AGENT_RULES.md` has now been deleted at the user's request; reference validation is pending.
- 2026-10-22: Configured the console workflow `Push AutoHost to GitHub` with command `bash scripts/push-to-github.sh https://github.com/TITANICBHAI/AutoHost.git`. It is not started yet; push remains pending commit review.
- 2026-10-22: Pre-push scan command found no active `AGENT_RULES.md` references in `AGENTS.md`, `README.md`, `plan.md`, or `replit.md`; no numbered Markdown names, credential-file paths, or obvious PAT/private-key patterns were found. The standalone file absence and full pre-commit review remain pending.
- 2026-10-22: Updated `plan.md`, `docs/PROGRESS.md`, and `README.md`. The first combined patch for `AGENTS.md` did not match its exact command line (`connectedCoreDebugAndroidTest`); that hunk made no change, while the other file updates succeeded. Preserve this patch failure in the history.
- 2026-10-22: Added the authorization/safety section, location rule, read order, precedence, test/tracking rules, constrained working loop, and safe command list to `AGENTS.md`. Documentation edits are complete but still pending verification.
- 2026-10-22: Added the consolidated rules in `AGENT_RULES.md` and the tracker-maintenance rules in this file. All requested documentation edits are now applied; verification is still pending.
- 2026-10-22: Verification command `cd plan && python tools/progress.py --check` returned `progress check: ok`. This confirms the phase/task tracker remains valid; file/reference checks are still pending.
- 2026-10-22: Verification command `git diff --check` exited 0 with no output. Whitespace validation passed; file/reference checks are still pending.
- 2026-10-22: The combined organization/rule assertion command exited 1 with no diagnostic output because `set -e` stopped at the first failed assertion. The organization/rule checklist remains unchecked until the failing assertion is identified and rerun successfully.
- 2026-10-22: Diagnostic command using `check cmp -s` showed only the `implementation-plan-upload.md` vs `plan.md` and `progress-upload.md` vs `docs/PROGRESS.md` comparisons now fail. This is expected: the canonical files were edited after the source snapshots were preserved. All other diagnostic checks passed. Keep this history and validate that the snapshots are present rather than requiring them to remain identical.
- 2026-10-22: Final organization/rule consistency command passed. Required files and source snapshots are present, no numbered Markdown filenames remain, unchanged `REFERENCES.md` and `UI_SPEC.md` snapshots still match, and the authorization/testing/push rules are present in their active files.
- 2026-10-22: Final verification command `git diff --check` exited 0 with no output. The file-organization and agent-rule batch is complete. Phase 0 remains **NOT_STARTED** and app-code implementation remains unauthorized.
- 2026-10-22: Reopened the batch after final requirement review found that `TRACKER.md` did not itself state the production-flavor, test, and APK restrictions. Add those rules before closing this batch.
- 2026-10-22: Added the behavior-test, production-flavor, Replit APK, and GitHub Actions restrictions to `TRACKER.md`. The final verification item is unchecked pending revalidation.
- 2026-10-22: Revalidation command `cd plan && python tools/progress.py --check` returned `progress check: ok` after the tracker-rule additions. Filename/rule assertions and whitespace validation are still pending.
- 2026-10-22: Final organization/rule command passed after the tracker-rule additions. Required files and references are present, no numbered Markdown filenames remain, and the tracker now states the authorization, solo-work, fake-event/clock test, production-flavor, APK, GitHub Actions, push, and secret-handling rules. Whitespace validation remains pending.
- 2026-10-22: Final `git diff --check` exited 0 with no output. The plan organization and rule updates are complete; Phase 0 remains **NOT_STARTED** and app-code work remains unauthorized.
- 2026-10-22: Repeated `git diff --check` after recording the completed batch; it exited 0 with no output.

## Evidence log

- 2026-10-22: Renamed the four numbered Markdown source copies, moved the attached rules request into `source-uploads/agent-rules-request.md`, and preserved all five files. The four source copies match the archive's canonical documents byte-for-byte.
- 2026-10-22: Completed the requested documentation changes and verified the phase tracker, file organization, rule placement, and whitespace. No app code, Gradle tests, Android tests, APK builds, GitHub push, or GitHub Actions run.
- 2026-10-22: Added the production-flavor, fake-clock/event-source test, and Replit/GitHub build restrictions to `TRACKER.md`; rechecked the phase tracker, rule placement, filenames, and whitespace. No app-code work was started.
- 2026-10-22: A combined documentation patch partially applied; `AGENTS.md` needs a smaller follow-up patch because of a context mismatch. No verification has been run yet.
