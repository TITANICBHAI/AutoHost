"""autohost repo (P11-03, part 2) — the task-repository consumer on the PC.

Parses an `index.json` v1 (the same contract as the on-device `RepoIndex.kt` — the rules are
mirrored here on purpose and pinned by tests on both sides) and installs entries through the
Dev API. Fetching is https-or-file only; a bundle's sha256 (when the index carries one) is
verified BEFORE the install — integrity is not optional.
"""

from __future__ import annotations

import hashlib
import json
import re
import urllib.request
from dataclasses import dataclass
from typing import Any

SCHEMA = 1
SHA_HEX_LENGTH = 64
ID_REGEX = re.compile(r"^[a-z][a-z0-9]*(\.[a-z0-9][a-z0-9-]*)+$")
SEMVER_REGEX = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$")
SHA_REGEX = re.compile(r"^[0-9a-fA-F]{64}$")
RUNTIME_TYPES = {"python", "flow", "javascript"}
ENTRY_KEYS = {"id", "version", "title", "runtime", "source", "sha256", "min_host_api", "notes"}


@dataclass
class Issue:
    path: str
    code: str
    message: str

    def describe(self) -> str:
        return f"{self.path}: [{self.code}] {self.message}"


@dataclass
class Entry:
    id: str
    version: str
    title: str | None
    runtime: str | None
    source: str
    sha256: str | None
    min_host_api: int | None
    notes: str | None


@dataclass
class Index:
    name: str | None
    updated_at: str | None
    entries: list[Entry]


@dataclass
class ParseResult:
    index: Index | None
    issues: list[Issue]

    @property
    def ok(self) -> bool:
        return self.index is not None


def parse(text: str) -> ParseResult:
    """Mirrors RepoIndex.parse — malformed root → index None; per-entry problems → issues."""
    try:
        root = json.loads(text)
    except json.JSONDecodeError:
        return ParseResult(None, [Issue("/", "TYPE", "the index document must be a JSON object")])
    if not isinstance(root, dict):
        return ParseResult(None, [Issue("/", "TYPE", "the index document must be a JSON object")])
    issues: list[Issue] = []
    if root.get("schema") != SCHEMA:
        issues.append(Issue("/schema", "SCHEMA", f'"schema" must be {SCHEMA} (got {root.get("schema")})'))
    name = root.get("name") if isinstance(root.get("name"), str) else None
    updated = root.get("updated_at") if isinstance(root.get("updated_at"), str) else None
    tasks = root.get("tasks")
    if not isinstance(tasks, list):
        issues.append(Issue("/tasks", "REQUIRED", '"tasks" must be an array'))
        return ParseResult(None, issues)
    entries: list[Entry] = []
    seen: set[str] = set()
    for i, element in enumerate(tasks):
        if not isinstance(element, dict):
            issues.append(Issue(f"/tasks/{i}", "TYPE", "each entry must be an object"))
            continue
        entry = _parse_entry(element, i, issues)
        if entry is None:
            continue
        if entry.id in seen:
            issues.append(Issue(f"/tasks/{i}/id", "DUP_ID",
                                f"duplicate id '{entry.id}' — the first occurrence wins, this entry is dropped"))
        else:
            seen.add(entry.id)
            entries.append(entry)
    return ParseResult(Index(name, updated, entries), issues)


