#!/usr/bin/env python3
"""Generate testing/golden/script_manifest_vectors.json: the executable spec of `ManifestFactory`.

A person who picks a Python file, a folder or pasted code never writes `task.json`. The Kotlin `ManifestFactory`
(pure class, JVM unit test) writes it from a few plain choices. This file pins that mapping:

  * `build_manifest` below is the reference mapping; the Kotlin class must produce an equal JSON tree for every
    valid case and exactly the listed error keys for every invalid case;
  * every produced manifest is checked against schemas/task.schema.json with tools/jsonschema_mini.py, so the
    manifests are proven to be manifests the host accepts (same id pattern, same allowed keys, same bounds).

Regenerate only when the contract changes, and record why in docs/DECISIONS.md.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from jsonschema_mini import validate as schema_errors  # noqa: E402

SCHEMA = json.loads((ROOT / "schemas" / "task.schema.json").read_text(encoding="utf-8"))
NAME_MAX = 80
SLUG_MAX = 40
UI_GRANT_ORDER = ("device.app.launch", "device.app.query", "device.ui.observe", "device.ui.act")
CASES = []


def slug(name: str) -> str:
    """lowercase a-z0-9 and '-', everything else becomes '-', no leading/trailing/double '-'; never empty."""
    text = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:SLUG_MAX].strip("-")
    return text or "script"


def expand_permissions(perms: dict) -> list:
    """The four plain switches -> permission grants (id + scope), in a fixed order."""
    grants = []
    apps = perms.get("control_apps") or []
    if apps:
        grants += [{"id": gid, "scope": {"packages": list(apps)}} for gid in UI_GRANT_ORDER]
    domains = perms.get("internet_domains") or []
    if domains:
        grants.append({"id": "net.http", "scope": {"domains": list(domains)}})
    if perms.get("notifications"):
        grants.append({"id": "notify.post"})
    if perms.get("own_files"):
        grants.append({"id": "files.private"})
    return grants


def validate_inputs(kind: str, name: str, entry: str, perms: dict) -> list:
    """Error keys (stable identifiers; the UI maps them to plain sentences). Empty list = usable."""
    errors = []
    if not name.strip():
        errors.append("name_required")
    if kind == "script":
        suffix_ok = entry.endswith(".py")
        if not suffix_ok:
            errors.append("entry_must_be_py")
    elif kind == "flow":
        if not re.search(r"\.(ya?ml|json)$", entry):
            errors.append("entry_must_be_flow_file")
    if entry.startswith("/") or ".." in entry.split("/") or "\\" in entry or not entry:
        errors.append("entry_path_invalid")
    if perms.get("internet") and not perms.get("internet_domains"):
        errors.append("net_http_needs_domain")
    if perms.get("control_apps_on") and not perms.get("control_apps"):
        errors.append("app_control_needs_app")
    return errors


def build_manifest(kind: str, name: str, entry: str, perms: dict, id_suffix: str,
                   version: str = "1.0.0", description: str = "") -> dict:
    grants = expand_permissions(perms)
    uses_ui = any(g["id"].startswith("device.ui") for g in grants)
    apps = perms.get("control_apps") or []
    runtime_type = "python" if kind == "script" else "flow"
    default_desc = "Script added from this phone." if kind == "script" else "Flow recorded on this phone."
    manifest = {
        "schema": 1,
        "id": f"local.{slug(name)}-{id_suffix}",
        "name": name.strip()[:NAME_MAX],
        "version": version,
        "description": (description.strip() or default_desc)[:500],
        "host_api": ">=1 <2",
        "runtime": {"type": runtime_type, "entry": entry},
        "needs": {"capabilities": grants, "optional": [], "apps": [{"package": p} for p in apps]},
        "triggers": {"manual": True, "schedule": {"allowed": True}, "events": []},
        "execution": {
            "ui_exclusive": uses_ui,
            "needs_unlocked_screen": uses_ui,
            "pausable": True,
            "timeout_minutes": 30 if kind == "script" else 10,
            "max_attempts": 1,
            # plain scripts are not UI-verified; recorded flows verify their final state like the sample flow
            "verification": "off" if kind == "script" else "final",
        },
        "ui": {"mode": "auto"},
    }
    if kind == "flow":
        manifest["execution"]["idle_timeout_seconds"] = 120
    return manifest


def valid(name, kind, display_name, entry, perms, id_suffix="a1b2", version="1.0.0", description=""):
    assert not validate_inputs(kind, display_name, entry, perms), name
    manifest = build_manifest(kind, display_name, entry, perms, id_suffix, version, description)
    problems = schema_errors(manifest, SCHEMA)
    assert not problems, (name, problems)
    CASES.append({
        "name": name,
        "input": {"kind": kind, "display_name": display_name, "entry": entry, "permissions": perms,
                  "id_suffix": id_suffix, "version": version, "description": description},
        "manifest": manifest,
    })


def invalid(name, kind, display_name, entry, perms, errors):
    got = validate_inputs(kind, display_name, entry, perms)
    assert got == errors, (name, got, errors)
    CASES.append({
        "name": name,
        "input": {"kind": kind, "display_name": display_name, "entry": entry, "permissions": perms,
                  "id_suffix": "a1b2", "version": "1.0.0", "description": ""},
        "errors": errors,
    })


# --- script (picked file, pasted code, or a chosen file inside a folder) ---------------------------
valid("script_no_permissions", "script", "My script", "main.py", {})
valid("script_internet_one_site", "script", "Site check", "main.py", {"internet": True, "internet_domains": ["example.com"]})
valid("script_controls_one_app", "script", "Open settings", "main.py", {"control_apps_on": True, "control_apps": ["com.android.settings"]})
valid("script_controls_two_apps_and_notifies", "script", "Two apps", "main.py",
      {"control_apps_on": True, "control_apps": ["com.android.settings", "com.android.chrome"], "notifications": True})
valid("script_own_files_and_notifications", "script", "Writer", "main.py", {"notifications": True, "own_files": True})
valid("script_everything", "script", "All four", "main.py",
      {"control_apps_on": True, "control_apps": ["com.android.settings"], "internet": True,
       "internet_domains": ["example.com", "api.example.org"], "notifications": True, "own_files": True},
      id_suffix="z9y8", version="1.0.1", description="Checks a site and tells me.")
valid("folder_entry_in_subfolder", "script", "Folder project", "src/app.py", {})
valid("name_with_parentheses", "script", "My Script (2)", "main.py", {})
valid("name_non_ascii", "script", "Ünïcode Skript", "main.py", {})
valid("name_only_symbols_gets_fallback_slug", "script", "___", "main.py", {})
valid("name_very_long_is_cut", "script", "Backup " + "very-long-name-" * 14, "main.py", {})

# --- flow (a recorded flow saved as a task) -----------------------------------------------------------
valid("flow_recorded_in_settings", "flow", "Wi-Fi check", "flow.yaml",
      {"control_apps_on": True, "control_apps": ["com.android.settings"]})
valid("flow_json_entry", "flow", "Flow as json", "flow.json", {"control_apps_on": True, "control_apps": ["com.android.chrome"]})

# --- invalid inputs: error KEYS are the contract (the UI turns keys into sentences) --------------------
invalid("empty_name", "script", "   ", "main.py", {}, ["name_required"])
invalid("entry_is_not_python", "script", "Shell", "run.sh", {}, ["entry_must_be_py"])
invalid("entry_escapes_the_folder", "script", "Escape", "../x.py", {}, ["entry_path_invalid"])
invalid("entry_is_absolute", "script", "Absolute", "/sdcard/x.py", {}, ["entry_path_invalid"])
invalid("internet_without_a_website", "script", "Net", "main.py", {"internet": True}, ["net_http_needs_domain"])
invalid("app_control_without_an_app", "script", "Apps", "main.py", {"control_apps_on": True}, ["app_control_needs_app"])
invalid("flow_entry_not_a_flow_file", "flow", "Flow", "flow.txt", {}, ["entry_must_be_flow_file"])


def build_doc() -> dict:
    return {
        "description": "Executable spec of ManifestFactory (Part 2 B-00): plain choices -> task.json for a picked "
                       "script / pasted code / folder / recorded flow. Generated by "
                       "tools/gen_script_manifest_vectors.py and checked against schemas/task.schema.json. The Kotlin "
                       "ManifestFactory must produce an equal JSON tree for every case with `manifest` and exactly "
                       "the listed error keys for every case with `errors`.",
        "input_fields": {
            "kind": "script|flow", "display_name": "text the person typed (cut to 80 characters)",
            "entry": "relative file inside the task folder; main.py for a picked file or pasted code",
            "permissions": {
                "control_apps_on": "switch 'Control other apps' is on", "control_apps": "chosen package names",
                "internet": "switch 'Use the internet' is on", "internet_domains": "chosen websites (domains only)",
                "notifications": "bool", "own_files": "bool",
            },
            "id_suffix": "4 characters [0-9a-z] taken from Ids.ulid(clock.nowMs()) so the function stays pure",
        },
        "cases": CASES,
    }


def main():
    out = ROOT / "testing" / "golden" / "script_manifest_vectors.json"
    out.write_text(json.dumps(build_doc(), indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out} with {len(CASES)} cases")


if __name__ == "__main__":
    main()
