#!/usr/bin/env python3
"""Licence CI check (P11-09, Gate 11-e) — every third-party dependency in the version catalog
must be covered by THIRD_PARTY_NOTICES.md (a row in the coverage table or its own section).

The check is deliberately dumb and honest: it parses `gradle/libs.versions.toml` for
`module = "group:artifact"` entries, strips the first two AndroidX/Compose/Kotlin groups that
ship under Apache-2.0 with the app, and requires everything else to appear in
THIRD_PARTY_NOTICES.md (case-insensitive `group:artifact` substring) or in the ALLOWLIST below
(first-party / generated / test-only entries with a recorded reason). A missing row fails the
build — that is the entire job.

    python3 tools/licence_check.py            # exit 0/1
    python3 tools/licence_check.py --verbose  # list every dependency and its verdict
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOG = ROOT / "gradle" / "libs.versions.toml"
NOTICES = ROOT / "THIRD_PARTY_NOTICES.md"

# First-party, generated, or test-only entries — each with a one-line recorded reason.
ALLOWLIST: dict[str, str] = {
    "androidx.": "Jetpack/AndroidX family — Apache-2.0 (Google's licence header ships in every artifact); a per-artifact report via licensee is the upgrade path (docs/RELEASE.md)",
    "androidx.compose:compose-bom": "Google Maven BOM (versions only, no code) — Apache-2.0 umbrella",
    "junit:junit": "test-only (JVM unit tests), EPL-1.0 — not shipped",
    "androidx.test.ext:junit": "test-only (instrumented runners), Apache-2.0 — not shipped",
    "androidx.test.core": "test-only (instrumented core), Apache-2.0 — not shipped",
    "com.android.tools.build": "build tooling (AGP) — not a runtime dependency",
    "org.jetbrains.kotlin": "Kotlin toolchain/stdlib — Apache-2.0, covered by the toolchain row",
    "com.google.devtools.ksp": "build-time codegen (Room) — not a runtime dependency",
    "io.gitlab.arturbosch.detekt": "build-time static analysis — not a runtime dependency",
    "org.jlleitschuh.gradle": "build-time ktlint plugin — not a runtime dependency",
    "app.cash.licensee": "reserved for a future full licence report (see docs/RELEASE.md)",
}

MODULE_RE = re.compile(r'module\s*=\s*"([^"]+)"')
SECTION_RE = re.compile(r"^##\s+(.+)$", re.MULTILINE)

# X-11 (ADR-088): an *adapted* file must carry the header defined by docs/REUSE_MATRIX.md §0 —
# `Adapted from <project> <upstream path> (Apache-2.0, <copyright line>). Changes: ...` — and the
# upstream path must have a THIRD_PARTY_NOTICES.md row. A header without a row means code entered
# the tree without its licence notice: fail.
ADAPTED_HEADER_RE = re.compile(
    r"Adapted from\s+(\S+)\s+(\S+)\s+\((?:Apache|apache)-2\.0"
)
ADAPTED_SOURCE_SUFFIXES = {".kt", ".java", ".py", ".kts"}
ADAPTED_SCAN_ROOTS = ("app", "core", "capabilities", "runtime", "plugin-sdk", "sdk-python", "testing", "gradle")
ADAPTED_SKIP_DIRS = {"build", ".gradle", "archive", "reference", "drafts"}

# X-11 (ADR-088): the reference/ inventory must be complete — every file in reference/ except the
# index docs themselves must be listed in reference/INDEX.md, so nothing third-party can enter the
# folder unrecorded. findings/ holds our own analysis notes and is also inventoried.
REFERENCE_INDEX = ROOT / "reference" / "INDEX.md"
REFERENCE_SELF_DOCS = {"reference/INDEX.md", "reference/README.md", "reference/SNAPSHOT.md"}


def catalog_modules() -> list[str]:
    modules: list[str] = []
    for match in MODULE_RE.finditer(CATALOG.read_text(encoding="utf-8")):
        modules.append(match.group(1))
    return sorted(set(modules))


def covered(group_artifact: str, notices_text: str) -> str | None:
    """Returns the coverage reason, or None when the dependency is not covered."""
    if group_artifact.lower() in notices_text.lower():
        return "THIRD_PARTY_NOTICES.md"
    for allow_key, reason in ALLOWLIST.items():
        if group_artifact.startswith(allow_key) or group_artifact == allow_key:
            return f"allowlist: {reason}"
    return None


def adapted_files(root: pathlib.Path = ROOT):
    """Yield (relative path, project, upstream path) for files carrying the adaptation header."""
    for base in ADAPTED_SCAN_ROOTS:
        base_dir = root / base
        if not base_dir.is_dir():
            continue
        for path in base_dir.rglob("*"):
            if path.suffix not in ADAPTED_SOURCE_SUFFIXES or not path.is_file():
                continue
            if set(path.parts) & ADAPTED_SKIP_DIRS:
                continue
            rel = path.relative_to(root).as_posix()
            text = path.read_text(encoding="utf-8", errors="replace")
            m = ADAPTED_HEADER_RE.search(text)
            if m:
                yield rel, m.group(1), m.group(2)


def reference_inventory_gaps(root: pathlib.Path = ROOT) -> list[str]:
    """Files under reference/ that reference/INDEX.md does not list (ADR-088 inventory)."""
    index = root / "reference" / "INDEX.md"
    indexed = index.read_text(encoding="utf-8", errors="replace") if index.exists() else ""
    ref_dir = root / "reference"
    gaps: list[str] = []
    if not ref_dir.is_dir():
        return gaps
    for path in sorted(ref_dir.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        if rel in REFERENCE_SELF_DOCS:
            continue
        # INDEX.md lists paths relative to reference/ ("Path under `reference/`")
        rel_under_ref = path.relative_to(ref_dir).as_posix()
        if f"`{rel_under_ref}`" not in indexed and f"`{rel}`" not in indexed:
            gaps.append(rel)
    return gaps


def main() -> int:
    verbose = "--verbose" in sys.argv
    modules = catalog_modules()
    notices_text = NOTICES.read_text(encoding="utf-8") if NOTICES.exists() else ""
    missing: list[str] = []
    for module in modules:
        reason = covered(module, notices_text)
        if reason is None:
            missing.append(module)
        if verbose:
            print(f"{'OK      ' if reason else 'MISSING '} {module}" + (f"  ({reason})" if reason else ""))
    # X-11 (ADR-088): adapted-file headers must have their THIRD_PARTY_NOTICES.md row.
    header_gaps: list[str] = []
    for rel, project, upstream in adapted_files():
        if upstream.lower() not in notices_text.lower() and project.lower() not in notices_text.lower():
            header_gaps.append(f"{rel} (adapted from {project} {upstream})")
    # X-11 (ADR-088): the reference/ inventory must be complete.
    inventory_gaps = reference_inventory_gaps()

    failed = bool(missing or header_gaps or inventory_gaps)
    if missing:
        print("licence check FAILED — add rows to THIRD_PARTY_NOTICES.md (or a recorded allowlist entry) for:")
        for module in missing:
            print(f"  - {module}")
    if header_gaps:
        print("licence check FAILED — adapted files carry an upstream-path header without a THIRD_PARTY_NOTICES.md row:")
        for gap in header_gaps:
            print(f"  - {gap}")
    if inventory_gaps:
        print("licence check FAILED — reference/ files not inventoried in reference/INDEX.md:")
        for gap in inventory_gaps:
            print(f"  - {gap}")
    if failed:
        return 1
    print(
        f"licence check ok — {len(modules)} catalog dependencies, all covered; "
        f"reference/ inventory complete"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
