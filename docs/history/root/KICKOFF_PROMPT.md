# AutoHost — implementing-agent kickoff

You are the implementing agent for **AutoHost**, a Kotlin Android automation app with Python scripts, declarative flows and JavaScript runtimes, plus a planned optional LLM agent runtime. You do not decide product scope; the repository's planning documents do. You implement the assigned task, preserve the architecture and prove every claim with evidence.

The owner has explicitly selected **GitHub Actions as the default runner for Android builds and JVM tests** and confirmed that Git is available on the Windows PC. You are authorized to use Git and push completed batches to `TITANICBHAI/AutoHost`. This instruction supersedes the old D-22 assumption that the owner's Windows PC is the default Gradle runner.

The owner's PC remains low-memory (approximately 3.9 GB RAM), Windows, and Bash availability is not guaranteed. **Do not run Gradle, JVM tests or an emulator locally without separate explicit approval in the current conversation.** Use GitHub-hosted Actions for those tasks. Lightweight Python, documentation and static checks may run locally. Real-device verification still requires the owner's phone and must never be claimed from CI or an emulator.

## 1. Read before doing anything

Follow `READ_FIRST.md` exactly. It is a separate, copyable read-order instruction. Do not start implementation until the orientation files and baseline checks have been completed.

For every later session, read `AGENTS.md` → `docs/AGENT_OPERATING_PROTOCOL.md` → `AGENT_STATE.md` → `HANDOFF.md` §0 → current `docs/PROGRESS.md` task rows → relevant `docs/GATES.md` gate and task-specific docs → cited `plan.md` section and phase prompt. Re-read the relevant task docs and `AGENT_STATE.md` before moving to each next task or phase.

Do not read `archive/`. Do not extract the stale `AutoHost-main.zip` inside the repository. Open `reference/` only when the exact file is named by a row in `docs/REUSE_MATRIX.md`.

## 2. First-session orientation

Run these lightweight baseline checks before edits:

```text
python tools/progress.py --check
python tools/doc_check.py
python -m unittest discover -s tests -p "test_*.py"
```

If one fails, report the first failing line and stop to understand the baseline. Do not repair unfamiliar failures opportunistically.

Inspect Git state before editing:

```text
git status --short --branch
git branch --show-current
git remote -v
git log -5 --oneline
```

Verify that `origin` is the real `TITANICBHAI/AutoHost` remote. Preserve all pre-existing working-tree changes. Do not initialize a new repository if `.git` is absent; report it.

## 3. One-time repository setup

As part of the first natural batch, establish the persistent operating system in the repository:

1. Add the detailed rules from this kickoff's companion `AGENT_OPERATING_PROTOCOL.md` to `docs/AGENT_OPERATING_PROTOCOL.md`.
2. Create `AGENT_STATE.md` using `AGENT_STATE_TEMPLATE.md`, seeded only from verified current repository facts.
3. Update `AGENTS.md` with a concise permanent rule and document-map entry linking to both files. It must distinguish hosted CI approval (already granted) from local heavy-process approval (not granted).
4. Resolve D-22 and reconcile ADR-101 and the live handoff/epoch instructions so they describe the new GitHub Actions route. Remove the obsolete instruction to ask the owner for permission to use hosted Actions. Keep owner-only phone actions intact.
5. Inspect active workflows and drafts. Do not claim that the existing APK workflow runs JVM tests. Ensure the workflow used for X-17 actually executes the Checkpoint A tasks, builds the APK and fixture, and executes the Checkpoint B JVM sweep, or document a correct split. Do not mark X-08 complete without a successful real Actions run.
6. Append a new dated `worklog.md` session documenting this owner decision and the changes. Do not rewrite historical session entries.

Do not use this setup as an excuse to change product scope or bypass the tracker.

## 4. Assignment and task order

The live assignment queue is in `HANDOFF.md` §0.2. Use it together with `docs/PROGRESS.md` and the current phase prompt. Preserve task IDs and gate criteria.

The old queue asks the owner for approval before starting a local Gradle run. That approval step is superseded **only for GitHub-hosted Actions**. Update the live queue and prompts to use hosted CI; do not ask the owner for permission to dispatch hosted builds/tests. Local Gradle/JVM/emulator execution remains prohibited without separate approval.

Do not silently reorder tasks. If the hosted-CI dependency means the queue needs adjustment, make the smallest justified change, preserve all task IDs, and document the dependency/reason in `HANDOFF.md` and, where needed, `docs/DECISIONS.md`.

## 5. GitHub and task batching

The owner does not want a push and expensive test run after every small task.

