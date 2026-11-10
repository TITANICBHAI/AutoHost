#!/usr/bin/env python3
"""Session 1 / milestone M0: reset the repo's process documents so an agent reads ~300 lines, not 11,000.

  python3 tools/m0_reset.py                       dry run: print what would change
  python3 tools/m0_reset.py --apply               do it (docs are MOVED to docs/history/)
  python3 tools/m0_reset.py --apply --mode delete delete the retired docs instead (they stay in Git history)
  python3 tools/m0_reset.py --apply --ready DIR   also copy every file under DIR into the repo (the starter pack)
  python3 tools/m0_reset.py --check-eol           list files that use CRLF line endings (edit those with care)
  python3 tools/m0_reset.py --apply --normalize-golden   regenerate the golden JSON vectors with LF endings

It never touches Kotlin, Gradle or the code under src/. Run it from the repository root.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT_DOCS = [
    "CRASH_FIX_PLAN.md", "HANDOFF.md", "worklog.md", "KICKOFF.md", "KICKOFF_PROMPT.md", "READ_FIRST.md",
    "AGENT_OPERATING_PROTOCOL.md", "AGENT_STATE.md", "AGENT_STATE_TEMPLATE.md", "WORK_SEQUENCE_PROMPT.md",
    "rework.md", "rework 2.md", "left.md", "need to.md", "ci report.md",
]
DOCS_DOCS = [
    "docs/PROGRESS.md", "docs/progress.html", "docs/GATES.md", "docs/PLAN_MERGE.md", "docs/MIGRATION.md",
    "docs/VALIDATION_REPORT.md", "docs/PROACTIVE-CRASH-REVIEW.md", "docs/PORT_EXERCISE.md", "docs/REFERENCES.md",
    "docs/AGENT_OPERATING_PROTOCOL.md",
]
# kept on purpose: docs/REUSE_MATRIX.md (licence ledger that source headers point to), docs/DECISIONS.md, UI_SPEC, CONVENTIONS
DELETE_ALWAYS = ["archive", "verify", ".agents/memory", "docs/LOCAL_INFERENCE.md", "prompts/phase-16.md"]
RETIRE_TOOLS = ["tools/progress.py", "tools/doc_check.py", "tests/test_progress.py", "tests/test_doc_check.py"]
NEVER_TOUCH_SUFFIXES = (".kt", ".kts")
GOLDEN = [("tools/gen_flow_vectors.py", "testing/golden/flow_vectors.json"),
          ("tools/gen_schedule_vectors.py", "testing/golden/schedule_vectors.json")]


class Plan:
    def __init__(self, apply: bool, mode: str):
        self.apply, self.mode = apply, mode
        self.is_git = Path(".git").exists()
        self.log = []

    def say(self, text):
        self.log.append(text)
        print(("DO  " if self.apply else "plan ") + text)

    def git(self, *args):
        subprocess.run(["git", *args], check=True)

    def remove(self, rel):
        path = Path(rel)
        if not path.exists():
            return
        self.say(f"delete {rel}")
        if not self.apply:
            return
        if self.is_git:
            subprocess.run(["git", "rm", "-r", "-q", "-f", "--ignore-unmatch", rel], check=False)
        if path.exists():
            shutil.rmtree(path) if path.is_dir() else path.unlink()

    def retire_doc(self, rel, bucket):
        path = Path(rel)
        if not path.exists():
            return
        if self.mode == "delete":
            return self.remove(rel)
        target = Path("docs/history") / bucket / path.name
        self.say(f"move   {rel} -> {target}")
        if not self.apply:
            return
        target.parent.mkdir(parents=True, exist_ok=True)
        if self.is_git:
            self.git("mv", rel, str(target))
        else:
            shutil.move(rel, target)

    def edit_lines(self, rel, rules):
        """rules: list of (predicate(line)->bool, replacement or None to delete). Preserves the file's newline style."""
        path = Path(rel)
        if not path.exists():
            return
        raw = path.read_bytes()
        crlf = b"\r\n" in raw
        text = raw.decode("utf-8").replace("\r\n", "\n")
        out, changed = [], 0
        for line in text.split("\n"):
            for matches, replacement in rules:
                if matches(line):
                    changed += 1
                    if replacement is not None:
                        out.append(replacement(line) if callable(replacement) else replacement)
                    break
            else:
                out.append(line)
        if changed:
            self.say(f"edit   {rel} ({changed} line(s))")
            if self.apply:
                data = "\n".join(out)
                path.write_bytes((data.replace("\n", "\r\n") if crlf else data).encode("utf-8"))


