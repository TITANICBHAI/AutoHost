#!/usr/bin/env python3
"""Documentation and migration consistency checks (stdlib only).

  python tools/check_docs.py            # exit 1 on errors
  python tools/check_docs.py --root DIR # check another checkout

Checks
  1. every task/gate/cross-cutting id (P1-05, G3-b, X-02), ADR-nnn, D-nn and R-nn mentioned in a document exists
  2. relative markdown links resolve
  3. backticked paths under docs/ schemas/ tools/ prompts/ samples/ tests/ reference/ .github/, top-level files and
     any */imported/** path exist (planned/generated artifacts are allow-listed below)
  4. docs/migration-manifest.tsv: every `staged` file exists and matches its sha256 and the pristine copy under
     reference/; `integrated` rows name an existing destination (ARIA rows only once D-19 is CONFIRMED); `dropped` rows carry a reason
  5. settings.gradle.kts never includes reference/ or imported/ code
  6. stale claims that earlier plan versions made (see STALE) do not reappear unless the line says superseded/historical
  7. (warning) raw "N tests" counts in README.md, AGENTS.md and docs/README.md (counts live in PROGRESS.md evidence and VALIDATION_REPORT.md)
"""
import argparse, hashlib, pathlib, re, sys

SKIP_DIRS = {".git", "reference", "node_modules", "build", ".gradle", "__pycache__"}
CHECK_PREFIXES = ("docs/", "schemas/", "tools/", "prompts/", "samples/", "tests/", "reference/", ".github/")
TOP_FILES = {"plan.md", "AGENTS.md", "HANDOFF.md", "README.md", "THIRD_PARTY_NOTICES.md", "LICENSE",
             "settings.gradle.kts", "build.gradle.kts", "gradle.properties"}
# Planned or generated artifacts that documents may name before they exist.
PLANNED = {"docs/CAPABILITIES.md", "docs/SDK.md", "docs/PERF.md", "docs/SECURITY.md", "docs/USER_GUIDE.md",
           "schemas/capabilities.json", "tools/soak_report", "docs/evidence", "SECURITY.md", "docs/capabilities.json",
           "tests/mcp-evals", "docs/progress.html"}
HISTORICAL = {"docs/DECISIONS.md", "docs/VALIDATION_REPORT.md", "docs/MIGRATION_NOTES.md", "docs/REUSE_MATRIX.md",
              "docs/REFERENCES.md", "worklog.md"}
HISTORY_MARK = re.compile(r"superseded|historical|\bwas\b.{0,20}\bbefore\b|replaces", re.I)
STALE = ["No production code before this gate", "write all code first", "No production modules exist yet",
         "We host scripts, not LLM loops", "There are no further code phases"]
COUNT_CHECKED = {"README.md", "AGENTS.md", "docs/README.md"}
ID_RE = re.compile(r"\b(P\d+-\d+|G\d+-[a-z]|X-\d+)\b")
ADR_RE, D_RE, R_RE = re.compile(r"\bADR-\d{3}\b"), re.compile(r"\bD-\d{2}\b"), re.compile(r"\bR-\d{2}\b")
LINK_RE = re.compile(r"\[[^\]\n]*\]\(([^)\s]+)\)")
TICK_RE = re.compile(r"`([^`\n]+)`")
COUNT_RE = re.compile(r"\b\d+\s+(?:python\s+|sdk\s+|schema\s+|unit\s+|local\s+)?tests?\b", re.I)


def md_files(root):
    for p in sorted(root.rglob("*.md")):
        rel = p.relative_to(root)
        if any(part in SKIP_DIRS for part in rel.parts) or "imported" in rel.parts:
            continue
        yield p, rel.as_posix()


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def tracker_ids(root):
    p = root / "docs" / "PROGRESS.md"
    if not p.exists():
        return set()
    return set(re.findall(r"^- \[[ xX]\] \*\*([^*]+)\*\*", p.read_text(encoding="utf-8"), re.M))


def defined(root, rel, rx):
    p = root / rel
    return set(rx.findall(p.read_text(encoding="utf-8"))) if p.exists() else set()


def aria_locked(root):
    p = root / "docs" / "DECISIONS.md"
    if not p.exists():
        return True
    for line in p.read_text(encoding="utf-8").splitlines():
        if line.startswith("| D-19 "):
            return "UNCONFIRMED" in line
    return True


