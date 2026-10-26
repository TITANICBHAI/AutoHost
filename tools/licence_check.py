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
    if missing:
        print("licence check FAILED — add rows to THIRD_PARTY_NOTICES.md (or a recorded allowlist entry) for:")
        for module in missing:
            print(f"  - {module}")
        return 1
    print(f"licence check ok — {len(modules)} catalog dependencies, all covered")
    return 0


if __name__ == "__main__":
    sys.exit(main())
