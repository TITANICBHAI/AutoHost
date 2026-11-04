# AutoHost — Agent Operating Protocol

**Purpose:** define how an implementing agent works across sessions, batches changes, uses Git/GitHub Actions, navigates the planning documents and records evidence. This file supplements `AGENTS.md`; it does not replace `plan.md`, `HANDOFF.md`, `docs/PROGRESS.md` or `docs/GATES.md`.

## 1. Authority and source of truth

Use each file for its actual responsibility. Do not create competing task trackers.

| File | Authority | How to use it |
|---|---|---|
| `AGENTS.md` | Permanent repository and agent rules | Read every session. Keep concise; link to this protocol and the state file. |
| `docs/AGENT_OPERATING_PROTOCOL.md` | Detailed working procedure | Read every session and before a new batch. |
| `AGENT_STATE.md` | Current short-term agent memory | Read at session start and before each new task/phase. Update at each batch boundary. |
| `HANDOFF.md` | Current assignment queue, environment and owner handoff | Follow its live queue. Update stale operational instructions when an owner decision changes them. |
| `docs/PROGRESS.md` | Authoritative task status and evidence | Update the task line as soon as evidence exists. Never infer completion from code inspection. |
| `plan.md` | Product behaviour, scope, architecture and phase plan | Consult the exact sections cited by the current task. |
| `docs/GATES.md` | Verification procedures and exit criteria | Follow the relevant gate exactly; record the evidence it requires. |
| `docs/DECISIONS.md` | Accepted decisions and unresolved owner decisions | Do not reopen accepted decisions. Record new decisions and close resolved questions. |
| `docs/CONVENTIONS.md` | Coding, testing, naming and Git conventions | Follow before editing or committing. |
| `worklog.md` | Append-only session history | Append a concise session/batch record. Do not treat historical statements as current instructions. |
| `prompts/*.md` | Phase-specific work instructions | Read the relevant prompt when entering a phase or when the tracker points to it. |
| `docs/REUSE_MATRIX.md` | File-level source reuse and licence decisions | Read before adapting code. Read `reference/` only for a file explicitly named by a row. |

**Precedence:** `plan.md` defines behaviour; task-specific design docs define implementation details; `docs/PROGRESS.md` defines task status; `HANDOFF.md` defines the live assignment queue; this protocol defines the operating procedure. If live documents conflict, stop, record the exact conflict in `docs/DECISIONS.md`, and reconcile the affected documents. Never silently choose whichever statement is convenient.

`AGENT_STATE.md` is a navigation and continuity aid, not an alternate source of truth. When it disagrees with the tracker, handoff or accepted decision, the authoritative file wins and the state file must be corrected.

## 2. Owner-approved GitHub Actions operating model

The owner has selected GitHub Actions as the default place to run resource-intensive Android builds and JVM tests for `TITANICBHAI/AutoHost`.

- Use the repository's Git checkout and Git CLI to manage source changes and push completed batches to the repository.
- Use GitHub-hosted Actions runners for Gradle builds, JVM tests and any other heavy CI task supported by the workflow.
- Do **not** run Gradle, JVM tests or an emulator locally on the owner's low-memory Windows PC unless the owner separately gives explicit approval in the current conversation.
- Do not ask the owner for approval to use GitHub-hosted Actions. This protocol is that approval.
- A successful build workflow is not proof that JVM tests passed. Confirm the exact jobs and test tasks that actually ran.
- Physical-device checks, device facts, and actions that require the owner's phone remain owner tasks. An emulator or hosted CI run is never real-device evidence.

### Current workflow facts to verify, not assume

At the time this protocol was drafted:

- `.github/workflows/android-debug-apk.yml` triggers on `push`, `pull_request` and `workflow_dispatch`. It builds the core debug APK and fixture APK and uploads artifacts/logs. It does not, by itself, prove the complete JVM test suite.
- `drafts/ci.workflow.yml` is a draft, not an active workflow. It contains Python and JVM jobs but has not been executed; its Gradle setup and task coverage must be checked before promotion.
- The draft does not include an emulator job.

Inspect the actual checked-out workflow files before relying on these statements. They may have changed.

### CI workflow requirements

