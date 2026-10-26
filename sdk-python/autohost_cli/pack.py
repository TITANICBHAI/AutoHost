"""Skill-format agent context pack (P5-16, plan §13.3 step 8).

`autohost docs --pack DIR` (or `python3 tools/gen_agent_pack.py DIR`) writes:

  SKILL.md                     — how to drive AutoHost from an agent loop
  references/sdk.md            — the SDK surface (ctx/device/ui/flow/errors)
  references/capabilities.md   — capability catalog (+ live availability when the device is reachable)
  references/task.schema.json  — the manifest schema, verbatim
  references/flow.schema.json  — the flow schema, verbatim
  references/trigger-rule.schema.json
  references/pitfalls.md       — common mistakes and their fixes
  references/device.md         — device facts (API level, flavor, providers)
  examples/                    — the sample tasks, copied verbatim

Generated from the specs (single source of truth §5.5); never hand-edit the outputs.
"""

from __future__ import annotations

import json
import os
import shutil

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCHEMA_DIR = os.path.join(REPO_ROOT, "schemas")
SAMPLES_DIR = os.path.join(REPO_ROOT, "samples")


SKILL_TEMPLATE = """# AutoHost — Android automation via the Dev API

## What this is
AutoHost runs sandboxed Python (or declarative flow) tasks on an Android phone. You author a task
bundle (`task.json` + `main.py`), push it over the Dev API, run it, and debug failures from `.aarun`
bundles — without touching the phone.

## Loop
1. LOOK: `autohost_get_device_state` + `autohost_dump_ui` (+ `autohost_try_selector`) to see the screen.
2. WRITE: create `task.json` + `main.py` (see references/sdk.md; schemas in references/).
3. PUSH: `autohost_push_task {dir}`.
4. RUN: `autohost_run_task {task}` → `autohost_tail_logs {run_id}`.
5. DEBUG: on failure `autohost_export_run {run_id}` and read the bundle (stages, incidents, dumps).
6. FIX and loop.

## Rules
- Selectors live in task code/config; prefer id/desc over text over coordinates.
- Never retry blindly — read the reason code in the failure bundle first.
- Secrets go in profile fields, never in code or logs (they are redacted anyway).
- Unavailable capability → check references/capabilities.md for the setup row instead of hacking.

## When to use
Any recurring or interactive Android automation the user has authorized. Respect target apps' rules;
never automate against a service's terms.
"""


SDK_REFERENCE = """# autohost SDK (Python, worker-side)

```python
from autohost import ctx, device, ui, flow, sel, log, notify, http, errors
```

- `ctx.config` / `ctx.profile` — read-only merged config for this run.
- `ctx.state` — per-run checkpoint dict; `ctx.save()` persists it atomically.
- `ctx.once(name, fn)` — run fn once per run-id even across resume attempts.
- `ctx.secret(name)` — a declared profile secret (raises NotFound if undeclared).
- `ctx.result(code, data)` — small machine-readable outcome shown in History.
- `ctx.progress(frac, msg)`; `ctx.sleep(s)` (cancellable); `ctx.now()` ISO time.
- `ctx.next_run(after_minutes=…)` — propose an adaptive reschedule (clamped by the host).
- `device.ui` — snapshot/find/click/set_text/wait_for/scroll_until/dump; handles are stale-checked.
- `device.app` — launch/stop/wait_foreground/foreground/query.
- `ui.ask(title, message, kind=…)` — prompt the user; run becomes WAITING_USER while open.
- `flow` — Screen signatures, `classify`, `transition`, `retry`, and `@flow.stage` stages
  (completed stages never repeat after an INTERRUPTED resume; on_resume: verify|redo|manual).
- Errors: `Cancelled`, `NeedsUser(reason, msg)`, `Retryable`, `Timeout`, `NotFound`,
  `StaleNode`, `CapabilityUnavailable`, `PermissionDenied` — catch narrowly, never `except Exception`.

See `autohost/*.py` docstrings for the full surface; stubs are in `autohost/*.pyi`.
"""