def _parse_entry(obj: dict[str, Any], i: int, issues: list[Issue]) -> Entry | None:
    path = f"/tasks/{i}"
    valid = True
    for key in obj:
        if key not in ENTRY_KEYS:
            issues.append(Issue(f"{path}/{key}", "UNKNOWN_KEY", f'"{key}" is not part of index v1'))
    for key in ("id", "version", "source"):
        if not isinstance(obj.get(key), str) or not obj[key]:
            issues.append(Issue(f"{path}/{key}", "REQUIRED", f'entry needs a "{key}"'))
            valid = False
    if not valid:
        return None
    entry_id = obj["id"]
    version = obj["version"]
    source = obj["source"]
    if not ID_REGEX.match(entry_id):
        issues.append(Issue(f"{path}/id", "PATTERN", f'"{entry_id}" is not a reverse-DNS task id'))
        valid = False
    if not SEMVER_REGEX.match(version):
        issues.append(Issue(f"{path}/version", "PATTERN", f'"{version}" is not strict semver (MAJOR.MINOR.PATCH)'))
        valid = False
    sha = obj.get("sha256")
    if sha is not None:
        if not isinstance(sha, str) or not SHA_REGEX.match(sha):
            issues.append(Issue(f"{path}/sha256", "PATTERN", f'"sha256" must be {SHA_HEX_LENGTH} hex characters'))
            valid = False
    runtime = obj.get("runtime")
    if runtime is not None and runtime not in RUNTIME_TYPES:
        issues.append(Issue(f"{path}/runtime", "ENUM", f'"runtime" must be one of {sorted(RUNTIME_TYPES)}'))
        valid = False
    min_api = obj.get("min_host_api")
    if "min_host_api" in obj:
        if not isinstance(min_api, int) or isinstance(min_api, bool) or min_api < 1:
            issues.append(Issue(f"{path}/min_host_api", "TYPE", '"min_host_api" must be a positive integer'))
            valid = False
    title = obj.get("title")
    if title is not None and (not isinstance(title, str) or len(title) > 200):
        issues.append(Issue(f"{path}/title", "MAX_LENGTH", '"title" max 200 characters'))
        valid = False
    notes = obj.get("notes")
    if notes is not None and (not isinstance(notes, str) or len(notes) > 500):
        issues.append(Issue(f"{path}/notes", "MAX_LENGTH", '"notes" max 500 characters'))
        valid = False
    if not valid:
        return None
    return Entry(
        id=entry_id,
        version=version,
        title=title if isinstance(title, str) else None,
        runtime=runtime if isinstance(runtime, str) else None,
        source=source,
        sha256=sha if isinstance(sha, str) else None,
        min_host_api=min_api if isinstance(min_api, int) and not isinstance(min_api, bool) else None,
        notes=notes if isinstance(notes, str) else None,
    )


# ---- selection (mirrors RepoIndex.select + SemVer §11) -----------------------------------------

def _semver_key(version: str) -> tuple:
    """A sort key implementing plan §11 SemVer: numeric fields, pre-release < release."""
    match = SEMVER_REGEX.match(version)
    if not match:
        return (0, 0, 0, (1,), ())  # unparseable sorts lowest — never mistaken for newer
    major, minor, patch, pre, _build = match.groups()
    if pre:
        pre_ids = tuple(
            (0, int(part), "") if part.isdigit() else (1, 0, part)
            for part in pre.lstrip("-").split(".")
        )
        return (int(major), int(minor), int(patch), (0,), pre_ids)  # pre-release BEFORE release
    return (int(major), int(minor), int(patch), (1,), ())


def select(entry: Entry, installed_version: str | None, host_api: int) -> tuple[str, str]:
    """Returns (verdict, message) — Install / Upgrade / UpToDate / Reject(HOST_TOO_OLD|REPO_OLDER)."""
    if entry.min_host_api is not None and host_api < entry.min_host_api:
        return "HOST_TOO_OLD", f"task {entry.id} needs host API {entry.min_host_api}; this host is {host_api}"
    if installed_version is None:
        return "INSTALL", ""
    newer, older = _semver_key(entry.version), _semver_key(installed_version)
    if newer == older:
        return "UP_TO_DATE", ""
    if newer < older:
        return "REPO_OLDER", f"repo has {entry.version} but {installed_version} is installed; downgrades need an explicit uninstall"
    return "UPGRADE", f"{installed_version} → {entry.version}"


def verify_sha(expected_hex: str, actual_hex: str) -> bool:
    if len(expected_hex) != SHA_HEX_LENGTH or len(actual_hex) != SHA_HEX_LENGTH:
        return False
    return expected_hex.lower() == actual_hex.lower()


# ---- fetching + the CLI actions -----------------------------------------------------------------

def fetch(url: str) -> bytes:
    """https or file only — a repo is a source of code; anything else is refused."""
    if url.startswith("https://"):
        with urllib.request.urlopen(url, timeout=15) as response:  # noqa: S310 (https pinned above)
            return response.read()
    if url.startswith("file://"):
        from pathlib import Path

        return Path(url[len("file://"):]).read_bytes()
    raise ValueError(f"refusing to fetch {url!r}: repo sources must be https:// (or file:// for tests)")


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def install_entry(entry: Entry, client: Any) -> str:
    """Fetches the bundle, verifies the sha when declared, installs via tasks.install."""
    raw = fetch(entry.source)
    if entry.sha256 and not verify_sha(entry.sha256, sha256_hex(raw)):
        raise ValueError(f"bundle sha256 mismatch for {entry.id} — refusing the install")
    import base64 as _b64

    result = client.call("tasks.install", {"bundle_b64": _b64.b64encode(raw).decode("ascii"), "name": entry.id})
    return str(result.get("taskId", entry.id))