When a task requires hosted compilation or tests:

1. Identify the exact required Gradle tasks from `HANDOFF.md`, the task row, `CRASH_FIX_PLAN.md` and the relevant gate.
2. Inspect the active workflows and determine which workflow actually runs those tasks. If none does, extend or restore the CI workflow as part of the relevant tracked task; do not claim that an unexecuted draft is evidence.
3. Keep one canonical expensive Android/JVM pipeline where practical. Avoid accidentally running the same full Gradle build in multiple push-triggered workflows.
4. Pin the toolchain to the repository's documented versions (currently Java 17 and Gradle 8.13 are referenced by the live handoff/workflow; verify against `docs/VERSIONS.md` and `scripts/tool-cache.sh`). Prefer the Gradle wrapper if X-07 chooses to add it; otherwise use the explicitly pinned Gradle version.
5. The workflow should support the branches used for agent work, pull requests and `workflow_dispatch`. A manual dispatch runs the latest pushed commit on the selected ref; it cannot test uncommitted local changes.
6. Keep permissions minimal. Do not add write permissions, repository secrets or a bot auto-commit/push step unless a task explicitly requires them and the owner approves the scope.
7. Upload useful build/test reports and the debug APK as artifacts when the task requires them. Record the APK SHA-256 from the actual artifact/build output, not from a guessed path or a previous run.
8. Do not call a workflow successful until the run has completed successfully and the relevant job/task is visibly green.

### CI evidence

For each hosted verification result, record:

- workflow name and run URL or run ID;
- branch and full commit SHA tested;
- job and exact Gradle/Python command(s);
- final conclusion and exit status;
- relevant test counts/failure names;
- artifact name and APK SHA-256 when applicable;
- run date and runner/toolchain versions.

A workflow YAML file existing in the repository is not evidence. A queued or in-progress run is not evidence. A build-only run is not JVM-test evidence. A rerun of an old commit does not verify newer unpushed source changes.

If `gh` (GitHub CLI) is installed and authenticated, it may be used to inspect or dispatch runs. First check availability/authentication without printing tokens. Otherwise use the GitHub repository's Actions UI. Never ask the owner to paste a personal access token into chat, a file, a workflow log or a Git remote URL.

## 3. Git safety and remote policy

### Session preflight

Before changing or committing anything, inspect:

```text
git status --short --branch
git branch --show-current
git remote -v
git log -5 --oneline
```

Confirm that the working directory is the real repository checkout and that `origin` points to `TITANICBHAI/AutoHost` (HTTPS or SSH form is acceptable). Do not infer the remote from a folder name. If the checkout is not a Git repository, the remote is wrong, authentication is unavailable, or the branch state is ambiguous, stop and report the exact condition instead of initializing a new repository or inventing credentials.

Git is available on the owner's PC. Do not assume Bash is installed; use Git commands that work in PowerShell/CMD or the available terminal. Keep heavy build/test commands off the PC.

### Preserve owner work and history

- Never discard, overwrite, stage or commit unrelated pre-existing user changes.
- Do not use `git add .` or `git add -A` blindly. Stage only the files belonging to the completed batch, then inspect `git diff --cached`.
- Never use `git push --force`, `--force-with-lease`, `git reset --hard`, `git clean -fd`, history rewriting, or deletion of remote branches/tags.
- Do not amend or rebase commits that have been pushed. Preserve the existing commit history.
- Before pushing, fetch the remote and check whether the branch can be advanced safely. Use fast-forward-only integration when needed. If the local and remote branches have diverged, stop and report; do not resolve divergence by force or by silently rewriting history.
- Do not push directly to `main` unless the repository's current contribution policy or the owner explicitly authorizes it. Prefer a topic branch and PR when the current branch is `main` and no direct-main instruction exists.
- Do not put tokens or credentials in remote URLs, commit messages, files, prompts or logs.
- Before every push, inspect `git diff`, `git diff --cached`, `git status` and the outgoing commit list. Confirm that no keystore, `local.properties`, personal screenshot, credential, generated cache or secret-bearing file is included.

### Commit and push cadence

The owner does not want a push and expensive CI run after every tiny task.

