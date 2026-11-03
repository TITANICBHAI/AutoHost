"""X-19 architecture conformance tests (ADR-100): source-level rules that need no Gradle.

Scans the tree and fails when an architecture rule is broken:

1. Run records are created only by ``RunOrchestrator`` — a run starts anywhere except
   through the orchestrator.
2. ``AccessibilityService`` is touched only inside ``capabilities/a11y``.
3. Chaquopy is referenced only in ``:runtime:python`` (plus the shared version pin).
4. ``settings.gradle.kts`` includes neither ``reference/`` nor ``archive/``.
5. No reference-project package name appears in compiled sources.

Known pre-existing violations are recorded in ``KNOWN`` (a ratchet, with the date and the
reason they are not fixed yet): a NEW violation fails, and an allowlist entry whose finding
has disappeared also fails (stale — remove the entry). Set ``AUTOHOST_ARCH_STRICT=1`` to
ignore the ratchet and fail on every current violation (the red-team view used to record
the findings on 2026-11-03). Kotlin is deliberately NOT fixed by this task (failing test
first — record, do not fix).
"""
import os
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

# Top-level dirs that contain (or will contain) compiled Kotlin/Java sources.
BUILDABLE_ROOTS = ("app", "core", "capabilities", "runtime", "plugin-sdk", "testing", "samples")
SKIP_DIRS = {"build", ".gradle", ".git", "archive", "reference", "drafts", "node_modules"}
SRC_PARTS = ("src",)  # only files under a .../src/... path of a module count as sources

# --- Ratchet: known violations of rules that are currently broken (dated; do not silently grow).
# Keyed by rule id -> {relative posix path: reason the violation is recorded, not fixed}.
KNOWN = {
    "a11y_containment": {
        # 2026-11-03, X-19: the browser.a11y link (app module) casts the shared accessibility
        # service from A11yBridge to perform GLOBAL_ACTION_BACK — a direct touch of the class
        # outside capabilities/a11y. Recorded, not fixed (failing-test-first; fix belongs to a
        # later task through the A11yBridge seam).
        "app/src/main/kotlin/app/autohost/browser/A11yBrowserLink.kt":
            "browser.a11y link performs GLOBAL_ACTION_BACK on the shared service (recorded 2026-11-03)",
        # 2026-11-03, X-19: AppGraph uses the AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN Int
        # constant for the screen lock path. A constant touch, still outside the a11y module.
        "app/src/main/kotlin/app/autohost/AppGraph.kt":
            "GLOBAL_ACTION_LOCK_SCREEN constant used for the lock path (recorded 2026-11-03)",
    },
    # rule 1 (run starts): the single documented exception — P5-05 re-opens a persisted
    # INTERRUPTED record after a process death; the run itself is then handed to the orchestrator.
    "run_starts": {
        "app/src/main/kotlin/app/autohost/AppGraph.kt":
            "resumeRunFromDb rebuilds the lost record, then goes through the orchestrator (P5-05, recorded 2026-11-03)",
    },
}

A11Y_MODULE = "capabilities/a11y"
RUN_ORCHESTRATOR = "core/engine/src/main/kotlin/app/autohost/core/engine/orchestrator/RunOrchestrator.kt"
# Chaquopy may be applied only in :runtime:python; the shared version pin (catalog + root
# `apply false` declaration) is the Gradle mechanism that makes that possible and applies no code.
# Detection targets REAL references (package, plugin alias, config block) — the word "Chaquopy"
# in a comment or error message is prose, not a dependency (the raw-bridge import scanner must
# name Chaquopy to detect it in user bundles, ADR-062).
CHAQUOPY_REFERENCE = re.compile(r"com\.chaquo|libs\.plugins\.chaquopy|^\s*chaquopy\s*\{", re.MULTILINE)
CHAQUOPY_ALLOWED_PREFIXES = ("runtime/python/",)
CHAQUOPY_ALLOWED_FILES = ("build.gradle.kts", "gradle/libs.versions.toml")
REFERENCE_PACKAGES = re.compile(r"\b(ai\.closepaw|com\.ariaagent|com\.opendroid)\b")


def repo_files(suffixes, roots=BUILDABLE_ROOTS, under_src=True):
    """Yield (relative posix path, text) for buildable source files, skipping build/venv dirs."""
    for root in roots:
        base = REPO / root
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*")):
            if path.suffix not in suffixes or not path.is_file():
                continue
            parts = set(path.parts)
            if parts & SKIP_DIRS:
                continue
            if under_src and "src" not in path.parts:
                continue
            rel = path.relative_to(REPO).as_posix()
            text = path.read_text(encoding="utf-8", errors="replace")
            yield rel, text


