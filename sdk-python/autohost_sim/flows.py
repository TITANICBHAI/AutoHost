"""run_flow_task: execute a declarative flow task (flow.json / flow.yaml) against a FakeDevice.

The simulator's flow half (plan §18 P6-11): the same reference interpreter that the golden
vectors pin (`autohost_ref.flows`) runs the task's flow document through a broker adapter over
the FakeDevice, so a flow task behaves in the simulator exactly as the Kotlin
FlowRuntimeProvider behaves on the phone — same command set, same failure classes, same outcome
shape as `autohost_sim.runner.run_task` returns for Python tasks.

Entry resolution mirrors the Kotlin FlowSources: the manifest's `runtime.entry`, else
`flow.json`, else `flow.yaml` (ADR-052 order — JSON needs no dependency). YAML needs PyYAML
(available in this environment; the on-device side keeps the parser-plug rule instead).
Subflows for `runFlow {file}` are every other flow document in the task folder, registered
under both the file name and the stem, exactly like the Kotlin side.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from autohost_ref.flows import BrokerError, Exec, FlowError, run_flow

from .device import FakeDevice, SimError
from .runner import Outcome
from autohost._ctx import Result

__all__ = ["SimBroker", "run_flow_task", "load_flow_document", "load_subflows"]


class SimBroker:
    """Broker adapter over the FakeDevice: records calls, maps SimError to BrokerError.

    Same contract as the adapter in tools/gen_flow_vectors.py (the golden-vector generator),
    kept here as the simulator's own long-lived home (the generator is contract-frozen).
    """

    def __init__(self, device: FakeDevice) -> None:
        self.device = device
        self.calls: list[tuple] = []

    def call(self, capability: str, method: str, args: dict) -> dict:
        self.calls.append((capability, method, dict(args)))
        try:
            out = self.device.dispatch(capability, method, dict(args))
        except SimError as exc:
            raise BrokerError(exc.code, exc.message, retryable=bool(exc.retryable)) from None
        return out if isinstance(out, dict) else {"value": out}


def load_flow_document(task_dir: str | Path, entry: str | None) -> tuple[dict, str]:
    """Reads the main flow document; returns (raw_document, file_name).

    The document stays raw (JSON/YAML -> dict): `run_flow` parses it (and re-parses on resume),
    matching the reference and the golden vectors where subflows are raw documents too.
    """
    task_dir = Path(task_dir)
    candidates: list[Path]
    if entry:
        named = task_dir / entry
        if not named.is_file():
            raise FlowError(f"flow entry '{entry}' does not exist beside task.json")
        candidates = [named]
    else:
        candidates = [p for p in (task_dir / "flow.json", task_dir / "flow.yaml") if p.is_file()]
        if not candidates:
            raise FlowError("no flow document found beside task.json (expected flow.json or flow.yaml)")
    file = candidates[0]
    text = file.read_text(encoding="utf-8")
    if file.suffix.lower() in (".yaml", ".yml"):
        return _parse_yaml(text, file.name), file.name
    try:
        doc = json.loads(text)
    except json.JSONDecodeError as exc:
        raise FlowError(f"flow '{file.name}' is not valid JSON: {exc}") from None
    return doc, file.name


def _parse_yaml(text: str, name: str) -> dict:
    try:
        import yaml  # PyYAML: the simulator host's dependency, not the SDK's
    except ImportError:
        raise FlowError(
            f"flow '{name}' is YAML but PyYAML is not installed in the simulator environment; "
            "ship a flow.json (ADR-052 keeps the on-device side plug-gated as well)",
        ) from None
    try:
        loaded = yaml.safe_load(text)
    except Exception as exc:  # yaml.YAMLError and friends
        raise FlowError(f"flow '{name}' is not valid YAML: {exc}") from None
    if not isinstance(loaded, dict):
        raise FlowError(f"flow '{name}' must be a mapping at the top level")
    return loaded


def load_subflows(task_dir: str | Path, main_file: str | None) -> dict[str, dict]:
    """Every other flow document in the task folder, keyed by file name and stem."""
    task_dir = Path(task_dir)
    out: dict[str, dict] = {}
    for file in sorted(task_dir.iterdir()):
        if not file.is_file() or file.name == "task.json" or file.name == main_file:
            continue
        if file.suffix.lower() not in (".json", ".yaml", ".yml"):
            continue
        try:
            text = file.read_text(encoding="utf-8")
            doc = _parse_yaml(text, file.name) if file.suffix.lower() in (".yaml", ".yml") else json.loads(text)
        except (FlowError, json.JSONDecodeError, OSError):
            continue  # fail closed at execution time, only if a runFlow step targets it
        out[file.name] = doc
        out[file.stem] = doc
    return out


def run_flow_task(
    task_dir: str | Path,
    *,
    device: FakeDevice,
    config: dict | None = None,
    profile: dict | None = None,
    profile_id: str = "p1",
    secrets: dict | None = None,
    env: dict | None = None,
    run: dict | None = None,
    api_level: int = 29,
    cancel_after_s: float | None = None,
) -> Outcome:
    """Runs a flow task like `autohost_sim.runner.run_task` runs a Python task."""
    task_dir = Path(task_dir)
    manifest: dict[str, Any] = json.loads((task_dir / "task.json").read_text(encoding="utf-8"))
    device.profile_id = profile_id
    # P11-04 (ADR-080): the same pack injection the Python/JS hosts get ({{config.packs.*}}).
    from .runner import resolve_packs
    config = resolve_packs(manifest, dict(config or {}), device)
    flow, main_file = load_flow_document(task_dir, manifest.get("runtime", {}).get("entry"))
    subflows = load_subflows(task_dir, main_file)

    events: list[dict] = []
    broker = SimBroker(device)
    cancelled = {"flag": False}

    def check() -> None:
        if cancelled["flag"]:
            from autohost_ref.flows import Cancelled

            raise Cancelled()

    def sleep(seconds: float) -> None:
        device.clock.sleep(seconds)  # advances the virtual clock; on_tick injects cancellation

    if cancel_after_s is not None:
        previous = device.clock.on_tick

        def stop_when_due(clock: Any) -> None:
            if previous:
                previous(clock)
            if clock.slept >= cancel_after_s:
                cancelled["flag"] = True

        device.clock.on_tick = stop_when_due

    ex = Exec(
        broker,
        emit=events.append,
        check=check,
        sleep=sleep,
        api_level=api_level,
        subflows=subflows,
    )
    outcome = run_flow(
        flow, ex,
        config=dict(config or {}), profile=dict(profile or {}), env=dict(env or {}),
        secrets=dict(secrets or {}), run=dict(run or {"attempt": 1}),
    )
    result = None
    if outcome["status"] == "SUCCEEDED":
        payload = outcome.get("result") or {}
        result = Result(payload.get("code", "OK"), payload.get("data") or {})
    return Outcome(
        status=outcome["status"],
        reason=outcome.get("reason"),
        result=result,
        events=events,
        message=outcome.get("message", ""),
    )
