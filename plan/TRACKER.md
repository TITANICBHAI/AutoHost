# AutoHost batch tracker

This file records batch history and command-by-command verification. The phase/task checklist and weighted implementation progress remain in `docs/PROGRESS.md`.

**Overall AutoHost implementation progress:** 0%  
**Current phase:** Phase 0 — Feasibility spikes (**BLOCKED: device-dependent work deferred**)  
**App-code authorization:** Phase 0 authorized; no production modules before Gate 0.  
**Current batch:** Phase 0 license selection — **COMPLETED**
**Batch started:** 2026-10-22
**Batch completed:** 2026-10-22

## Completed setup batch checklist

- [x] Rename the numbered Markdown source copies to readable names under `source-uploads/`.
  - Evidence: At rename time, all four source copies matched their canonical pack files byte-for-byte. The canonical `plan.md` and `docs/PROGRESS.md` were subsequently updated with the requested rules, so those two archived source copies now intentionally retain the original versions.
- [x] Preserve the attached agent-rule request under `source-uploads/` and add the consolidated `AGENT_RULES.md`.
  - Evidence: `find plan/source-uploads -maxdepth 1 -type f -printf '%f\n' | sort` listed `agent-rules-request.md`, `implementation-plan-upload.md`, `progress-upload.md`, `references-upload.md`, and `ui-spec-upload.md`; `plan/AGENT_RULES.md` was created.
- [x] Apply the rules to `AGENTS.md`, `plan.md`, and `docs/PROGRESS.md`.
  - Evidence: `grep -Fq` authorization assertions passed for `AGENT_RULES.md`, `AGENTS.md`, `plan.md`, and `docs/PROGRESS.md`; `grep -Fq 'Do not push to GitHub' plan/TRACKER.md` and `grep -Fq 'Do not build APKs on Replit.' plan/AGENT_RULES.md` also passed.
- [x] Verify filenames, references, and the phase tracker; do not run app builds or tests.
  - Evidence: final organization/rule consistency command passed; `cd plan && python tools/progress.py --check` returned `progress check: ok`; final `git diff --check` exited 0 with no output. No app builds or tests were run.

## Previous GitHub preparation checklist (carried forward; incomplete)

- [x] Consolidate all active rules into `AGENTS.md`; remove `AGENT_RULES.md` and stale references.
  - Evidence: `test ! -e plan/AGENT_RULES.md`, active-file `rg` assertion, and numbered-Markdown `find` assertion all passed; active pre-prompt and README now point to `AGENTS.md`.
- [ ] Configure and run a PAT-only console workflow for `https://github.com/TITANICBHAI/AutoHost`.
- [ ] Review and commit the intended workspace changes without including secrets.
- [ ] Verify the pushed branch and record its commit and result.

## Completed next-agent setup checklist

- [x] Preserve the supplied request under `source-uploads/` with a descriptive Markdown name.
  - Evidence: `test -s plan/source-uploads/next-agent-tracker-request.md` and the archived-link assertion passed in the document/reference consistency check.
- [x] Add a dedicated read-first pre-agent prompt that routes the next agent to the canonical plan and trackers.
  - Evidence: `test -s plan/PRE_AGENT.md` and the README and `replit.md` pointer assertions passed in the document/reference consistency check.
- [x] Align the requested authorization, solo-work, scope, safety, verification, and evidence rules across the plan, agent instructions, and tracker.
  - Evidence: authorization/read-only rule assertions passed across `plan/plan.md`, `plan/AGENTS.md`, `plan/docs/PROGRESS.md`, and `plan/TRACKER.md`; exact consistency command is recorded below.
- [x] Verify the phase tracker and document/reference consistency; record exact commands and results below.
  - Evidence: `cd plan && python tools/progress.py --check` returned `progress check: ok`; the document/reference assertion command printed `document/reference consistency: ok`; the whitespace command `git diff --check && ! rg -n '[[:blank:]]+$' plan/PRE_AGENT.md plan/source-uploads/next-agent-tracker-request.md && printf 'whitespace check: ok\n'` printed `whitespace check: ok` after the logged fix.

