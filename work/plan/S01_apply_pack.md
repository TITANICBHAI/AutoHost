# S01 — Apply the starter pack (reset + new CI + verified fixes). No new Kotlin logic.
Branch `work/s01-pack`. Everything you apply here was checked on a clean copy of the repo (Python side) or is a few-line edit.

## Do (Path A — you have a git CLI that can push)
1. `git checkout -b work/s01-pack`
2. `cp work/ready/tools/m0_reset.py tools/ && python3 tools/m0_reset.py` — **dry run**; read the list.
3. `python3 tools/m0_reset.py --apply --mode move --ready work/ready` (use `--mode delete` if `git mv` is unavailable).
   It moves 25 retired docs to `docs/history/`, deletes `archive/`, `verify/`, `.agents/memory/`, `docs/LOCAL_INFERENCE.md`,
   `prompts/phase-16.md`, retires `tools/progress.py`, `tools/doc_check.py` (+ their tests), edits `ci.yml`, `scripts/test-python.sh`,
   `docs/CONVENTIONS.md`, and copies `AGENTS.md`, `README.md`, `STATUS.md`, the new workflow, task list, digest tool, tests and vectors.
   Commit: `M0: reset process documents`.
4. Apply the patches **in order**, one commit each (`git apply --check` first; message = the patch name): `P01_C02…`, `P02_C03…`,
   `P03_C04…`, `P04_C05…`, `P05_C01…`, `P06_P01_P02…`.
5. Run `scripts/test-python.sh` (or the two `unittest discover` commands in AGENTS.md). **Expect:** SDK suite 436 tests OK, root suite 69 tests OK,
   `gen_artifacts --check`, `sync_sdk --check`, `licence_check`, `mcp_eval` OK. Different numbers → stop and report.
6. Line endings: `python3 tools/m0_reset.py --check-eol` lists CRLF files — write the list in STATUS ("edit with care"). Then
   `python3 tools/gen_flow_vectors.py && git diff --ignore-space-at-eol --stat testing/golden/flow_vectors.json`: if empty but plain `git diff` is not,
   the committed file is CRLF and the generator writes LF (ci.yml's drift step would show a diff). Re-run with
   `python3 tools/m0_reset.py --apply --normalize-golden` and commit `testing/golden/*.json` as `M0: LF golden vectors`.
7. Push, open the PR. Do **not** delete `jvm-core.yml` / `jvm-capabilities.yml` yet (S02 does, after one digest exists).
8. STATUS.md: set Now = S02; paste today's test numbers under "Works".

## Do (Path B — connector only, no git CLI)
- In your sandbox: fetch the repo (public: GitHub zipball; private: fetch files with the connector), `git init`, commit it as baseline, then run steps 2–6
  there (`git apply` works in the sandbox). Upload the **changed/new files** back with the connector in the same commits as Path A.
- **`archive/` (≈1,200 files):** do not delete with per-file calls. Tell the owner: *open the repo, press `.` (github.dev), delete the folders
  `archive` and `verify`, commit to `work/s01-pack`.* Do everything else.
- Docs: use `--mode delete` and delete the ~25 retired docs with the connector's delete call (moving 600 KB of text through your context is not worth it).
- **Workflow files:** GitHub can reject pushes that add/change `.github/workflows/*` without the `workflow` permission **[from memory]**. If rejected, do not retry:
  put `work/ready/.github/workflows/jvm-tests.yml` in your report and ask the owner to add it in the web UI (*Add file → Create new file*, path `.github/workflows/jvm-tests.yml`).
- If the digest push later fails with 403: repo *Settings → Actions → General → Workflow permissions → Read and write*.

## Gate
Python suites pass with the numbers above; PR open; the new `jvm-tests` workflow has started (its result is S02's input). If you cannot read `ci-results`, ask the owner to
paste the run's Summary text.

## Stop and report if
`git apply --check` fails for any patch (paste the error), a Python suite fails, or `m0_reset.py` reports a path that does not exist.