- Work in **batches of two or three independently completable tracker tasks** by default.
- Complete a task's implementation and all applicable lightweight local checks before considering it complete. Update its `docs/PROGRESS.md` row with evidence immediately when the result exists.
- Prefer logically scoped commits: one commit per task, or one commit for a tightly coupled group. Several commits can be pushed together.
- Push **once after the batch**, not after each task. That push should trigger one CI run for the batch's final commit, provided the active workflow is configured for that branch/event.
- Do not artificially invent or split tasks just to reach two. A single large task may be a batch when its dependencies, safety or verification requirements require it. Explain the exception in `AGENT_STATE.md` and the worklog.
- If a CI run fails, inspect the failed job and logs, fix the cause, and continue with the next coherent task or closely related fixes before the next push when safe. Use a manual rerun only when rerunning the exact same pushed commit is meaningful. Do not repeatedly push one-line fixes just to probe CI.
- Do not postpone an essential test when proceeding would make later work unsafe or invalidate the evidence. In that case, explain why the batch is smaller than usual.

### Typical batch cycle

1. Select the next two or three tasks from the authoritative queue.
2. Re-read the current state, task rows, gate and task-specific docs.
3. Implement only the named scope. Run quick, non-heavy checks locally (Python, documentation, schema, static checks) as appropriate.
4. Update each task line in `docs/PROGRESS.md` immediately when evidence exists. Keep failing tasks unticked and mark `(FAILING: reason)`.
5. Run the required post-batch checks: `python tools/progress.py`, `python tools/progress.py --check`, `python tools/doc_check.py`, plus relevant Python suites and generated-artifact checks.
6. Update `worklog.md` and `AGENT_STATE.md` with the batch result and next exact tasks.
7. Review the staged diff, commit the batch, fetch/check remote state, then push once.
8. Inspect the GitHub Actions run for that exact pushed SHA. Download/read relevant logs and artifacts. Do not claim success until all required jobs finish.
9. Update progress evidence and the worklog with the actual CI run URL, commands, counts, date, environment, artifact checksum and any failures. If this evidence update changes the tree, commit and push it in the next coherent batch; do not create a pointless push solely for a one-line bookkeeping change unless the workflow/evidence gate requires it.
10. Re-read `AGENT_STATE.md` and the next task's documents before moving on.

## 4. Task-to-document navigation

For every task, use this sequence:

1. Read its row in `docs/PROGRESS.md` and identify dependencies, size, state tag and evidence requirement.
2. Read the relevant gate in `docs/GATES.md`.
3. Read the design document(s) named in the task row, `AGENTS.md` doc map or the phase prompt.
4. Read the exact `plan.md` sections referenced by those documents.
5. Inspect only the source files named by the task and their direct dependencies. Expand the inspection only when the code or test evidence demonstrates a need.
6. Before adapting third-party code, read the exact `docs/REUSE_MATRIX.md` row and only then the named reference file. Respect its licence and test requirements.

At a phase transition, read the phase's `prompts/phase-N.md` or the appropriate epoch prompt, its entry criteria, gate and relevant `plan.md` sections. Do not assume the next phase starts just because the previous implementation looks finished; check its explicit exit condition in `docs/PROGRESS.md`/`docs/GATES.md`.

## 5. Persistent working memory

Maintain the repository-root file `AGENT_STATE.md`. It must be short enough to scan quickly and should contain:

- last updated date/time and current branch;
- current phase/epoch and exact active task ID(s);
- current batch: tasks in progress, tasks completed but not yet pushed, pushed commit SHA and latest Actions run URL/status;
- latest verified baseline results and environment;
- decisions already made that affect implementation, with links to `docs/DECISIONS.md`;
- unresolved blockers/owner actions, including whether a physical phone is required;
- next two or three exact task IDs and the documents that must be reread for each;
- files changed in the most recent batch;
- one short note on pitfalls or failed approaches that the next session must not repeat.

Do **not** copy the whole progress tracker, worklog, plan or CI logs into `AGENT_STATE.md`. Link to the authoritative location. Never store credentials, tokens, secret values, private keys, personal device data beyond what the project already documents, or raw sensitive logs in this file.