def check_manifest(root, errors, warns):
    mp = root / "docs" / "migration-manifest.tsv"
    if not mp.exists():
        errors.append("docs/migration-manifest.tsv is missing")
        return 0
    rows = [l.rstrip("\n").split("\t") for l in mp.read_text(encoding="utf-8").splitlines() if l.strip()]
    head, rows = rows[0], rows[1:]
    want = ["id", "status", "repo", "source_path", "staged_path", "sha256", "matrix_row", "notes"]
    if head != want:
        errors.append(f"migration-manifest.tsv header must be {want}")
        return 0
    ids = set()
    for n, r in enumerate(rows, 2):
        if len(r) != len(want):
            errors.append(f"migration-manifest.tsv line {n}: expected {len(want)} columns, got {len(r)}")
            continue
        d = dict(zip(want, r))
        if d["id"] in ids:
            errors.append(f"migration-manifest.tsv: duplicate id {d['id']}")
        ids.add(d["id"])
        st = d["status"]
        pristine = root / "reference" / d["repo"] / d["source_path"]
        if st == "staged":
            f = root / d["staged_path"]
            if not f.is_file():
                errors.append(f"{d['id']}: staged file missing: {d['staged_path']}")
            elif sha256(f) != d["sha256"]:
                errors.append(f"{d['id']}: staged file differs from recorded sha256 (edited in place?): {d['staged_path']}")
            if not pristine.is_file():
                errors.append(f"{d['id']}: pristine source missing under reference/: {d['repo']}/{d['source_path']}")
            elif sha256(pristine) != d["sha256"]:
                errors.append(f"{d['id']}: pristine copy no longer matches recorded sha256: {d['repo']}/{d['source_path']}")
        elif st == "integrated":
            if not d["staged_path"] or not (root / d["staged_path"]).exists():
                errors.append(f"{d['id']}: integrated row must name an existing destination in staged_path")
            if d["repo"] == "aria" and aria_locked(root):
                errors.append(f"{d['id']}: ARIA files may not be integrated while D-19 says UNCONFIRMED (docs/DECISIONS.md, ADR-031)")
        elif st == "dropped":
            if not d["notes"].strip():
                errors.append(f"{d['id']}: dropped rows need a reason in notes")
        else:
            errors.append(f"{d['id']}: unknown status '{st}' (staged|integrated|dropped)")
    return len(rows)


def check_gradle(root, errors):
    s = root / "settings.gradle.kts"
    if not s.exists():
        return
    for m in re.finditer(r'include\(([^)]*)\)', s.read_text(encoding="utf-8")):
        for tok in re.findall(r'"([^"]+)"', m.group(1)):
            if "reference" in tok or "imported" in tok:
                errors.append(f"settings.gradle.kts includes '{tok}': reference/ and imported/ code must never be built")


def run(root):
    errors, warns = [], []
    ids = tracker_ids(root)
    adrs = defined(root, "docs/DECISIONS.md", ADR_RE)
    ds = defined(root, "docs/DECISIONS.md", D_RE)
    rs = defined(root, "docs/REUSE_MATRIX.md", R_RE)
    nfiles = 0
    for p, rel in md_files(root):
        nfiles += 1
        text = p.read_text(encoding="utf-8", errors="replace")
        # 1. ids
        if rel != "docs/PROGRESS.md":
            for tok in sorted(set(ID_RE.findall(text))):
                if ids and tok not in ids:
                    errors.append(f"{rel}: unknown tracker id {tok}")
        if rel != "docs/DECISIONS.md":
            for tok in sorted(set(ADR_RE.findall(text))):
                if tok not in adrs:
                    errors.append(f"{rel}: unknown {tok}")
            for tok in sorted(set(D_RE.findall(text))):
                if tok not in ds:
                    errors.append(f"{rel}: unknown {tok}")
        if rel not in ("docs/REUSE_MATRIX.md", "docs/MIGRATION_NOTES.md"):
            for tok in sorted(set(R_RE.findall(text))):
                if rs and tok not in rs:
                    errors.append(f"{rel}: unknown reuse-matrix row {tok}")
        # 2. links
        for target in LINK_RE.findall(text):
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            t = target.split("#")[0]
            if t and not (p.parent / t).exists():
                errors.append(f"{rel}: broken link {target}")
        # 3. backticked paths (worklog.md is a historical log of past sessions: its paths are not maintained)
        for tok in ([] if rel == "worklog.md" else TICK_RE.findall(text)):
            t = tok.strip().rstrip(".,:;)")
            if not t or re.search(r"[\s*<>{}$|()…\[\]]", t) or t.startswith(("http", "/")):
                continue
            t = t[2:] if t.startswith("./") else t
            t = t.split("#")[0]
            if not t:
                continue
            last = t.rstrip("/").rsplit("/", 1)[-1]
            checkable = (t.startswith(CHECK_PREFIXES) and ("." in last or t.endswith("/"))) or t in TOP_FILES or "/imported/" in t
            if not checkable or t in PLANNED or t.startswith("docs/evidence"):
                continue
            if not (root / t.rstrip("/")).exists():
                errors.append(f"{rel}: path does not exist: {t}")
        # 6. stale claims
        if rel not in HISTORICAL:
            for phrase in STALE:
                for line in text.splitlines():
                    if phrase.lower() in line.lower() and not HISTORY_MARK.search(line):
                        errors.append(f"{rel}: stale claim '{phrase}' (mark it historical/superseded or remove it; see docs/DECISIONS.md)")
                        break
        # 7. raw test counts
        if rel in COUNT_CHECKED and COUNT_RE.search(text):
            warns.append(f"{rel}: raw test count '{COUNT_RE.search(text).group(0)}' — keep counts in PROGRESS.md evidence / VALIDATION_REPORT.md only")
    nrows = check_manifest(root, errors, warns)
    check_gradle(root, errors)
    return errors, warns, nfiles, nrows


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    a = ap.parse_args(argv)
    errors, warns, nfiles, nrows = run(pathlib.Path(a.root).resolve())
    for w in warns:
        print("WARN:", w)
    for e in errors:
        print("ERROR:", e)
    print(f"docs check: {'FAILED' if errors else 'ok'} ({nfiles} documents, {nrows} manifest rows, {len(warns)} warnings)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