PITFALLS = """# Common pitfalls

1. **Clicking stale nodes.** Re-find after any screen change; handles die with the snapshot.
2. **Text-only selectors.** Localized apps change text; add id/desc variants (`sel.text("Ok", "OK")`).
3. **`except Exception` swallowing `Cancelled`.** Cancellation must propagate or the run hangs.
4. **Long sleeps.** `ctx.sleep` is cancellable; `time.sleep` is not and will burn the worker.
5. **Secrets in logs.** Values from `ctx.secret()` are redacted in logs/evidence, but do not echo them.
6. **Unverified clicks.** `device.ui.click(target, expect=…)` — without expect, success is not proven.
7. **Doubling side effects on resume.** Make stages idempotent or use `on_resume="verify"` with a real
   postcondition; the host never repeats a *completed* stage but an *interrupted* one needs a check.
8. **Idle timeout.** A wedged loop with no SDK calls is killed at `execution.idle_timeout_seconds`;
   emit `ctx.progress()` periodically in long pure-Python sections.
9. **Big payloads.** Screenshots/dumps over ~100 KB become evidence files — read paths, not blobs.
10. **Push without review.** Widened capability needs re-approval on the phone; expect Permission Review.
"""


def _capabilities_reference(device_info: dict) -> str:
    lines = ["# Capability catalog", ""]
    specs_path = os.path.join(SCHEMA_DIR, "capability-specs.w1.json")
    availability = device_info.get("providers")
    try:
        with open(specs_path, encoding="utf-8") as fh:
            specs = json.load(fh)
    except OSError:
        specs = {"capabilities": []}
    for spec in specs.get("capabilities", []):
        methods = ", ".join(m.get("name", "?") for m in spec.get("methods", []))
        lines.append(f"- **{spec.get('id', '?')}** — {spec.get('summary', '')}  methods: {methods}")
    if availability:
        lines += ["", "## Live availability on this device", f"```json", json.dumps(availability, indent=2), "```"]
    else:
        lines += ["", "_Availability rows appear here when the pack is written with a reachable device_ "
                      "(`autohost docs --pack`)._"]
    return "\n".join(lines) + "\n"


def _device_reference(device_info: dict) -> str:
    return ("# Device facts\n\n```json\n" +
            json.dumps(device_info or {"note": "device unreachable when the pack was written"}, indent=2) +
            "\n```\n")


def write_pack(out_dir: str, *, device_info: dict | None = None) -> str:
    """Write the pack; returns out_dir. Idempotent: overwrites generated files, keeps the rest."""
    device_info = device_info or {}
    os.makedirs(out_dir, exist_ok=True)
    refs = os.path.join(out_dir, "references")
    examples = os.path.join(out_dir, "examples")
    os.makedirs(refs, exist_ok=True)
    os.makedirs(examples, exist_ok=True)

    with open(os.path.join(out_dir, "SKILL.md"), "w", encoding="utf-8") as fh:
        fh.write(SKILL_TEMPLATE)
    with open(os.path.join(refs, "sdk.md"), "w", encoding="utf-8") as fh:
        fh.write(SDK_REFERENCE)
    with open(os.path.join(refs, "capabilities.md"), "w", encoding="utf-8") as fh:
        fh.write(_capabilities_reference(device_info))
    with open(os.path.join(refs, "pitfalls.md"), "w", encoding="utf-8") as fh:
        fh.write(PITFALLS)
    with open(os.path.join(refs, "device.md"), "w", encoding="utf-8") as fh:
        fh.write(_device_reference(device_info))

    for schema in ("task.schema.json", "flow.schema.json", "trigger-rule.schema.json"):
        src = os.path.join(SCHEMA_DIR, schema)
        if os.path.isfile(src):
            shutil.copyfile(src, os.path.join(refs, schema))

    if os.path.isdir(SAMPLES_DIR):
        for name in sorted(os.listdir(SAMPLES_DIR)):
            src = os.path.join(SAMPLES_DIR, name)
            if os.path.isdir(src):
                shutil.copytree(src, os.path.join(examples, name), dirs_exist_ok=True)
    return out_dir


if __name__ == "__main__":
    import sys

    target = sys.argv[1] if len(sys.argv) > 1 else os.path.join(".", "autohost-agent-pack")
    print(write_pack(target))
