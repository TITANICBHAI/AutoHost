"""Static import scan for task bundles (plan §15, P2-13).

Runs inside the Python runtime (worker or simulator) on a bundle directory: parses every ``*.py`` file
with :mod:`ast`, extracts top-level imported module names, and classifies them via the same buckets the
host's Kotlin ``ImportClassifier`` knows: stdlib / bundled / vendored / unavailable / desktop-only, with
suggestions. The JSON report mirrors ``ScanReport`` on the Kotlin side so either end can consume it.

Desktop-only / bypass routes (ctypes, GUI kits, browser stacks, subprocess-to-desktop-binaries) are
flagged with a fix suggestion — never silently allowed, never shimmed (no compatibility shims in v1).
"""

from __future__ import annotations

import ast
import base64
import json
import os
import sys
from pathlib import Path

DESKTOP_ONLY = {
    "playwright": "use the browser capability (browser.open/navigate/extract - shipped in P10)",
    "patchright": "use the browser capability (browser.open/navigate/extract - shipped in P10)",
    "selenium": "use the browser capability (browser.open/navigate/extract - shipped in P10)",
    "pyautogui": "use device.ui for screen control",
    "pynput": "keyboard/mouse control is not available on device; use device.ui",
    "tkinter": "no desktop GUI on device; use ui.* prompts or a task UI",
    "PyQt5": "no desktop GUI on device; use ui.* prompts or a task UI",
    "PyQt6": "no desktop GUI on device; use ui.* prompts or a task UI",
    "PySide2": "no desktop GUI on device; use ui.* prompts or a task UI",
    "PySide6": "no desktop GUI on device; use ui.* prompts or a task UI",
    "win32api": "Windows-only; not available on device",
    "win32com": "Windows-only; not available on device",
    "winreg": "Windows-only; not available on device",
    "ctypes": "bypasses the broker; declare needs.capabilities instead (android.api.direct requires a grant)",
    "adb_shell": "talk to the device through the host broker, not from inside it",
}

SUGGESTION_FALLBACK = "not bundled with the host; add it to python_deps.vendored or rewrite against the SDK"

# Subprocess targets that only make sense on a PC (task is scanning for desktop-binary calls).
DESKTOP_BINARIES = {
    "adb", "scrcpy", "python", "python3", "pip", "git", "bash", "sh", "cmd", "powershell",
    "explorer", "open", "xdg-open", "code", "node", "npm", "brew", "apt", "choco",
}

_STDLIB_TOP_LEVEL = frozenset(sys.stdlib_module_names)  # Python 3.10+


def top_level_name(name: str) -> str:
    return name.split(".")[0] if name else name


def imported_toplevel_modules(source: str) -> list[str]:
    """Top-level module names imported by one python file (import x / from x import y)."""
    try:
        tree = ast.parse(source)
    except SyntaxError as exc:
        # A syntax error is a manifest-level problem the validator reports; the scan records nothing.
        return ["<syntax-error:%s>" % exc.lineno]
    modules: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                modules.append(top_level_name(alias.name))
        elif isinstance(node, ast.ImportFrom):
            if node.level and node.level > 0:
                continue  # relative import within the bundle — not a dependency
            if node.module:
                modules.append(top_level_name(node.module))
    return modules


def desktop_binary_calls(source: str) -> list[str]:
    """Best-effort scan for subprocess/os.system calls that launch desktop binaries."""
    hits: list[str] = []
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return hits
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr in {
            "call", "run", "Popen", "check_output", "system",
        } and node.args:
            first = node.args[0]
            if isinstance(first, ast.Constant) and isinstance(first.value, str):
                binary = first.value.split()[0].rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
                if binary in DESKTOP_BINARIES:
                    hits.append(binary)
    return hits


def raw_bridge_calls(source: str) -> bool:
    """True when the file uses the raw Android bridge: Chaquopy imports or autohost.android calls.

    Matches ``android.jclass(…)`` / ``autohost.android.jclass(…)`` (same for ``system_service`` and
    ``authorize``) — the P8-12 import guard feeds on this (declare ``android.api.direct`` in
    ``needs.capabilities`` or the import is refused).
    """
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return False
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name == "java" or alias.name.startswith("com.chaquo"):
                    return True
        elif isinstance(node, ast.ImportFrom):
            if node.module == "java" or (node.module or "").startswith("com.chaquo"):
                return True
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
            if node.func.attr not in ("jclass", "system_service", "authorize"):
                continue
            value = node.func.value
            parts = [node.func.attr]
            while isinstance(value, ast.Attribute):
                parts.append(value.attr)
                value = value.value
            if isinstance(value, ast.Name):
                parts.append(value.id)
            parts.reverse()  # e.g. ["autohost", "android", "jclass"] or ["android", "jclass"]
            if parts[-2:] == ["android", node.func.attr]:
                prefix = parts[:-2]
                if not prefix or prefix[-1] == "autohost":
                    return True
    return False


def scan_dir(bundle_dir: str | os.PathLike, bundled: list[str] | None = None, vendored: list[str] | None = None) -> dict:
    """Scan every ``*.py`` under *bundle_dir*; returns the ScanReport-shaped dict."""
    bundled_set = {top_level_name(b) for b in (bundled or [])}
    vendored_set = {top_level_name(v) for v in (vendored or [])}

    files = 0
    stdlib = 0
    bundled_count = 0
    vendored_count = 0
    unavailable: set[str] = set()
    desktop: dict[str, dict] = {}
    binary_hits: list[dict] = []
    raw_bridge: list[str] = []

    root = Path(bundle_dir)
    for path in sorted(root.rglob("*.py")):
        rel = path.relative_to(root).as_posix()
        files += 1
        source = path.read_text(encoding="utf-8", errors="replace")
        modules = imported_toplevel_modules(source)
        for module in modules:
            if module.startswith("<syntax-error"):
                unavailable.add(module)
                continue
            # desktop-only / bypass routes win over everything else (import guard)
            if module in DESKTOP_ONLY:
                desktop.setdefault(module, {"module": module, "file": rel, "suggestion": DESKTOP_ONLY[module]})
            elif module in _STDLIB_TOP_LEVEL:
                stdlib += 1
            elif module in bundled_set or any(module == b or module.startswith(b + ".") for b in bundled_set):
                bundled_count += 1
            elif module in vendored_set or any(module == v or module.startswith(v + ".") for v in vendored_set):
                vendored_count += 1
            else:
                unavailable.add(module)
        for binary in desktop_binary_calls(source):
            binary_hits.append({"module": binary, "file": rel, "suggestion": "desktop binaries are not available on device"})
        if raw_bridge_calls(source):
            raw_bridge.append(rel)

    for hit in binary_hits:
        desktop.setdefault(hit["module"], hit)

    return {
        "files": files,
        "stdlib": stdlib,
        "bundled": bundled_count,
        "vendored": vendored_count,
        "unavailable": sorted(unavailable),
        "desktopOnly": sorted(desktop.values(), key=lambda h: (h["module"], h["file"])),
        "rawBridge": sorted(raw_bridge),
    }


def scan_json(bundle_dir: str, bundled: list[str] | None = None, vendored: list[str] | None = None) -> str:
    """JSON report string (the worker returns strings across the bridge)."""
    return json.dumps(scan_dir(bundle_dir, bundled, vendored))


def _self_check() -> None:  # pragma: no cover - manual helper
    print(scan_json(os.path.dirname(__file__))[:200])


if __name__ == "__main__":  # pragma: no cover
    if len(sys.argv) > 1:
        print(scan_json(sys.argv[1]))
    else:
        _self_check()
