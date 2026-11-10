# AGENTS.md — how to work on AutoHost

AutoHost is an Android app (Kotlin + Jetpack Compose) that runs small automations (Python, flows, JS) through a
permission broker. Read this file, then `STATUS.md`. Nothing else first.

## Read order
1. `STATUS.md` — what is true now and what to do next.
2. `work/plan/00_OVERVIEW.md`, then **only** the `work/plan/Sxx_*.md` file that STATUS "Now" names.
3. `docs/CONVENTIONS.md` before you write Kotlin, `docs/UI_SPEC.md` before you touch a screen (where a plan file and
   UI_SPEC disagree about a screen, UI_SPEC wins).

Do **not** read `docs/history/`, `plan.md`, `docs/PROGRESS.md`, `HANDOFF.md` or `worklog.md` unless a plan item names a line.
They are huge and out of date; they exist for the owner.

## The ten rules
1. **STATUS first, STATUS last.** Rewrite it (do not append) at the end of every session. Under 150 lines.
2. **One concern per commit and push.** Commit message `<ITEM-ID>: <what and why>` (e.g. `C-02: advance the test clock`).
   After every push read the CI digest before the next change (see below).
3. **Exact-match edits.** The old text must occur exactly once, otherwise stop and report. Never edit with shell
   heredocs. After every edit re-read the file and grep for the new text. **Keep each file's own line endings** (14 files
   are CRLF: check before you edit; `tools/m0_reset.py --check-eol` lists them).
4. **No scope you were not given.** Anything else you notice goes under "Broken" in STATUS. Frozen modules are touched
   only to keep the build green.
5. **"Done" means evidence:** a CI run link and test names, or the owner's screenshot. "Unverified" is a good word.
6. **Fail closed.** No secrets in logs, events, exports or prompts. The canary-secret test stays green.
7. **Boundaries:** no CAPTCHA solving, no stealth or identity spoofing, no credential harvesting; a challenge pauses the
   run (`WAITING_USER`). AI features stay off by default. Never weaken the cleartext rule (localhost only).
8. **One broker, one action path; no app-specific code** outside `samples/` and `testing/`. New code follows
   `docs/CONVENTIONS.md`: pure state holders/policies with JVM tests, `Clock`/`Ids` (never `System.currentTimeMillis`
   or `Random`), user text in `strings.xml`, `runBlocking` only at process entry points, rethrow
   `CancellationException`, fakes over mocks.
9. **The same failure twice means stop and report.** At most 3 CI cycles on one problem.
10. **Report format:** `Done / Blocked / Needs you / Files touched / Next session`, plain language, at most 25 lines.

## The user is not a developer
Never show an ordinary user JSON, a regex, a package name, an id, a port or a token. Those live only under
Settings → Developer. Failure text comes from `schemas/errors.json`, never from exception messages.

## Commands
- Python (run these yourself when you touch Python): `scripts/test-python.sh`, or
  `cd sdk-python && python3 -m unittest discover -s tests -t tests -p "test_*.py"` and
  `python3 -m unittest discover -s tests -p "test_*.py"` from the repo root.
- After editing anything under `sdk-python/autohost/`: `python3 tools/sync_sdk.py` (the worker copy under
  `runtime/python/` must stay byte-identical; CI checks it).
- Kotlin cannot be built where most agents run. **CI is the compiler.** Write conservative Kotlin that copies existing
  patterns; before changing a function or type, search the whole repo for every call site and update them in the same change.
- CI results: after each push, `git fetch origin ci-results && git show origin/ci-results:latest.md`. Its first line
  names the branch and commit — wait until it matches yours. Compile errors, failing tests, hung tests and modules with
  no results are listed there.
- Unit-test task list: `scripts/unit-test-tasks.txt` (every module exactly once; `tests/test_ci_task_list.py` guards it).

## Frozen ("Labs") until the golden path is proven
Ask/agent runtime, Task Studio, Recorder, plugins/app packs, webhooks, Dev API/MCP, reverse bridge, browser, vision, and
everything in the `full` flavor. Keep them compiling and their tests in CI; do not extend them.
