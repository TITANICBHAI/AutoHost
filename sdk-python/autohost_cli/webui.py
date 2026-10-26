"""autohost ui web (P11-06) — the PC web inspector.

Serves a single-page inspector on 127.0.0.1 that talks to the Dev API through a small
localhost proxy: the browser never sees the token (the proxy injects Authorization), and the
Dev API's localhost-only rule still applies — this is an adb-forward companion, not a LAN
surface. Dependency-free on purpose (stdlib http.server + fetch in the page).

Commands (wired in autohost_cli/__main__.py):
    autohost ui web [--port 8099] [--devapi-port 8080]
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from autohost_cli.client import DevApiClient

DEFAULT_UI_PORT = 8099


def index_html(devapi_port: int) -> str:
    """The inspector page. Rendered per server start so the page knows nothing about tokens."""
    return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>AutoHost inspector</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 0; background: #111; color: #eee; }
  header { padding: 10px 16px; background: #1b1b1b; display: flex; gap: 16px; align-items: center; }
  header h1 { font-size: 16px; margin: 0; }
  button, input { background: #262626; color: #eee; border: 1px solid #444; border-radius: 6px;
                  padding: 6px 10px; font-size: 13px; }
  button:hover { background: #333; }
  main { display: grid; grid-template-columns: 1fr 360px; gap: 12px; padding: 12px 16px; }
  .tree { font-family: ui-monospace, monospace; font-size: 12px; line-height: 1.5;
          background: #161616; border: 1px solid #333; border-radius: 8px; padding: 10px;
          min-height: 60vh; overflow: auto; white-space: pre-wrap; }
  .panel { display: flex; flex-direction: column; gap: 10px; }
  .panel section { background: #161616; border: 1px solid #333; border-radius: 8px; padding: 10px; }
  .panel h2 { font-size: 13px; margin: 0 0 8px; }
  #shot { max-width: 100%; border-radius: 6px; }
  .node { cursor: pointer; } .node:hover { background: #26303a; }
  .sel { color: #7fd0ff; } .warn { color: #ffb347; }
  textarea { width: 100%; min-height: 90px; background: #262626; color: #eee;
             border: 1px solid #444; border-radius: 6px; font-family: ui-monospace, monospace; }
</style>
</head>
<body>
<header>
  <h1>AutoHost inspector</h1>
  <button id="refresh">Refresh tree</button>
  <button id="shot-btn">Screenshot</button>
  <span id="status" class="warn"></span>
</header>
<main>
  <div class="tree" id="tree">loading…</div>
  <div class="panel">
    <section>
      <h2>Try a selector</h2>
      <textarea id="selector">{&quot;text&quot;: &quot;&quot;}</textarea>
      <p><button id="try">Find</button> <span id="try-out"></span></p>
    </section>
    <section>
      <h2>Screenshot</h2>
      <img id="shot" alt="screenshot appears here">
    </section>
  </div>
</main>
<script>
const tree = document.getElementById('tree');
const statusEl = document.getElementById('status');

async function api(method, params) {
  const res = await fetch('/api/' + method, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(params || {})
  });
  const data = await res.json();
  if (data.error) throw new Error(data.error.code + ': ' + data.error.message);
  return data.result || {};
}

function esc(s) { const d = document.createElement('span'); d.textContent = s == null ? '' : String(s); return d; }

function renderNode(node, depth, container) {
  if (!node) return;
  const line = document.createElement('div');
  line.className = 'node';
  const label = node.text || node.desc || node.cls || node.pkg || '';
  line.append(esc('  '.repeat(depth) + (node.cls || '?') + ' "' + label + '"'));
  const meta = document.createElement('span');
  meta.className = 'sel';
  meta.textContent = node.sel ? '  ← ' + node.sel : '';
  line.append(meta);
  line.onclick = () => {
    document.getElementById('selector').value = JSON.stringify(node.sel || {text: label});
  };
  container.append(line);
  for (const child of node.children || []) renderNode(child, depth + 1, container);
}

async function refresh() {
  try {
    const snap = await api('device.ui.snapshot', {compact: false});
    tree.textContent = '';
    renderNode(snap.root || snap, 0, tree);
    statusEl.textContent = '';
  } catch (e) { statusEl.textContent = String(e.message || e); }
}

async function shot() {
  try {
    const result = await api('device.screenshot', {});
    const img = document.getElementById('shot');
    const b64 = result.data_b64 || result.bundle_b64 || '';
    img.src = b64 ? 'data:image/png;base64,' + b64 : '';
    if (!b64) statusEl.textContent = 'screenshot unavailable: ' + (result.reason || 'no data');
  } catch (e) { statusEl.textContent = String(e.message || e); }
}

async function trySelector() {
  const out = document.getElementById('try-out');
  try {
    const selector = JSON.parse(document.getElementById('selector').value || '{}');
    const found = await api('device.ui.find', {selector});
    out.textContent = found.node ? 'found' : 'not found';
  } catch (e) { out.textContent = String(e.message || e); }
}

document.getElementById('refresh').onclick = refresh;
document.getElementById('shot-btn').onclick = shot;
document.getElementById('try').onclick = trySelector;
refresh();
setInterval(refresh, 5000);
</script>
</body>
</html>
"""


class InspectorHandler(BaseHTTPRequestHandler):
    """GET / → the page; POST /api/<method> → the Dev API (token injected here, never in JS)."""

    client: DevApiClient
    page: str

    def do_GET(self) -> None:  # noqa: N802 (http.server API)
        if self.path != "/":
            self._json(404, {"error": {"code": "NOT_FOUND", "message": "only / is served"}})
            return
        body = self.page.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802
        if not self.path.startswith("/api/"):
            self._json(404, {"error": {"code": "NOT_FOUND"}})
            return
        method = self.path[len("/api/"):].strip("/")
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            params: dict[str, Any] = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError as exc:
            self._json(400, {"error": {"code": "INVALID_JSON", "message": str(exc)}})
            return
        try:
            result = self.client.call(method, params)
            self._json(200, {"result": result})
        except Exception as exc:  # DevApiError and transport failures — both honest 502s
            code = getattr(exc, "code", "UPSTREAM_ERROR")
            self._json(502, {"error": {"code": code, "message": str(exc)}})

    def _json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        pass  # keep the inspector quiet; the CLI prints the bind line itself


def serve(client: DevApiClient, port: int = DEFAULT_UI_PORT, devapi_port: int | None = None) -> int:
    """Starts the inspector server (blocking). Returns only on KeyboardInterrupt."""
    backend_port = devapi_port or getattr(client, "port", None) or 8080
    handler = type("BoundHandler", (InspectorHandler,), {"client": client, "page": index_html(backend_port)})
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)  # localhost-only by construction
    print(f"inspector: http://127.0.0.1:{port}  (Dev API via 127.0.0.1:{backend_port})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0
