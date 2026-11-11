#!/usr/bin/env python3
"""One short, plain-text summary of a CI test run, written for a reader that cannot open the Actions UI.

Reads the Gradle console log and the JUnit XML reports and prints markdown of at most MAX_LINES lines:
the verdict, Kotlin compile errors (the usual reason a blind change fails), failed Gradle tasks, failing tests
with their first message line, the last tests that STARTED when a task timed out (the hung test), and which
modules produced no results at all. The workflow commits the output to the `ci-results` branch.

  python3 tools/ci_digest.py --log gradle.log --root . --tasks scripts/unit-test-tasks.txt \
      --run 12 --branch work/s3-green --sha 1a2b3c4 --outcome failure
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_LINES = 150
MAX_COMPILE_ERRORS = 40
MAX_FAILED_TASKS = 25
MAX_FAILING_TESTS = 40
MAX_STARTED_LINES = 10
LINE_CUT = 220

_PATH_PREFIX = re.compile(r"file:///.*?/work/[^/]+/[^/]+/")


def short(text: str, limit: int = LINE_CUT) -> str:
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def kotlin_errors(log: str) -> list:
    out = []
    for line in log.splitlines():
        if line.startswith("e: "):
            out.append(short(_PATH_PREFIX.sub("", line[3:])))
    return out


def failed_tasks(log: str) -> list:
    return [m.group(1) for m in re.finditer(r"^> Task (\S+) FAILED$", log, re.MULTILINE)]


def timed_out(log: str) -> bool:
    return "Timeout has been exceeded" in log or "timed out" in log.lower().split("what went wrong", 1)[-1][:2000]


def started_tail(log: str) -> list:
    started = [ln.strip() for ln in log.splitlines() if ln.rstrip().endswith(" STARTED")]
    return started[-MAX_STARTED_LINES:]


def module_of(xml_path: Path, root: Path) -> str:
    rel = xml_path.relative_to(root).as_posix()
    return rel.split("/build/", 1)[0]


def parse_reports(root: Path):
    """-> (per_module {module: [tests, failures, skipped]}, failing [(module, 'Class.name', message)])"""
    per_module, failing = {}, []
    for xml_path in sorted(root.glob("**/build/test-results/**/TEST-*.xml")):
        try:
            suite = ET.parse(xml_path).getroot()
        except ET.ParseError:
            continue
        module = module_of(xml_path, root)
        row = per_module.setdefault(module, [0, 0, 0])
        for case in suite.iter("testcase"):
            row[0] += 1
            if case.find("skipped") is not None:
                row[2] += 1
            bad = case.find("failure")
            if bad is None:
                bad = case.find("error")
            if bad is not None:
                row[1] += 1
                message = (bad.get("message") or (bad.text or "").strip().splitlines()[0:1] or [""])
                message = message if isinstance(message, str) else message[0]
                failing.append((module, f'{case.get("classname", "?").rsplit(".", 1)[-1]}.{case.get("name", "?")}', message))
    return per_module, failing


def modules_expected(tasks_file: Path | None) -> list:
    if not tasks_file or not tasks_file.exists():
        return []
    modules = []
    for line in tasks_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith(":"):
            modules.append(line.rsplit(":", 1)[0].strip(":").replace(":", "/"))
    return modules


def went_wrong_blocks(log: str, max_lines: int = 14) -> list:
    """Gradle's own '* What went wrong:' text for every failed task (the reason a task failed or timed out)."""
    blocks = []
    for m in re.finditer(r"^\* What went wrong:\n(.*?)(?=^\* Try:|^\* Exception is:|\Z)", log, re.MULTILINE | re.DOTALL):
        text = [ln.rstrip() for ln in m.group(1).strip().splitlines() if ln.strip()]
        blocks.append(text[:max_lines])
    return blocks