## Completed correction batch checklist

- [x] Keep the requested rules directly in `PRE_AGENT.md`, `TRACKER.md`, and `plan.md`; remove my extra copies and pointers from other files.
  - Evidence: the rule-placement assertions passed for those three files, and assertions confirmed the added handoff rule/pointers are absent from `AGENTS.md`, `docs/PROGRESS.md`, `README.md`, and `replit.md`.
- [x] Verify the rule placement and whitespace; record exact commands and outcomes below.
  - Evidence: the rule-placement command printed `rule placement: ok`; `git diff --check` exited 0 and printed `whitespace check: ok`.

## Completed progress-view update checklist

- [x] Name both `plan/docs/PROGRESS.md` and `plan/docs/progress.html` in the pre-agent read order, clarifying that Markdown is authoritative.
  - Evidence: `plan/PRE_AGENT.md` explicitly names both files and says the Markdown file takes precedence if the generated HTML differs.
- [x] Verify the read-order text and whitespace; record exact commands and outcomes below.
  - Evidence: the assertions for both paths and authority text passed; `git diff --check` exited 0 and the new prompt had no trailing whitespace.

## Current batch checklist

- [x] Create the Phase 0 throwaway spike workspace and ensure the spike register has S1–S5 entries.
  - Evidence: Added `spikes/README.md`; direct review confirmed `plan/docs/SPIKES.md` already contains S1–S5 headings. No Android code, builds, tests, or device checks were performed.
- [x] Add the user's no-testing-until-authorized rule to the pre-agent prompt, including builds, lint, and automated verification.
  - Evidence: direct review of `plan/PRE_AGENT.md` lines 24, 33–34 confirms all tests and checks are deferred until explicit authorization.
- [x] Add the do-not-ask / defer rule for real-device verification and update the Phase 0 handoff guidance.
  - Evidence: direct review of `plan/PRE_AGENT.md` lines 25, 36, and 41 confirms real-device work is skipped without asking and left deferred.
- [x] Review the prompt wording and record that no tests or automated checks were run, as requested.
  - Evidence: direct review of `plan/PRE_AGENT.md` lines 24–41; no tests, builds, lint, or automated checks were run.

## Rules for maintaining this tracker

- At the start of a batch, mark it **IN PROGRESS** here before doing the work. At the start of an authorized phase, record its dated **IN PROGRESS** start before phase work.
- Keep batch notes current: after each meaningful change, update the relevant status, progress, evidence, and history rather than waiting for the batch to finish.
- As soon as an individual checklist item or verification step completes or fails, record its status, exact command, and observed result here before beginning the next item or check.
- Tick work only after it is complete and verified. For each completed item and batch, record changed files, the exact verification performed, and its observed result.
- If a check fails, leave the affected item unchecked and record the failure; append the fix and successful rerun when available. Preserve failures, blockers, decisions, and deferred work; do not erase history.
- Before finishing, reconcile this log and `docs/PROGRESS.md` against the actual files, code, and test evidence. Leave incomplete work unchecked and explain why.
- A context-only pre-agent prompt authorizes reading only; do not edit files until the user explicitly says to start editing. The plan is not authorization for app-code work: obtain explicit authorization before implementation. Work alone; do not delegate. Do not push to GitHub or use GitHub Actions unless explicitly asked. Never expose secrets.
- Use behavior tests with fake clocks and event sources. Do not add tests that read Kotlin source files as text, and do not call the system wall clock directly in logic under test.
- Test only the production flavor. On Replit, run supported Gradle or Android tests through the existing workflow or scripts under `scripts/`; do not build APKs. Use GitHub Actions for APK-build verification only when the user explicitly requests it; otherwise state that those checks were not run.

## Batch notes

