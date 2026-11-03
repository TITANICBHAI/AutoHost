#!/usr/bin/env python3
"""Doc integrity check (v4). Stdlib only; run from the repo root (or pass --root).

Rules (each finding names file:line):
  paths   every `docs|prompts|schemas|tools|scripts|drafts/NAME.ext` mentioned in the checked docs exists
          (deliberate exceptions live in KNOWN_MISSING with a reason)
  gates   every `GATES.md#gN-x` link has a `### GN-x` heading in docs/GATES.md
  tasks   every task/gate id (P12-01, X-09, G13-a) mentioned exists as **ID** in docs/PROGRESS.md
  adr     every ADR-NNN mentioned exists as a heading or table row in docs/DECISIONS.md
  rm      every RM-A1-style id mentioned exists as a row in docs/REUSE_MATRIX.md
  index   every docs/*.md is listed in docs/README.md

It checks *references*, not truth: a green run says the documents agree on names, not that the claims are right.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# path -> reason. Keep this list short and honest.
KNOWN_MISSING: dict[str, str] = {
    "docs/SDK.md": "generated documentation named by AGENTS.md rule 'do not hand-edit generated files'; not present in this checkout",
    "schemas/capabilities.json": "named as a generator output in docs/REPO_LAYOUT.md; not present in the attached tree (pre-existing drift, docs/MIGRATION.md)",
    "scripts/spake2_ref.py": "named by the owner's HANDOFF/DECISIONS/PROGRESS (ADR-065); not present in the attached tree (pre-existing drift, docs/MIGRATION.md)",
    "docs/triggers.md": "upstream path inside a reference project, quoted in docs/REFERENCES.md section 2",
    "docs/local-api.md": "upstream path inside a reference project, quoted in docs/REFERENCES.md section 2",
    "docs/reverse-connection.md": "upstream path inside a reference project, quoted in docs/REFERENCES.md section 2",
}

PATH_RE = re.compile(r"(?<![\w/.\-])((?:docs|prompts|schemas|tools|scripts|drafts)/[A-Za-z0-9_.\-]+\.(?:md|json|py|sh|html))")
GATE_LINK_RE = re.compile(r"GATES\.md#(g\d+-[a-z])\b")
TASK_RE = re.compile(r"(?<![\w-])(P\d+-\d+|X-\d+|G\d+-[a-z])(?![\w])")
ADR_RE = re.compile(r"\bADR-(\d{3})\b")
RM_RE = re.compile(r"\bRM-[A-Z]\d+\b")


def checked_files(root: Path) -> list[Path]:
    out: list[Path] = []
    for name in ("plan.md", "AGENTS.md", "HANDOFF.md", "KICKOFF.md", "README.md", "THIRD_PARTY_NOTICES.md"):
        p = root / name
        if p.is_file():
            out.append(p)
    for d in ("docs", "prompts", "verify"):
        out.extend(sorted((root / d).glob("*.md")))
    for name in ("README.md", "INDEX.md", "SNAPSHOT.md"):
        p = root / "reference" / name
        if p.is_file():
            out.append(p)
    return out


def defined_tasks(progress: str) -> set[str]:
    return set(re.findall(r"\*\*((?:P\d+-\d+)|(?:X-\d+)|(?:G\d+-[a-z]))\*\*", progress))


def defined_adrs(decisions: str) -> set[str]:
    heads = set(re.findall(r"^#{2,4}\s*ADR-(\d{3})\b", decisions, re.M))
    rows = set(re.findall(r"^\|\s*ADR-(\d{3})\s*\|", decisions, re.M))
    return heads | rows


def defined_gates(gates: str) -> set[str]:
    return {m.lower() for m in re.findall(r"^###\s+(G\d+-[a-z])\b", gates, re.M)}


def defined_rms(matrix: str) -> set[str]:
    return set(re.findall(r"^\|\s*(RM-[A-Z]\d+)\s*\|", matrix, re.M))


def check(root: Path) -> list[str]:
    findings: list[str] = []
    read = lambda rel: (root / rel).read_text(encoding="utf-8") if (root / rel).is_file() else ""  # noqa: E731
    tasks = defined_tasks(read("docs/PROGRESS.md"))
    adrs = defined_adrs(read("docs/DECISIONS.md"))
    gates = defined_gates(read("docs/GATES.md"))
    rms = defined_rms(read("docs/REUSE_MATRIX.md"))
    files = checked_files(root)
    for f in files:
        rel = f.relative_to(root).as_posix()
        for n, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            for m in PATH_RE.finditer(line):
                p = m.group(1)
                if any(c in p for c in "*<>{}"):
                    continue
                if not (root / p).is_file() and p not in KNOWN_MISSING:
                    findings.append(f"paths  {rel}:{n}: `{p}` does not exist")
            for m in GATE_LINK_RE.finditer(line):
                if m.group(1).lower() not in gates:
                    findings.append(f"gates  {rel}:{n}: no `### G{m.group(1)[1:]}` heading in docs/GATES.md")
            if tasks:
                for m in TASK_RE.finditer(line):
                    if m.group(1) not in tasks:
                        findings.append(f"tasks  {rel}:{n}: `{m.group(1)}` is not defined in docs/PROGRESS.md")
            if adrs:
                for m in ADR_RE.finditer(line):
                    if m.group(1) not in adrs:
                        findings.append(f"adr    {rel}:{n}: ADR-{m.group(1)} is not defined in docs/DECISIONS.md")
            if rms:
                for m in RM_RE.finditer(line):
                    if m.group(0) not in rms:
                        findings.append(f"rm     {rel}:{n}: {m.group(0)} is not a row in docs/REUSE_MATRIX.md")
    index = read("docs/README.md")
    for p in sorted((root / "docs").glob("*.md")):
        if p.name != "README.md" and f"`{p.name}`" not in index:
            findings.append(f"index  docs/README.md: `{p.name}` is not listed")
    return findings


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    args = ap.parse_args(argv)
    root = Path(args.root)
    findings = check(root)
    if findings:
        print(f"doc_check: {len(findings)} finding(s)")
        for line in findings:
            print("  " + line)
        return 1
    print(f"doc_check: ok ({len(checked_files(root))} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