Update `AGENT_STATE.md`:

- after each two/three-task batch;
- after a push and after the corresponding Actions result arrives;
- whenever a blocker or owner decision changes;
- before ending a session;
- before starting a new phase.

At the start of a later session and before every new task/phase, reread it and verify its references against `HANDOFF.md`, `docs/PROGRESS.md` and `docs/DECISIONS.md`. Correct stale state; do not blindly trust it.

## 6. Required documentation reconciliation for the new operating model

The owner has explicitly chosen GitHub Actions for hosted builds/tests and confirmed that Git is available on the PC. During the initial setup batch:

1. Add this protocol to `docs/AGENT_OPERATING_PROTOCOL.md` and add it to the documentation map in `AGENTS.md`.
2. Create and seed `AGENT_STATE.md` from the real tracker/handoff state (do not guess).
3. Update `AGENTS.md` so it no longer says Git is unavailable. Retain the warning about the weak Windows PC and no-Bash assumption unless independently confirmed. State clearly that local Gradle/JVM/emulator runs still require separate owner approval, while hosted GitHub Actions are already authorized.
4. Resolve D-22 in `docs/DECISIONS.md` to record GitHub Actions as the default hosted build/test route. Reconcile ADR-101 and related wording so it distinguishes hosted CI approval from local heavy-process approval.
5. Update the live assignment queue and current kickoff/epoch prompt(s) so the agent no longer asks the owner for permission to use GitHub Actions. Keep physical phone checks assigned to the owner.
6. Update active operational documentation that contradicts the new policy. Do not rewrite historical worklog entries; append a new session entry explaining the owner decision and the date it was adopted.
7. Inspect the active workflow and draft. Ensure the workflow used for X-17 actually runs the required Checkpoint A, APK build and Checkpoint B JVM tasks, or clearly split these across appropriate jobs/workflows. Ensure X-08 is not marked complete until a real run verifies the promoted workflow.
8. Keep the original task IDs and gate requirements. Change task order only where a genuine dependency requires it, and record the reason in the relevant decision/handoff document.

Do not treat the initial documentation update as permission to change product scope or bypass the existing task queue.

## 7. Evidence, stopping and reporting

- A tracker tick requires the exact command, exit status, key numbers, date and environment on the task line. Device tasks also require `device: <model>, Android <n> / API <n>`.
- A failing test stays unticked and receives `(FAILING: reason)`. A prediction from static code reading is not a reproduced defect.
- After every batch run `python tools/progress.py`, `python tools/progress.py --check` and `python tools/doc_check.py`.
- Use exact-match edits only when the target occurs exactly once. If it occurs zero times or multiple times, stop and report.
- If the same command fails twice after a fix attempt, stop and report the first error, command and attempts. Do not brute-force.
- Stop on conflicting requirements, unexpected untracked/modified owner files, an incorrect remote, remote divergence, missing CI authentication, an owner-only phone action, or a licence/security/scope decision. Do not improvise around these.
- After every batch report: **Done** (IDs + evidence), **Blocked** (ID + reason), **Needs owner** (at most one concrete question/action), **Files touched**, **Commit/push** (SHA/branch or not pushed yet), and **Actions** (run URL, status, tested SHA, or reason not run).

## 9a. Owner session-control words (owner instruction, 2026-11-03 — bind every session)

- **"continue"** → proceed to the next task in the queue (no re-confirmation needed; the queue
  in `HANDOFF.md` §0.2 + `AGENT_STATE.md` decides what "next" is).
- **"recheck"** → re-verify the stage just completed: re-run its checks, re-read every file the
  stage touched, confirm nothing was lost or half-applied (the silent-revert and index-wipe
  incidents are the reason this word exists), update AGENT_STATE/worklog if anything changed,
  then ask the owner "shall I continue?" and wait for the answer.
- **READ ALL RELATED DOCS, STRICTLY (owner, 2026-11-03):** before doing any plan part, read
  EVERY `.md`/file related to it — the task row, its ADRs, the gate section, every doc the row
  names, plus the plan section it cites. No exceptions, no skipping "known" files.
