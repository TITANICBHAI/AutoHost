"""run_task: execute a task's entry point against a FakeDevice and report the run status.

The classification logic is shared with the Android worker via `autohost._runtime.execute_with_runtime`,
so the simulator and the device behave identically for every failure class.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from autohost._core import Runtime
from autohost._ctx import Result
from autohost._runtime import execute_with_runtime

from .device import FakeDevice
from .transport import SimTransport


@dataclass
class Outcome:
    status: str                      # SUCCEEDED | FAILED | CANCELLED | WAITING_USER
    reason: str | None = None
    result: Result | None = None
    events: list = field(default_factory=list)
    message: str = ""


def resolve_packs(manifest: dict[str, Any], config: dict, device: FakeDevice) -> dict:
    """P11-04 (ADR-080): the sim mirror of the host's run-start pack injection.

    The task's `uses_packs` resolve against `device.installed_packs`; the payload lands under the
    reserved config key `packs` (the host value wins on collision, with a warning, exactly like
    PackController.injectPacks). Missing packs are a warning — the run proceeds honestly.
    """
    uses = manifest.get("uses_packs") or []
    if not uses:
        return config
    resolved = {name: device.installed_packs[name] for name in uses if name in device.installed_packs}
    for missing in [name for name in uses if name not in device.installed_packs]:
        print(f"[sim] warning: app pack '{missing}' is not installed — the run starts without it")
    if not resolved:
        return config
    if "packs" in config:
        print("[sim] warning: config already has a 'packs' key; the host payload wins (reserved key)")
    merged = dict(config)
    merged["packs"] = resolved
    return merged


def run_task(task_dir: str | Path, *, device: FakeDevice, config: dict | None = None,
             profile: dict | None = None, profile_id: str = "p1", secrets: dict | None = None,
             trigger_type: str = "manual", trigger_payload: dict | None = None,
             cancel_after_s: float | None = None) -> Outcome:
    task_dir = Path(task_dir)
    manifest: dict[str, Any] = json.loads((task_dir / "task.json").read_text(encoding="utf-8"))
    device.profile_id = profile_id
    config = resolve_packs(manifest, dict(config or {}), device)

    rt = Runtime(SimTransport(device), task_id=manifest["id"], config=config, profile=profile,
                 profile_id=profile_id, secrets=secrets, clock=device.clock,
                 trigger_type=trigger_type, trigger_payload=trigger_payload)
    if cancel_after_s is not None:
        previous = device.clock.on_tick

        def stop_when_due(clock: Any) -> None:
            if previous:
                previous(clock)
            if clock.slept >= cancel_after_s:
                rt.cancelled = True

        device.clock.on_tick = stop_when_due

    outcome = execute_with_runtime(rt, str(task_dir), manifest["runtime"]["entry"])
    result = None
    if outcome["status"] == "SUCCEEDED":
        payload = outcome.get("result") or {}
        result = Result(payload.get("code", "OK"), payload.get("data") or {})
    return Outcome(
        status=outcome["status"],
        reason=outcome.get("reason"),
        result=result,
        events=rt.emitted,
        message=outcome.get("message", ""),
    )
