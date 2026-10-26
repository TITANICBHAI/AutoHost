"""autohost bridge (P11-07, ADR-081) — the PC side of the reverse Dev API transport.

The PHONE dials OUT to this listener (no USB, no adb forward). Protocol (mirrors
`core/devapi/ReverseDevApiLink.kt` and `WsFrames.kt` — RFC 6455):

  1. the phone connects as a WS **client** and performs the handshake;
  2. its FIRST frame carries `{"token": "…"}` — the token never rides a URL (ADR-075 rule);
  3. the bridge relays `POST /rpc` bodies as unmasked text frames to the phone and
     relays the phone's (masked) reply back to the HTTP caller.

The bridge exposes a localhost HTTP proxy on `--http` so the existing CLI/MCP tooling keeps
working unchanged: point `autohost --port <proxy-port>` at the bridge and every command rides
the reverse socket.

    autohost bridge --listen 8090 --http 8081 --token <dev-api-token>
"""

from __future__ import annotations

import base64
import hashlib
import json
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
OP_TEXT = 0x1
OP_CLOSE = 0x8
OP_PING = 0x9
OP_PONG = 0xA


# ---- frame codec (mirror of WsFrames.kt; keep semantics identical) --------------------------

def encode(opcode: int, payload: bytes, mask: bool) -> bytes:
    header = bytearray()
    header.append(0x80 | opcode)  # FIN + opcode
    size = len(payload)
    mask_bit = 0x80 if mask else 0
    if size < 126:
        header.append(mask_bit | size)
    elif size < 65536:
        header.append(mask_bit | 126)
        header += struct.pack(">H", size)
    else:
        header.append(mask_bit | 127)
        header += struct.pack(">Q", size)
    if mask:
        key = bytearray(hashlib.md5(payload).digest()[:4])  # mask key content is irrelevant; deterministic keeps tests stable
        masked = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
        return bytes(header) + bytes(key) + masked
    return bytes(header) + payload


def encode_text(payload: bytes, mask: bool) -> bytes:
    return encode(OP_TEXT, payload, mask)


class Closed(Exception):
    pass


def read_exact(sock: socket.socket, count: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < count:
        chunk = sock.recv(count - len(chunks))
        if not chunk:
            raise Closed("peer closed")
        chunks += chunk
    return bytes(chunks)


def read_frame(sock: socket.socket, expect_masked: bool) -> tuple[int, bytes]:
    """Reads one frame. Server-role readers expect MASKED frames (client→server) and vice versa."""
    header = read_exact(sock, 2)
    opcode = header[0] & 0x0F
    masked = bool(header[1] & 0x80)
    if masked != expect_masked:
        raise Closed(f"frame mask mismatch (want masked={expect_masked})")
    size = header[1] & 0x7F
    if size == 126:
        size = struct.unpack(">H", read_exact(sock, 2))[0]
    elif size == 127:
        size = struct.unpack(">Q", read_exact(sock, 8))[0]
    if size > 8 * 1024 * 1024:
        raise Closed("frame exceeds the 8 MiB transport cap")
    mask = read_exact(sock, 4) if masked else b""
    payload = read_exact(sock, size) if size else b""
    if masked:
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    return opcode, payload


def handshake_server(sock: socket.socket) -> None:
    """Reads the phone's client handshake and answers the 101 (bridge = WS server role)."""
    key: str | None = None
    while True:
        line = _read_line(sock)
        if line == "":
            break
        if line.lower().startswith("sec-websocket-key:"):
            key = line.split(":", 1)[1].strip()
    if not key:
        raise Closed("handshake without Sec-WebSocket-Key")
    accept = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
    )
    sock.sendall(response.encode())


def _read_line(sock: socket.socket) -> str:
    line = bytearray()
    while True:
        byte = read_exact(sock, 1)
        line += byte
        if byte == b"\n":
            return line.decode().rstrip("\r\n")


# ---- the bridge ------------------------------------------------------------------------------