def render_details(log: str, per_module: dict, failing: list) -> str:
    """The long form that does not fit the 150-line digest: EVERY compile error, every failing test, every Gradle reason."""
    errors = sorted(set(kotlin_errors(log)))
    lines = [f"# CI details - {len(errors)} distinct Kotlin compile errors, {len(failing)} failing tests", ""]
    lines += ["## All Kotlin compile errors (sorted, de-duplicated)"] + [f"- {e}" for e in errors]
    lines += ["", "## All failing tests"] + [f"- [{m}] {name} - {short(msg, 400)}" for m, name, msg in failing]
    lines += ["", "## Gradle 'What went wrong' blocks"]
    for i, block in enumerate(went_wrong_blocks(log), 1):
        lines += [f"### {i}"] + [f"    {ln}" for ln in block]
    return "\n".join(lines) + "\n"


def render(log: str, per_module: dict, failing: list, expected: list, header: dict) -> str:
    errors = kotlin_errors(log)
    tasks = failed_tasks(log)
    tests = sum(r[0] for r in per_module.values())
    bad = sum(r[1] for r in per_module.values())
    silent = [m for m in expected if m not in per_module]
    red = bool(errors or tasks or bad or header.get("outcome") == "failure")
    lines = [
        f"# CI digest - run {header.get('run', '?')} - branch {header.get('branch', '?')} - commit {header.get('sha', '?')}",
        f"tests step outcome: {header.get('outcome', '?')}",
        "",
        f"## Verdict: {'RED' if red else 'GREEN'} - {tests} tests ran, {bad} failed, "
        f"{len(errors)} Kotlin compile errors, {len(tasks)} failed Gradle tasks, {len(silent)} modules with no results",
    ]
    if errors:
        lines += ["", f"## Kotlin compile errors (first {MAX_COMPILE_ERRORS} of {len(errors)})"]
        lines += [f"- {e}" for e in errors[:MAX_COMPILE_ERRORS]]
    if tasks:
        lines += ["", "## Failed Gradle tasks"]
        lines += [f"- {t}" for t in tasks[:MAX_FAILED_TASKS]]
    if failing:
        lines += ["", f"## Failing tests (first {MAX_FAILING_TESTS} of {len(failing)})"]
        lines += [f"- [{m}] {name} - {short(msg)}" for m, name, msg in failing[:MAX_FAILING_TESTS]]
    if timed_out(log):
        lines += ["", "## A task TIMED OUT - the last tests that started (the last one is the hung test)"]
        lines += [f"- {s}" for s in started_tail(log)] or ["- (no STARTED lines in the log)"]
    if silent:
        lines += ["", "## Modules that produced no test results (compile error, timeout, or no tests)"]
        lines += [f"- {m}" for m in silent]
    if per_module:
        lines += ["", "## Per module (tests / failed / skipped)"]
        lines += [f"- {m}: {r[0]} / {r[1]} / {r[2]}" for m, r in sorted(per_module.items())]
    if len(lines) > MAX_LINES:
        lines = lines[: MAX_LINES - 1] + [f"... digest cut at {MAX_LINES} lines"]
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--log", default="")
    p.add_argument("--root", default=".")
    p.add_argument("--tasks", default="")
    p.add_argument("--run", default="?")
    p.add_argument("--branch", default="?")
    p.add_argument("--sha", default="?")
    p.add_argument("--outcome", default="?")
    p.add_argument("--details-out", default="", help="also write the long form (all errors, all failing tests) here")
    args = p.parse_args(argv)
    log = Path(args.log).read_text(encoding="utf-8", errors="replace") if args.log and Path(args.log).exists() else ""
    root = Path(args.root).resolve()
    per_module, failing = parse_reports(root)
    expected = modules_expected(Path(args.tasks) if args.tasks else None)
    if args.details_out:
        Path(args.details_out).write_text(render_details(log, per_module, failing), encoding="utf-8")
    sys.stdout.write(render(log, per_module, failing, expected,
                            {"run": args.run, "branch": args.branch, "sha": args.sha, "outcome": args.outcome}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