- 2026-10-22: Started a documentation batch to record the user's selection of Apache-2.0, subject to dependency/reuse compatibility review and attribution requirements.
- 2026-10-22: Recorded Apache-2.0 as the selected app license in `plan/docs/DECISIONS.md`; P0-32 is WIP pending the dependency/reuse inventory and fallback ADRs. A combined patch failed to apply the `plan/docs/PROGRESS.md` hunks because they were out of order; correction was pending. No tests, builds, or checks were run.
- 2026-10-22: Applied the corrected `plan/docs/PROGRESS.md` update and regenerated its dashboard with `cd plan && python tools/progress.py`; it reported `Overall: 0% (weighted 1/725 points)`. No `--check`, tests, lint, builds, or device checks were run.
- 2026-10-22: Started a documentation batch to record the user's decision to defer P0-32 until the dependency/reuse inventory is ready. No license is selected in this batch.
- 2026-10-22: Recorded the user's P0-32 disposition in `plan/docs/DECISIONS.md` and `plan/docs/PROGRESS.md`; license selection remains open until dependency/reuse inventory. No tests, builds, or verification commands were run.
- 2026-10-22: Progress dashboard regenerated with `cd plan && python tools/progress.py`; it reported `Overall: 0% (weighted 1/725 points)`. Direct review showed Phase 0 BLOCKED with two blocked tasks. No `--check`, tests, lint, builds, or device checks were run.
- 2026-10-22: Started Phase 0 after the user's explicit instruction to begin it. P0-02 is the initial device-free task. Per the handoff, real-device facts/actions and all tests, builds, lint, and automated verification remain deferred unless separately authorized; no device findings will be inferred.
- 2026-10-22: Completed the device-free P0-02 scaffold: added `spikes/README.md`; the existing `plan/docs/SPIKES.md` already listed S1–S5. P0-02 is complete. No tests/builds/checks were run. Phase 0 is BLOCKED because device facts and Android experiments are deferred.
- 2026-10-22: Progress dashboard generation command `cd plan && python tools/progress.py` completed and reported `Overall: 0% (weighted 1/725 points)`. This regenerated status only; no `--check`, tests, lint, builds, or device checks were run.
- 2026-10-22: Started the documentation-only batch to prepare a read-only handoff for the next agent and align the requested work/evidence rules. No app-code authorization was granted or used.
- 2026-10-22: Added `plan/PRE_AGENT.md`, archived the supplied request as `plan/source-uploads/next-agent-tracker-request.md`, and aligned the handoff/verification rules in `plan/AGENTS.md`, `plan/plan.md`, `plan/docs/PROGRESS.md`, and this tracker. Updated the reading pointers in `plan/README.md` and `replit.md`. Verification remains pending; batch checkboxes stay unchecked until verified.
- 2026-10-22: Corrected the archived request's original tracker links to preserve the supplied text more faithfully. No app code was changed.
- 2026-10-22: Verification command `cd plan && python tools/progress.py --check` returned `progress check: ok`. The tracker-format check passed; document/reference and whitespace checks remain pending, so the batch verification item stays unchecked.
- 2026-10-22: Verification command `set -eu; test -s plan/PRE_AGENT.md; test -s plan/source-uploads/next-agent-tracker-request.md; rg -Fq 'plan/PRE_AGENT.md' plan/README.md; rg -Fq 'plan/PRE_AGENT.md' replit.md; rg -Fq 'A pre-agent prompt or context handoff is read-only.' plan/AGENTS.md; rg -Fq 'A context handoff is read-only.' plan/plan.md; rg -Fq 'A pre-agent context handoff is read-only.' plan/docs/PROGRESS.md; rg -Fq 'context-only pre-agent prompt authorizes reading only' plan/TRACKER.md; rg -Fq 'Before finishing, reconcile that folder/[TRACKER.md](http://tracker.md/)' plan/source-uploads/next-agent-tracker-request.md; printf 'document/reference consistency: ok\n'` printed `document/reference consistency: ok`; the archive, handoff, and cross-document rule checks passed. Whitespace validation remains pending.
- 2026-10-22: Verification command `git diff --check && ! rg -n '[[:blank:]]+$' plan/PRE_AGENT.md plan/source-uploads/next-agent-tracker-request.md && printf 'whitespace check: ok\n'` failed with exit code 2: `plan/TRACKER.md:8` had trailing spaces on the updated batch heading. The verification item remains unchecked; fix and rerun pending.
- 2026-10-22: Removed the trailing spaces from the updated batch heading in `plan/TRACKER.md`. The failed attempt remains recorded; whitespace rerun is pending.
- 2026-10-22: Verification rerun with `git diff --check && ! rg -n '[[:blank:]]+$' plan/PRE_AGENT.md plan/source-uploads/next-agent-tracker-request.md && printf 'whitespace check: ok\n'` exited 0 and printed `whitespace check: ok`.
- 2026-10-22: Final whitespace verification command `git diff --check && ! rg -n '[[:blank:]]+$' plan/PRE_AGENT.md plan/source-uploads/next-agent-tracker-request.md && printf 'final whitespace check: ok\n'` exited 0 and printed `final whitespace check: ok`.
- 2026-10-22: Started a corrective documentation batch after the user clarified that the rules belong directly in the pre-agent prompt, the tracker, and `plan.md` only. Remove my duplicate additions from other files; preserve the prompt, tracker, plan changes, and archived request.
- 2026-10-22: Removed the extra additions I had made to `plan/AGENTS.md`, `plan/docs/PROGRESS.md`, `plan/README.md`, and `replit.md`. The requested rules remain directly in `plan/PRE_AGENT.md`, `plan/TRACKER.md`, and `plan/plan.md`; placement verification is pending.
- 2026-10-22: Verification command `set -eu; rg -Fq 'This handoff is context, not permission to edit.' plan/PRE_AGENT.md; rg -Fq 'At the start of a batch, mark it **IN PROGRESS**' plan/TRACKER.md; rg -Fq '**A context handoff is read-only.**' plan/plan.md; ! rg -Fq 'A pre-agent prompt or context handoff is read-only.' plan/AGENTS.md; ! rg -Fq 'A pre-agent context handoff is read-only.' plan/docs/PROGRESS.md; ! rg -Fq 'plan/PRE_AGENT.md' plan/README.md replit.md; printf 'rule placement: ok\n'` printed `rule placement: ok`. Placement passed; whitespace validation remains pending.
- 2026-10-22: Verification command `git diff --check && printf 'whitespace check: ok\n'` exited 0 and printed `whitespace check: ok`. The requested rules now remain only in the pre-agent prompt, tracker, and `plan.md`; the corrective batch is complete.
- 2026-10-22: Started a documentation batch to include both progress views in the pre-agent prompt. `PROGRESS.md` is already listed; add `progress.html` as the generated visual dashboard and state that the Markdown remains authoritative.
- 2026-10-22: Updated `plan/PRE_AGENT.md` read order to name `plan/docs/PROGRESS.md` and `plan/docs/progress.html` separately, with the Markdown specified as authoritative if the generated HTML differs. Verification pending.
- 2026-10-22: Verification command `set -eu; rg -Fq 'plan/docs/PROGRESS.md' plan/PRE_AGENT.md; rg -Fq 'plan/docs/progress.html' plan/PRE_AGENT.md; rg -Fq 'PROGRESS.md` is the authoritative phase checklist and weighted-progress source.' plan/PRE_AGENT.md; rg -Fq 'If it differs from `PROGRESS.md`, follow the Markdown file.' plan/PRE_AGENT.md; git diff --check; ! rg -n '[[:blank:]]+$' plan/PRE_AGENT.md; printf 'pre-agent read list and whitespace: ok\n'` printed `pre-agent read list and whitespace: ok`. Both progress files are listed and the tracker batch is complete.
- 2026-10-22: Started a prompt-only update to defer all tests and real-device verification until the user explicitly asks. No tests, builds, or hardware work are authorized by this update.
- 2026-10-22: Updated `plan/PRE_AGENT.md` to prohibit tests, lint, builds, and automated verification unless explicitly authorized; it directs agents to skip real-device verification without asking, leave related items unchecked, and record them as deferred. No tests or automated checks were run.
- 2026-10-22: Directly reviewed `plan/PRE_AGENT.md` lines 20–55 and confirmed the no-testing rule, the no-ask/defer rule for real-device verification, and the Phase 0 guidance. No tests, builds, lint, or automated checks were run, per user instruction. Batch complete.
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