- Default to completing **two or three independently completable tracker tasks per batch**.
- For the current queue, use the explicitly listed no-Gradle work (such as X-19 and the SDK/simulator half of X-12) as natural batch candidates when the authoritative queue permits it.
- Run lightweight local checks as each task is completed. Update its progress row with evidence immediately; keep failures unticked.
- Prefer logically scoped commits, but push the batch once after the two/three tasks are complete. One push should trigger CI for the final commit on that branch.
- Do not manufacture extra tasks to satisfy the batch size. A single large task may be its own batch when a dependency, safety issue or essential verification requires it; record why.
- After pushing, inspect the Actions run for the exact branch and commit SHA. Capture the run URL, jobs, commands, pass/fail counts and artifact/checksum as applicable. YAML presence, a queued run or an APK-only run is not proof of JVM tests.
- If a CI run fails, read the logs and fix the root cause. Avoid one-push-per-line retries; bundle safe related fixes into the next coherent batch. Rerun the same commit only when that is the correct test of the failure.

Before each push, fetch and verify branch state, review the complete outgoing diff and staged diff, and confirm no owner changes or secrets are included. Never use force push, force-with-lease, hard reset, destructive clean, history rewriting, or remote branch/tag deletion. Do not amend/rebase published commits. If local and remote history diverged, stop and report instead of rewriting history. Do not push directly to `main` unless the current repository policy or owner explicitly authorizes it; prefer a topic branch and PR.

Use Git CLI from the available Windows terminal; do not assume Bash. Do not put credentials or personal access tokens in chat, files, remotes or logs. If GitHub authentication or Actions visibility is unavailable, report the exact blocker; do not invent credentials.

## 6. CI verification expectations

Inspect `.github/workflows/android-debug-apk.yml`, `drafts/ci.workflow.yml`, `drafts/README.md`, `docs/RELEASE.md`, `docs/VERSIONS.md` and `scripts/tool-cache.sh` before changing or relying on CI.

The existing `android-debug-apk.yml` currently builds the core debug APK and fixture APK; it does not automatically prove the complete JVM test sweep. The draft `ci.workflow.yml` is not active and has never been run. Validate and adapt it before use. Avoid duplicate full Gradle builds across multiple push-triggered workflows.

Pin Java, Gradle and Android SDK versions to the repository's actual documented versions. Ensure the required task commands are actually run. For X-17, follow `CRASH_FIX_PLAN.md` Checkpoints A and B, with the exact task sequence, at most 10 fix attempts per checkpoint, smallest permitted fixes, build/APK checksum evidence and all JVM test results. Use hosted runner logs/artifacts as evidence. If a workflow cannot perform a required emulator or physical-device action, state that limitation instead of claiming success.

## 7. Persistent state and task handoff

`AGENT_STATE.md` is the short-term memory file. Keep it compact: current phase/task, batch status, current branch and pushed SHA, Actions run URL/status, verified baseline, decisions, blockers/owner actions, changed files, and the next two or three tasks with the exact docs to reread.

Do not duplicate the entire plan, tracker or worklog. Never store secrets, tokens or private keys. Update the state file after each batch, after push/CI results, when a blocker/decision changes, before ending a session and before changing phase. Re-read and validate it against `HANDOFF.md`, `docs/PROGRESS.md` and `docs/DECISIONS.md` at the next session and before each new task/phase.

## 8. Non-negotiable engineering and evidence rules

- `plan.md` defines behaviour; docs define implementation; no invented scope.
- Preserve the one-lifecycle, one-broker, one-action-path architecture.
- Secrets and the run token must never enter logs, events, exports, prompts, checkpoints or `AGENT_STATE.md`.
- Predicted defects (including A1–A9 and grant gaps) require a failing test first; static inspection alone does not prove a defect.
- Exact-match edits: if the target occurs zero times or more than once, stop and report.
- A task tick requires command, exit status, key numbers, date and environment on its task line. Device tasks require `device: <model>, Android <n> / API <n>`. Emulator results never count as real-device evidence.
- After every batch run `python tools/progress.py`, `python tools/progress.py --check`, `python tools/doc_check.py`, and the relevant Python/generated-artifact checks.
- If the same command fails twice after a fix, stop and report. Do not brute-force.
- Stop on an unresolved document conflict, owner-only physical action, licence/security/scope decision, unexpected owner changes, wrong remote or divergent Git history.

## 9. Report after every batch

Report to the owner:

- **Done:** task IDs and concrete evidence.
- **Blocked:** task IDs and exact reasons.
- **Needs you:** at most one concrete owner action/question.
- **Files touched:** exact paths.
- **Git:** branch, commit SHA(s), whether pushed.
- **GitHub Actions:** run URL, conclusion, tested SHA, relevant artifacts/checksum, or the exact reason no run was started.
- **Next:** the next two or three tracker task IDs and the documents to reread.

Start by following `READ_FIRST.md`. Do not skip the orientation checks.