def repo_config_files(suffixes):
    """Yield (relative posix path, text) for build/config files outside skipped dirs."""
    for path in sorted(REPO.rglob("*")):
        if path.suffix not in suffixes or not path.is_file():
            continue
        parts = set(path.parts)
        if parts & SKIP_DIRS:
            continue
        rel = path.relative_to(REPO).as_posix()
        yield rel, path.read_text(encoding="utf-8", errors="replace")


def strict() -> bool:
    return os.environ.get("AUTOHOST_ARCH_STRICT") == "1"


def check_rule(self, rule, findings, describe):
    """Compare findings against the ratchet; strict mode ignores the allowlist."""
    known = KNOWN.get(rule, {})
    unexpected = [f for f in findings if f not in known]
    stale = [path for path in known if path not in findings]
    if strict():
        self.assertEqual(findings, [], "strict mode: %s" % describe(findings))
        self.assertEqual(stale, [])
        return
    self.assertEqual(
        unexpected,
        [],
        "NEW %s violation(s) — fix the Kotlin or, if genuinely allowed, add a dated entry to "
        "KNOWN[%r]: %s" % (rule, rule, describe(unexpected)),
    )
    self.assertEqual(
        stale,
        [],
        "STALE ratchet entries in KNOWN[%r] — the violation is gone, remove the entries: %s"
        % (rule, stale),
    )


class ArchitectureConformanceTest(unittest.TestCase):
    """One method per rule (ADR-100 / X-19). All findings are reported with repo-relative paths."""

    def test_run_records_are_created_only_by_the_orchestrator(self):
        """A run starts only through RunOrchestrator: nothing else writes run records."""
        pattern = re.compile(r"\b(runStore|store)\.create\(")
        findings = [
            rel for rel, text in repo_files({".kt"}) if rel != RUN_ORCHESTRATOR and pattern.search(text)
        ]
        check_rule(self, "run_starts", findings, lambda f: ", ".join(f))

    def test_accessibility_service_is_touched_only_inside_the_a11y_module(self):
        """Only capabilities/a11y may reference the AccessibilityService class (not ...Info)."""

        def touches(line):
            for match in re.finditer(r"\bAccessibilityService\b", line):
                tail = line[match.end():]
                if tail.startswith("Info"):
                    continue  # AccessibilityServiceInfo is a different, data-only class
                return True
            return False

        findings = []
        for rel, text in repo_files({".kt", ".java"}):
            if rel.startswith(A11Y_MODULE + "/"):
                continue
            if any(touches(line) for line in text.splitlines()):
                findings.append(rel)
        check_rule(self, "a11y_containment", findings, lambda f: ", ".join(f))

    def test_chaquopy_is_referenced_only_in_the_python_runtime(self):
        findings = []
        for rel, text in repo_files({".kt", ".kts"}):
            if CHAQUOPY_REFERENCE.search(text) and not rel.startswith(CHAQUOPY_ALLOWED_PREFIXES):
                findings.append(rel)
        for rel, text in repo_config_files({".kts", ".toml"}):
            allowed = rel.startswith(CHAQUOPY_ALLOWED_PREFIXES) or rel in CHAQUOPY_ALLOWED_FILES
            if CHAQUOPY_REFERENCE.search(text) and not allowed:
                findings.append(rel)
        self.assertEqual(
            findings,
            [],
            "Chaquopy referenced outside :runtime:python (and the shared version pin): %s"
            % ", ".join(findings),
        )

    def test_settings_gradle_includes_neither_reference_nor_archive(self):
        text = (REPO / "settings.gradle.kts").read_text(encoding="utf-8", errors="replace")
        bad = [
            line.strip()
            for line in text.splitlines()
            if re.search(r"include\(|projectDir|rootDir", line)
            and re.search(r"[\"'/](reference|archive)[\"'/]", line)
        ]
        self.assertEqual(bad, [], "settings.gradle.kts wires reference/ or archive/ into the build: %s" % bad)

    def test_no_hash_comments_in_kotlin_sources(self):
        """Kotlin/KTS comments are // and /* */ — a line starting with '#' is shell/YAML syntax
        pasted into the wrong language (caught in CI as a build-script parse error, 2026-11-03)."""
        findings = []
        for rel, text in repo_files({".kt", ".kts"}):
            for line in text.splitlines():
                if line.lstrip().startswith("#") and not line.lstrip().startswith("#!"):
                    findings.append(f"{rel}: {line.strip()[:60]}")
                    break
        self.assertEqual(findings, [], "shell/YAML-style '#' comments in Kotlin sources: %s" % findings)

    def test_no_reference_project_package_names_in_compiled_sources(self):
        findings = [
            rel
            for rel, text in repo_files({".kt", ".java"})
            if REFERENCE_PACKAGES.search(text)
        ]
        self.assertEqual(
            findings,
            [],
            "Reference-project package names in compiled sources (adapted code must be renamed): %s"
            % ", ".join(findings),
        )


if __name__ == "__main__":
    unittest.main()
