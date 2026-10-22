read this plan, add it into a folder named plan with a tracker which has checkbox, progress, evidence etc
and a agent pre-prompt so new agents can get right context by read these files u will make, ofcouse no edit while i give him pre agent or until i tell him to start editing
here are some other rules u shall add in tracker, plan, pre-agent-

read this md and  after renaming it, put it into   folder

for tracker-
At the start of a batch, mark it in progress in its notes. After each meaningful change, update the relevant notes rather than waiting until the whole project is finished.
Tick each checkbox as soon as its work is complete and verified. Never mark unverified, partially completed, or merely attempted work as complete.
For every completed batch, record evidence: changed files, the exact verification performed, its result. Record decisions, failures, blockers, and deferred work as well.
If a check fails, keep the affected completion item unchecked, record the observed failure, and document the fix and successful rerun before ticking it.
Do not erase failed attempts or blockers from the record; add the outcome so another agent can follow the history.
Before finishing, reconcile the tracker with the actual code and test evidence. Leave incomplete items unchecked and say why
and make a agent pre prompt so the new agent can get context by reading these and have some rules too-
Work alone. Do not create, invoke, or delegate to sub-agents.
Follow the plan's implementation order and the tracker. Do not silently widen scope or change the plan's contract.
Before editing, inspect relevant current code, project instructions, and the working tree. Preserve unrelated user changes and user data.
Respect the plan's no-touch list unless the user explicitly changes the scope.
Treat task replacement and any other deletion of user data as destructive. Keep replacement disabled by default, preserve explicit confirmation and required safety guards, and do not perform destructive actions without the user's authorization.
run Gradle tests, or Android tests on Replit by using current workflow or scripts under scripts folder. If verification is needed, use GitHub Actions only when the user explicitly requests apk building verification. Otherwise state clearly that those checks were not run.
Do not push to GitHub or start, poll, or monitor GitHub Actions unless the user explicitly asks.
Do not expose, copy into files, or print secrets or credentials.
- Work alone. Do not create, invoke, or delegate to sub-agents.
- Run Gradle or Android tests on Replit using the current workflow or scripts under `scripts/`. Test only the production flavor. Do not build APKs on Replit.
- Use GitHub Actions for APK-build verification only when the user explicitly asks. Do not push to GitHub or start, poll, or monitor GitHub Actions unless explicitly asked.
- Do not expose, copy into files, or print secrets or credentials.
- Use behavior tests with fake clocks and event sources. Do not add tests that read Kotlin source files as text. Logic under test must not call the system wall clock directly.
- Respect the plan's no-touch list unless the user explicitly changes the scope.
- Treat task replacement and any other deletion of user data as destructive. Keep replacement disabled by default, preserve explicit confirmation and required safety guards, and do not perform destructive actions without the user's authorization.
- Run Gradle tests, or Android tests(leave apk building out of these two and no apk building on replit) on Replit by using the current workflow or scripts under `scripts/`. If verification is needed, use GitHub Actions only when the user explicitly requests APK-building verification. Otherwise state clearly that those checks were not run.
- You shall run test for only one flavour (meaning the production one's test only) becauses they are fundamentally same thing and hence even if run the same test twice but with diff flavour equals to same results.
- Do not push to GitHub or start, poll, or monitor GitHub Actions unless the user explicitly asks.
- Do not expose, copy into files, or print secrets or credentials.
- The plan is the implementation contract, not authorization by itself. Do not begin app-code implementation until the user explicitly authorizes it. If the requested scope or authorization is unclear, stop before changing app code and ask.
At the start of a phase, set it to IN PROGRESS and record a dated start note before doing its work.
After each meaningful change, update the relevant phase notes, status, progress, evidence, and history; do not wait until the phase is complete.
As soon as any checklist item or individual verification step (including each test, lint, or build check) completes or fails, update its checkbox/status and record the exact command and observed result in that folder/[TRACKER.md](http://tracker.md/) before starting the next item or check.
Tick a checkbox only after its work is complete and verified. Never mark attempted, partial, or unverified work complete.
For every completed item, record changed files, the exact verification performed, and its observed result.
If a check fails, leave the affected item unchecked and record the observed failure. Preserve it in the tracker; append the fix and successful rerun when available.
Record decisions, blockers, and deferred work. Do not erase old evidence or failure history.
Before finishing, reconcile that folder/[TRACKER.md](http://tracker.md/) with the actual code and test evidence. Leave incomplete items unchecked and explain why.
