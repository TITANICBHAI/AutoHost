#!/usr/bin/env python3
"""P13-06 (ADR-090): the PC stub model — dev tooling for AutoHost's agent runtime.

A Python-stdlib HTTP server that plays an OpenAI-compatible chat-completions endpoint:

    POST /v1/chat/completions   -> the next assistant message from the JSON script

The script is a JSON file: a list of entries, each either
    {"text": "..."}                                -> a plain assistant message
    {"tool_calls": [{"id","name","arguments"}]}    -> an assistant tool-call turn
plus optional fault injections, per entry or global:
    {"fault": "429" | "500" | "slow" | "bad_json" | "bad_args" | "drop", "then": {...}}
Every request body is appended to a JSONL log (given --log PATH) so tests can assert on what
the gateway actually sent. Malformed requests get a 400; unknown paths a 404.

Run on a PC; the device reaches it via `adb reverse tcp:<port> tcp:<port>` and the base URL
http://127.0.0.1:<port>/v1 (requires the ai_cleartext_loopback switch — G13-b/d; under owner
decision D-24/D-25 these runs are skipped, the tool exists for when they are re-enabled).

Usage:
    python testing/stub-model/stub_model.py --port 8080 --script script.json --log requests.jsonl
"""
from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"script": [], "cursor": 0, "log": None, "lock": threading.Lock()}


def next_entry():
    with STATE["lock"]:
        if STATE["cursor"] >= len(STATE["script"]):
            return None
        entry = STATE["script"][STATE["cursor"]]
        STATE["cursor"] += 1
        return entry


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # quiet
        pass

    def _send(self, status: int, payload: dict | None, raw: bytes | None = None):
        body = raw if raw is not None else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/ping":
            self._send(200, {"status": "ok"})
        else:
            self._send(404, {"error": {"message": "unknown path"}})

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self._send(404, {"error": {"message": "unknown path"}})
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        with STATE["lock"]:
            if STATE["log"]:
                STATE["log"].write(json.dumps({"body": json.loads(body or b"{}")}) + "\n")
                STATE["log"].flush()
        try:
            request = json.loads(body)
        except Exception:
            self._send(400, {"error": {"message": "request is not JSON"}})
            return
        if not isinstance(request, dict) or "messages" not in request or "model" not in request:
            self._send(400, {"error": {"message": "request misses model or messages"}})
            return

        entry = next_entry()
        if entry is None:
            self._send(500, {"error": {"message": "script exhausted"}})
            return
        fault = entry.get("fault")
        if fault == "429":
            self._send(429, {"error": {"message": "rate limited"}})
            return
        if fault == "500":
            self._send(500, {"error": {"message": "server error"}})
            return
        if fault == "slow":
            time.sleep(3.0)
        if fault == "drop":
            self.close_connection = True
            return

        payload = entry.get("then", entry)
        message: dict = {}
        if "text" in payload:
            message["content"] = payload["text"]
        if "tool_calls" in payload:
            message["tool_calls"] = [
                {"id": c["id"], "type": "function",
                 "function": {"name": c["name"], "arguments": c["arguments"]}}
                for c in payload["tool_calls"]
            ]
        if fault == "bad_json":
            self._send(200, None, raw=b"{not json")
            return
        if fault == "bad_args":
            message["tool_calls"] = [
                {"id": "bad", "type": "function", "function": {"name": "t", "arguments": "{not json"}}]
            message["content"] = None
        response = {
            "choices": [{"message": message, "finish_reason": payload.get("finish_reason", "tool_calls" if "tool_calls" in payload else "stop")}],
        }
        if "usage" in payload:
            response["usage"] = payload["usage"]
        self._send(200, response)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--script", required=True, help="JSON file: the replay script")
    ap.add_argument("--log", help="JSONL file to append every request body to")
    args = ap.parse_args(argv)

    with open(args.script, encoding="utf-8") as f:
        STATE["script"] = json.load(f)
    STATE["log"] = open(args.log, "a", encoding="utf-8") if args.log else None

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"stub model on http://127.0.0.1:{args.port}/v1 — {len(STATE['script'])} scripted entries")
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