class Bridge:
    """Accepts ONE phone connection at a time and proxies HTTP→WS on localhost."""

    def __init__(self, listen_port: int, http_port: int, expected_token: str) -> None:
        self.listen_port = listen_port
        self.http_port = http_port
        self.expected_token = expected_token
        self._phone: socket.socket | None = None
        self._phone_lock = threading.Lock()
        self._server: socket.socket | None = None
        self._running = False
        self.state = "off"
        self.last_error: str | None = None
        self.handshakes = 0
        self.auth_failures = 0

    # phone side -----------------------------------------------------------------------------

    def serve_forever(self) -> None:
        self._running = True
        self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server.bind(("0.0.0.0", self.listen_port))  # the phone is on the LAN by definition
        self._server.listen(1)
        self.state = "listening"
        print(f"bridge: listening on 0.0.0.0:{self.listen_port} (phone dials here)")
        print(f"bridge: http proxy on 127.0.0.1:{self.http_port} — point 'autohost --port {self.http_port}' at it")
        while self._running:
            try:
                client, addr = self._server.accept()
            except OSError:
                break
            with self._phone_lock:
                if self._phone is not None:
                    try:
                        client.close()
                    except OSError:
                        pass
                    continue  # one phone at a time; the current one wins
            self.state = "handshaking"
            try:
                handshake_server(client)
                opcode, payload = read_frame(client, expect_masked=True)  # first frame = the token
                if opcode != OP_TEXT:
                    raise Closed("first frame is not text")
                token = json.loads(payload.decode()).get("token", "")
                if token != self.expected_token:
                    self.auth_failures += 1
                    raise Closed("token mismatch — refusing the phone")
                self.handshakes += 1
                with self._phone_lock:
                    self._phone = client
                self.state = "connected"
                print(f"bridge: phone connected from {addr[0]}")
                # No competing reader loop here: `relay` is the ONLY reader on the phone socket
                # (single-flight under the lock), so a request/response pair can never be stolen.
                # Disconnection is noticed by the next relay (Closed) or the idle check below.
                while self._running:
                    time.sleep(0.25)
                    with self._phone_lock:
                        if self._phone is None:
                            break
                self.state = "listening"
            except (Closed, OSError) as exc:
                self.last_error = str(exc)
                self.state = "disconnected"
                print(f"bridge: phone disconnected ({exc})")
            finally:
                with self._phone_lock:
                    self._phone = None
                try:
                    client.close()
                except OSError:
                    pass
                self.state = "listening"

    def stop(self) -> None:
        self._running = False
        try:
            if self._server:
                self._server.close()
        except OSError:
            pass
        with self._phone_lock:
            try:
                if self._phone:
                    self._phone.close()
            except OSError:
                pass
            self._phone = None

    def relay(self, body: str) -> str:
        """One HTTP request → one WS request → the reply. Raises Closed when no phone is up."""
        with self._phone_lock:
            phone = self._phone
        if phone is None:
            raise Closed("no phone connected")
        try:
            phone.sendall(encode_text(body.encode("utf-8"), mask=False))
            while True:
                opcode, payload = read_frame(phone, expect_masked=True)
                if opcode == OP_TEXT:
                    return payload.decode("utf-8")
                if opcode == OP_CLOSE:
                    raise Closed("phone closed mid-request")
                # pings are ignored (the phone link pongs its bridge, not this direction)
        except (Closed, OSError):
            # The phone went away mid-request: forget it so the accept loop can take the next one.
            with self._phone_lock:
                if self._phone is phone:
                    self._phone = None
                    self.state = "listening"
            raise


# ---- the localhost HTTP proxy ------------------------------------------------------------------

def start_http_proxy(bridge: Bridge, port: int) -> ThreadingHTTPServer:
    class ProxyHandler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length).decode("utf-8") if length else "{}"
            try:
                reply = bridge.relay(body)
                payload = reply.encode("utf-8")
                self.send_response(200)
            except Closed as exc:
                payload = json.dumps({"error": {"code": "BRIDGE_DOWN", "message": str(exc)}}).encode("utf-8")
                self.send_response(502)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, format: str, *args: object) -> None:  # noqa: A002
            pass

    server = ThreadingHTTPServer(("127.0.0.1", port), ProxyHandler)  # the proxy is localhost-only
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def run(listen_port: int, http_port: int, token: str) -> int:
    bridge = Bridge(listen_port, http_port, token)
    start_http_proxy(bridge, http_port)
    try:
        bridge.serve_forever()
    except KeyboardInterrupt:
        bridge.stop()
    return 0
