#!/usr/bin/env python3
"""P13-09 (ADR-086 (2), AGENT_RUNTIME §6): the tool-catalog generator.

Reads schemas/capability-specs.w1.json and emits the agent's tool catalog as a byte-STABLE
JSON allowlist (sorted keys, no timestamps): one entry per allowed (capability, method) with
the model-visible name (`device.ui.click` → `ui_click`), the permission it needs, and the
argument WHITELIST (the model never sees strategy/retry_if_no_change/secret). A method not
listed is absent from the catalog — the allowlist IS the security boundary of the model's
surface (grants/scopes still apply on every broker call).

Usage:
    python tools/gen_tool_catalog.py            # writes runtime/agent/src/main/resources/autohost/tool_catalog.json
    python tools/gen_tool_catalog.py --check    # exit 1 on drift
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = ROOT / "schemas" / "capability-specs.w1.json"
OUT = ROOT / "runtime" / "agent" / "src" / "main" / "resources" / "autohost" / "tool_catalog.json"

# The Stage-A allowlist (AGENT_RUNTIME §6). Extra tools need the Stage-B process.
# name -> (capability, method, permission, model-visible args)
ALLOWLIST: dict[str, dict] = {
    "ui_snapshot":      {"capability": "device.ui", "method": "snapshot", "permission": "device.ui.observe", "args": {}},
    "ui_find":          {"capability": "device.ui", "method": "find", "permission": "device.ui.observe", "args": {"selector": {}, "limit?": {}}},
    "ui_wait_for":      {"capability": "device.ui", "method": "wait_for", "permission": "device.ui.observe", "args": {"selector": {}, "timeout_ms?": {"max": 10000}}},
    "ui_wait_for_settle": {"capability": "device.ui", "method": "wait_for_settle", "permission": "device.ui.observe", "args": {"timeout_ms?": {"max": 5000}}},
    "ui_click":         {"capability": "device.ui", "method": "click", "permission": "device.ui.act", "args": {"id?": {}, "selector?": {}, "expect?": {}}},
    "ui_long_click":    {"capability": "device.ui", "method": "long_click", "permission": "device.ui.act", "args": {"id?": {}, "selector?": {}}},
    "ui_set_text":      {"capability": "device.ui", "method": "set_text", "permission": "device.ui.act", "args": {"id?": {}, "selector?": {}, "text": {}, "verify?": {}}},
    "ui_scroll_until":  {"capability": "device.ui", "method": "scroll_until", "permission": "device.ui.act", "args": {"selector": {}, "direction": {"enum": ["up", "down"]}, "max_swipes?": {"max": 10}}},
    "ui_press":         {"capability": "device.ui", "method": "press", "permission": "device.ui.act", "args": {"key": {"enum": ["back"]}}},
    "ui_hide_keyboard": {"capability": "device.ui", "method": "hide_keyboard", "permission": "device.ui.act", "args": {}},
    "app_launch":       {"capability": "device.app", "method": "launch", "permission": "device.app.launch", "args": {"package": {"enum_of": "run_packages"}}},
    "app_foreground":   {"capability": "device.app", "method": "foreground", "permission": "device.app.query", "args": {}},
    "app_query":        {"capability": "device.app", "method": "query", "permission": "device.app.query", "args": {"package": {"enum_of": "run_packages"}}},
    "app_wait_foreground": {"capability": "device.app", "method": "wait_foreground", "permission": "device.app.query", "args": {"package": {"enum_of": "run_packages"}, "timeout_ms?": {"max": 15000}}},
    "ask_user":         {"capability": "ui.prompt", "method": "ask", "permission": "ui.prompt", "args": {"message": {}, "kind": {"enum": ["text", "choice", "confirm"]}, "choices?": {}}},
    "finish":           {"capability": None, "method": None, "permission": None, "host": True, "args": {"status": {"enum": ["done", "blocked", "failed"]}, "summary": {}}},
    "todo_write":       {"capability": None, "method": None, "permission": None, "host": True, "args": {"items": {}}},
    "wait":             {"capability": None, "method": None, "permission": None, "host": True, "args": {"seconds": {"max": 10}}},
}

# Host-written descriptions for the model (NOT the spec text — written for a UI agent).
DESCRIPTIONS: dict[str, str] = {
    "ui_snapshot": "Capture the current screen as a compact list of visible elements with stable [n] ids for this generation.",
    "ui_find": "Search the current screen for elements matching a selector.",
    "ui_wait_for": "Wait until an element matching the selector appears.",
    "ui_wait_for_settle": "Wait until the screen stops changing.",
    "ui_click": "Click an element by its observation id or selector.",
    "ui_long_click": "Long-press an element by its observation id or selector.",
    "ui_set_text": "Type text into an editable element (never use this for passwords).",
    "ui_scroll_until": "Scroll in a direction until an element matching the selector is visible.",
    "ui_press": "Press a device key. Only 'back' is available.",
    "ui_hide_keyboard": "Dismiss the on-screen keyboard if it is showing.",
    "app_launch": "Launch one of this run's target apps by package name.",
    "app_foreground": "Report which app is in the foreground (scope-filtered).",
    "app_query": "Ask whether one of this run's target apps is installed and its state.",
    "app_wait_foreground": "Wait until one of this run's target apps is in the foreground.",
    "ask_user": "Ask the user a question (text, choice or confirm).",
    "finish": "Claim the goal is done, blocked or failed. The host verifies; claiming is not the verdict.",
    "todo_write": "Update your scratchpad todo list (kept in context, no device effect).",
    "wait": "Pause for a short time (max 10 seconds).",
}


def build() -> dict:
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    caps = {c["id"]: c for c in spec["capabilities"]}
    entries = []
    for name in sorted(ALLOWLIST):
        entry = dict(ALLOWLIST[name])
        entry["name"] = name
        entry["description"] = DESCRIPTIONS[name]
        cap_id, method = entry["capability"], entry["method"]
        if cap_id is not None:
            cap = caps.get(cap_id)
            if cap is None:
                raise SystemExit(f"allowlist names unknown capability {cap_id}")
            methods = {m["name"]: m for m in cap.get("methods", [])}
            if method not in methods:
                raise SystemExit(f"allowlist names unknown method {cap_id}.{method}")
            entry["spec_risk"] = methods[method].get("risk", "low")
        entries.append(entry)
    return {"note": "generated by tools/gen_tool_catalog.py — byte-stable allowlist (P13-09); hand edits fail --check", "tools": entries}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args(argv)
    catalog = json.dumps(build(), indent=1, sort_keys=True) + "\n"
    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != catalog:
            print("tool catalog DRIFT — regenerate with tools/gen_tool_catalog.py")
            return 1
        print("tool catalog in sync")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(catalog, encoding="utf-8", newline="\n")
    print(f"wrote {OUT} ({catalog.count(chr(10))} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
