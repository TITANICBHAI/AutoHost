# AutoHost — pre-agent handoff prompt

You are the implementing agent for AutoHost, an Android-native host for user-authored automations. The canonical product and implementation contract is in `plan/plan.md`; this handoff gives you the safe reading order and operating rules for a new context.

## First response: read only

This handoff is context, not permission to edit. Read the files below and report the current project state. Do not edit any files, install packages, run builds or tests, push to GitHub, or start GitHub Actions until the user explicitly says to start editing or perform that action.

The user must separately and explicitly authorize app-code implementation. A request to read, review, prepare, or track the plan does not authorize app-code work.

## Read order

1. `plan/AGENTS.md` — highest-priority safety, scope, testing, and working rules.
2. `plan/TRACKER.md` and `plan/docs/PROGRESS.md` — current batch history, phase status, evidence, blockers, and next unticked task.
3. The documents named by the authorized task in `plan/docs/PROGRESS.md`.
4. The sections of `plan/plan.md` cited by that task.
5. `plan/README.md` for the source-pack location and tracker commands.

`plan/source-uploads/` contains archived input snapshots only. The canonical plan and active documents are under `plan/`.

## Rules after the user authorizes work

- Work alone. Do not create, invoke, or delegate to sub-agents.
- Follow the plan and tracker in order, one phase at a time. Do not widen scope or change the implementation contract silently.
- Before editing, inspect relevant current files, project instructions, and the working tree. Preserve unrelated changes and user data; respect the plan's no-touch list.
- Treat replacing tasks or deleting user data as destructive. Keep replacement disabled by default and retain required confirmation and safety guards.
- At each batch start, mark it **IN PROGRESS** in `plan/TRACKER.md`; at each authorized phase start, record a dated **IN PROGRESS** note before phase work.
- After each meaningful change and each individual check, immediately update the tracker with status, changed files, exact command, observed result, failures, blockers, decisions, and deferred work. Keep `plan/docs/PROGRESS.md` statuses and evidence synchronized.
- Tick an item only when complete and verified. Record changed files and exact verification for each completed item and batch. Failed or partial work stays unchecked; preserve the failure and append the fix and successful rerun.
- Use behavior tests with fake clocks and event sources. Do not add tests that read Kotlin source as text, or call the system wall clock directly in logic under test.
- On Replit, run only supported Gradle or Android tests through existing workflows or scripts under `scripts/`; test only the production flavor and do not build APKs. Use GitHub Actions for APK-build verification only when explicitly requested.
- Do not push to GitHub or start, poll, or monitor GitHub Actions unless explicitly asked. Never expose, copy into files, or print secrets or credentials.
- Ask the user for required device facts, physical actions, or unclear scope, safety, licensing, destructive, or authorization decisions. Do not treat a plan task as authorization.
- Before finishing, reconcile `plan/TRACKER.md` and `plan/docs/PROGRESS.md` against the actual files and verification evidence. Leave incomplete work unchecked and explain why.

## Current plan entry point

The active plan currently identifies Phase 0 — Feasibility spikes as **NOT_STARTED**. Confirm that status from the live trackers instead of relying on this note. Before any Phase 0 work, obtain explicit authorization and collect the device facts and physical-action availability required by `plan/prompts/phase-0.md`.
