"""Dev API JSON-RPC client for the autohost CLI (P2-23, docs/DEVAPI.md §5)."""

from __future__ import annotations

import base64
import io
import json
import os
import urllib.error
import urllib.request
import zipfile

DEFAULT_PORT = 8080


class DevApiError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class DevApiClient:
    """Thin JSON-RPC 2.0 client over HTTP with bearer-token auth."""

    def __init__(self, port: int = DEFAULT_PORT, token: str | None = None, timeout: float = 30.0) -> None:
        self.endpoint = f"http://127.0.0.1:{port}/rpc"
        self.token = token
        self.timeout = timeout
        self._next_id = 1

    def call(self, method: str, params: dict | None = None) -> dict:
        payload = {"jsonrpc": "2.0", "id": self._next_id, "method": method, "params": params or {}}
        self._next_id += 1
        body = json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(self.endpoint, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                envelope = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")
            raise DevApiError("HTTP_%d" % exc.code, detail or str(exc.reason)) from exc
        except urllib.error.URLError as exc:
            raise DevApiError(
                "CONN_FAILED",
                f"cannot reach {self.endpoint}: {exc.reason} "
                "(is the Dev API enabled and `adb forward tcp:<port> tcp:<port>` set up?)",
            ) from exc
        if "error" in envelope:
            error = envelope["error"]
            data = error.get("data") or {}
            raise DevApiError(data.get("code", "RPC_ERROR"), error.get("message", "unknown error"))
        return envelope.get("result") or {}

    # convenience wrappers -------------------------------------------------
    def ping(self) -> dict:
        return self.call("system.ping")

    def system_info(self) -> dict:
        return self.call("system.info")

    def push(self, directory: str) -> dict:
        """Install a task bundle from a local directory (zipped in-memory, sent base64)."""
        root = os.path.abspath(directory)
        manifest_path = os.path.join(root, "task.json")
        if not os.path.isfile(manifest_path):
            raise DevApiError("NO_MANIFEST", f"{root} has no task.json")
        with open(manifest_path, "rb") as fh:
            manifest = json.loads(fh.read().decode("utf-8"))
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as bundle:
            for base, _dirs, names in os.walk(root):
                for name in names:
                    full = os.path.join(base, name)
                    rel = os.path.relpath(full, root)
                    if rel.startswith("."):
                        continue
                    bundle.write(full, arcname=rel.replace(os.sep, "/"))
        encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
        return self.call("tasks.install", {"bundle_b64": encoded, "name": manifest.get("id")})

    def run(self, task: str, profile: str | None = None) -> dict:
        params: dict = {"task": task}
        if profile:
            params["profile"] = profile
        return self.call("runs.start", params)

    def status(self, run_id: str) -> dict:
        return self.call("runs.status", {"id": run_id})

    def logs(self, run_id: str, since_seq: int = 0) -> dict:
        return self.call("runs.tail", {"id": run_id, "since_seq": since_seq})

    def ui_dump(self, compact: bool = True) -> dict:
        return self.call("device.ui.snapshot", {"compact": compact})

    def ui_try(self, selector: dict) -> dict:
        return self.call("device.ui.find", {"selector": selector})

    def doctor(self) -> dict:
        return self.call("health.run", {})

    # P5-11/P5-12 additions -------------------------------------------------
    def screenshot(self, tag: str | None = None) -> dict:
        params: dict = {}
        if tag:
            params["tag"] = tag
        return self.call("device.screenshot", params)

    def export_run(self, run_id: str) -> dict:
        return self.call("runs.export", {"id": run_id})

    def health_checks(self) -> dict:
        return self.call("health.checks", {})

    def tasks_list(self) -> list:
        return self.call("tasks.list").get("tasks", [])

    def triggers_list(self) -> dict:
        return self.call("triggers.list", {})

    def trigger_upsert(self, rule: dict) -> dict:
        return self.call("triggers.upsert", {"rule": rule})

    def trigger_test(self, rule: dict, count: int = 5) -> dict:
        return self.call("triggers.test", {"rule": rule, "count": count})

    def save_bundle(self, export_result: dict, out_dir: str) -> str:
        """Decode a runs.export result into <out_dir>/<runId>.aarun and return the path."""
        import base64 as _b64
        run_id = export_result.get("runId", "run")
        raw = _b64.b64decode(export_result.get("bundle_b64", ""))
        os.makedirs(out_dir, exist_ok=True)
        path = os.path.join(out_dir, f"{run_id}.aarun")
        with open(path, "wb") as fh:
            fh.write(raw)
        return path