- **BATCH MORE, PUSH LESS (owner, 2026-11-03):** pushes were too frequent. Do substantially more
  work before each push — gather multiple fixes/tasks into one commit+push, then verify once.
  Do not push after every single fix or diagnosis round.
- **NO DEVICE EXISTS (owner, 2026-11-04, D-25):** the owner can never provide a physical device.
  Device-gated items are skipped as unverifiable — annotate `(BLOCKED: no device — D-25)` on the
  row, keep unticked, and do not let them block dependent tasks (X-09 waives X-18). Never claim
  device evidence; never ask the owner for phone actions.
- **EMULATOR JOB IS A NO-GO (owner, 2026-11-03):** never run the CI emulator job, no matter
  what. Instrumented/emulator verification is skipped entirely; recorded as D-24. Device
  evidence comes only from the owner's phones.

## 10. Every-time checklist (what to check before each step)

*Added 2026-11-03 from the first working session's incidents. This checklist is the "how you
will do it / what you check each time" contract; everything else in this file is the detail.*

### Before starting a task
1. `git fetch origin && git status -sb` — tree clean, branch `main` in sync with `origin/main`,
   remote is `TITANICBHAI/AutoHost`. Unexpected state (emptied index = hundreds of staged
   deletions, detached HEAD, divergence) → STOP and report; the index fix is a plain
   `git reset` (never `--hard`).
2. Re-read the task row in `docs/PROGRESS.md` and the docs its evidence names (ADR, gate).
   Blocked/dependency rows are not eligible.
3. Confirm the task needs no local Gradle/JVM/emulator (heavy work = hosted CI only, D-22).
4. Confirm no other agent session is active in this checkout (owner confirmed "only this
   session" on 2026-11-03; re-confirm if signs of concurrent edits appear — e.g. an Edit tool
   "modified since read" error you cannot explain, or a tick that silently reverts).

### Before every edit
1. Exact-match rule: the text you replace must occur exactly once; 0 or 2+ occurrences → STOP.
2. For tracker ticks: tick + evidence line in one edit, then IMMEDIATELY `grep -c "^- \[x\]"`
   (before AND after `python tools/progress.py`). A silent no-op write was observed once
   (2026-11-03, PROGRESS.md) — never trust an unverified edit.
3. Never hand-edit generated artifacts (`docs/CAPABILITIES.md`, `docs/SDK.md`,
   `sdk-python/**/_generated`, golden vectors, the broker resource copy) — run the generator.
4. Edits sweep only the task's named files; anything else you notice goes to the worklog as an
   observation (with a prediction test per ADR-089), not an opportunistic fix.

### Before every commit / push
0. **RECHECK LOOP (owner, 2026-11-04 — mandatory, repeat until clean):** after making edits,
   re-check EVERYTHING the batch touched (syntax by eye where no compiler exists, file
   line-endings, comment syntax per language, YAML validity, the exact texts you replaced).
   If ANY error is found: fix it, then RE-RUN THE WHOLE RECHECK — repeat the fix→recheck loop
   until a full round finds ZERO problems. Only then commit. Never commit straight after an
   edit without a clean recheck round; never commit while any known error is open.
1. Checks green: `python tools/progress.py --check`, `python tools/doc_check.py`, plus the
   suites the batch touched.
2. `git fetch origin` → confirm `origin/main` is where you last saw it; if it moved or the
   tree changed unexpectedly → STOP and report (never merge blindly, never force).
3. Stage files BY NAME (no `git add -A`); review `git diff --cached`; no secrets, keystores,
   `local.properties`, screenshots, or unrelated owner changes.
4. Commit message: task id first (`X-19: ...`). Plain fast-forward push to `main` only —
   no force, no amend/rebase of published commits, no history rewriting.
5. Push once per batch (2–3 tasks), not per task.

### After every push
1. Fetch the run via the anonymous GitHub API (`curl -s https://api.github.com/repos/
   TITANICBHAI/AutoHost/actions/runs?per_page=3`) — `gh` is NOT installed on this PC.
2. Verify which jobs actually ran: the APK workflow proves NO tests (ADR-085, §2).
3. Record run URL, SHA, conclusion on the task row; update `AGENT_STATE.md`; verify the ticks
   persisted.