def check_eol():
    found = []
    for dirpath, dirnames, filenames in os.walk("."):
        dirnames[:] = [d for d in dirnames if d not in (".git", "archive", "build", ".gradle", "node_modules")]
        for name in filenames:
            p = Path(dirpath) / name
            try:
                if b"\r\n" in p.read_bytes():
                    found.append(p.as_posix())
            except OSError:
                pass
    print("\n".join(sorted(found)) or "(no CRLF files)")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--mode", choices=["move", "delete"], default="move")
    ap.add_argument("--ready", default="")
    ap.add_argument("--check-eol", action="store_true")
    ap.add_argument("--normalize-golden", action="store_true")
    args = ap.parse_args(argv)
    if not (Path("settings.gradle.kts").exists() and Path("plan.md").exists()):
        print("run this from the AutoHost repository root", file=sys.stderr)
        return 2
    if args.check_eol:
        return check_eol()

    plan = Plan(args.apply, args.mode)
    for rel in ROOT_DOCS:
        plan.retire_doc(rel, "root")
    for rel in DOCS_DOCS:
        plan.retire_doc(rel, "docs")
    for rel in DELETE_ALWAYS:
        plan.remove(rel)
    for rel in RETIRE_TOOLS:
        plan.remove(rel)
    for pyc in list(Path(".").rglob("__pycache__")):
        if "archive" not in pyc.parts:
            plan.say(f"delete {pyc.as_posix()}")
            if args.apply:
                shutil.rmtree(pyc, ignore_errors=True)

    # the two commands that called the retired tools
    plan.edit_lines(".github/workflows/ci.yml", [
        (lambda l: l.strip() == "python3 tools/progress.py --check", None),
        (lambda l: l.strip() == "python3 tools/doc_check.py", None),
    ])
    plan.edit_lines("scripts/test-python.sh", [
        (lambda l: l.strip() == 'run "$PYTHON_BIN" tools/progress.py --check', None),
        (lambda l: l.strip() == 'run "$PYTHON_BIN" tools/doc_check.py', None),
    ])
    plan.edit_lines("docs/CONVENTIONS.md", [
        (lambda l: l.startswith("- Branch per phase ("),
         "- Branch per session (`work/s2-ci`). Commit `<ITEM-ID>: <summary>` (plan item id first, e.g. `C-02`). "
         "PR description lists item ids, evidence (CI run link), risks."),
        (lambda l: "update `plan.md` or docs in the same commit" in l,
         lambda l: l.replace("update `plan.md` or docs in the same commit", "update the relevant doc in the same commit")),
        (lambda l: l.startswith("4. **One state tag after a tracker ID**"),
         "4. **(Retired.)** Tracker state tags went away with the tracker; `STATUS.md` is the only state file."),
        (lambda l: l.startswith("13. **Docs stay consistent:**"),
         "13. **(Retired.)** `tools/doc_check.py` was removed; keep docs consistent by hand and by reading the CI digest."),
    ])
    gi = Path(".gitignore")
    if gi.exists():
        body = gi.read_text(encoding="utf-8")
        missing = [x for x in ("__pycache__/", "*.pyc") if x not in body]
        if missing:
            plan.say(f"edit   .gitignore (+{', '.join(missing)})")
            if args.apply:
                gi.write_text(body.rstrip("\n") + "\n" + "\n".join(missing) + "\n", encoding="utf-8")

    if args.ready:
        src = Path(args.ready)
        protected = {"AGENTS.md", "README.md", "STATUS.md"}
        for path in sorted(p for p in src.rglob("*") if p.is_file()):
            rel = path.relative_to(src)
            dest = Path(rel)
            if dest.exists() and rel.as_posix() not in protected and dest.read_bytes() != path.read_bytes():
                plan.say(f"SKIP   {rel} already exists and differs (left alone)")
                continue
            plan.say(f"copy   {rel}")
            if args.apply:
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, dest)

    if args.normalize_golden:
        for tool, out in GOLDEN:
            plan.say(f"regenerate {out} (LF endings)")
            if args.apply:
                subprocess.run([sys.executable, tool], check=True, stdout=subprocess.DEVNULL)

    if not args.apply:
        print("\n(dry run — nothing changed. Re-run with --apply.)")
    else:
        print("\nDone. Next: run scripts/test-python.sh, then `git add -A && git commit -m \"M0: reset process documents\"`.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
