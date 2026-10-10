#!/usr/bin/env python3
"""Tern phone bridge: token-authenticated HTTP endpoint over the public `tern` CLI.

Lets the Tern Companion Android APK list sessions and drive blocks on this PC
(type or submit text, send keys, read the screen, start/stop sessions). The phone reaches
it over Tailscale (recommended, encrypted) or an SSH tunnel; the server itself
binds loopback only unless you pass --expose.

Run:  python3 tern_bridge.py            (token stored privately in ~/.tern/bridge-token)
      python3 tern_bridge.py --port 9260 --expose   # bind 0.0.0.0 (only on trusted networks)
"""

import argparse
import hmac
import json
import re
import socket
import secrets
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

TOKEN_FILE = Path.home() / ".tern" / "bridge-token"
MAX_BODY = 64 * 1024
MAX_OUT = 1024 * 1024
CMD_TIMEOUT = 15
NAME_PATTERN = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}\Z")


def load_token() -> str:
    if TOKEN_FILE.exists():
        token = TOKEN_FILE.read_text().strip()
        if len(token) >= 32:
            return token
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    token = secrets.token_hex(32)
    TOKEN_FILE.write_text(token + "\n")
    TOKEN_FILE.chmod(0o600)
    return token


def run_tern(args: list[str], stdin_text: str | None = None) -> tuple[int, str]:
    proc = subprocess.run(
        ["tern", *args],
        input=stdin_text,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=CMD_TIMEOUT,
    )
    out = proc.stdout or ""
    if len(out.encode("utf-8")) > MAX_OUT:
        raise RuntimeError("tern output exceeds limit")
    if proc.returncode != 0:
        err = (proc.stderr or "tern failed").strip().splitlines()
        raise RuntimeError(err[-1] if err else f"tern exited {proc.returncode}")
    return proc.returncode, out


class Handler(BaseHTTPRequestHandler):
    server_version = "TernBridge/1.0"
    token = ""

    def log_message(self, fmt, *args):  # quiet
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(CMD_TIMEOUT)

    def _authed(self) -> bool:
        header = self.headers.get("Authorization", "")
        return hmac.compare_digest(header.encode("utf-8"), f"Bearer {self.token}".encode("utf-8"))

    def _send(self, code: int, payload, content_type="application/json"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict:
        if self.headers.get("Transfer-Encoding") is not None:
            raise ValueError("transfer encoding is not supported")
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
            raise ValueError("bad body size")
        length = int(lengths[0])
        if length <= 0 or length > MAX_BODY:
            raise ValueError("bad body size")
        raw = self.rfile.read(length)
        if len(raw) != length:
            raise ValueError("incomplete body")
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("body must be a JSON object")
        return data

    def do_GET(self):
        if not self._authed():
            self._send(401, {"error": "unauthorized"})
            return
        try:
            if self.path == "/health":
                self._send(200, {"ok": True})
            elif self.path == "/sessions":
                _, out = run_tern(["ls", "--json"])
                self._send(200, out.encode("utf-8"), content_type="application/json")
            else:
                self._send(404, {"error": "not found"})
        except Exception as e:
            self._error(e)

    def do_POST(self):
        if not self._authed():
            self._send(401, {"error": "unauthorized"})
            return
        try:
            body = self._read_json()
            if self.path == "/send":
                self._send(200, self._send_keys(body))
            elif self.path == "/capture":
                self._send(200, self._capture(body), content_type="text/plain; charset=utf-8")
            elif self.path == "/new":
                self._send(200, self._new_session(body))
            elif self.path == "/kill":
                self._send(200, self._kill_session(body))
            else:
                self._send(404, {"error": "not found"})
        except Exception as e:
            self._error(e)

    def _error(self, error: Exception):
        if isinstance(error, (ValueError, UnicodeError)):
            self._send(400, {"error": str(error)})
        elif isinstance(error, subprocess.TimeoutExpired):
            self._send(504, {"error": "tern timed out"})
        elif isinstance(error, socket.timeout):
            self._send(408, {"error": "request timed out"})
        elif isinstance(error, RuntimeError):
            self._send(409, {"error": str(error)})
        else:
            self._send(500, {"error": f"bridge error: {error}"})

    def _block(self, body: dict) -> str:
        block = body.get("block")
        if not isinstance(block, str) or len(block) > 129:
            raise ValueError("block must be SESSION/BLOCK or a block id")
        parts = block.split("/")
        valid = (len(parts) == 1 and re.fullmatch(r"[0-9]+", block)
                 or len(parts) == 2 and NAME_PATTERN.fullmatch(parts[1])
                 and 0 < len(parts[0]) <= 64 and parts[0] == parts[0].strip()
                 and not parts[0].startswith("-")
                 and all(ord(c) >= 32 and ord(c) != 127 for c in parts[0]))
        if not valid:
            raise ValueError("block must be SESSION/BLOCK or a block id")
        block.encode("utf-8")
        return block

    def _send_keys(self, body: dict) -> dict:
        block = self._block(body)
        kind = body.get("kind")
        if kind in ("text", "submit"):
            text = self._text(body.get("value"), 4096)
            # The CLI writes text verbatim to the PTY; CR submits in the same write.
            run_tern(["send", block, "text", text + "\r" if kind == "submit" else text])
        elif kind == "keys":
            keys = body.get("value")
            if not isinstance(keys, str) or not keys or len(keys) > 64:
                raise ValueError("value must be a key combo like Enter or ctrl+c")
            if keys.startswith("-") or any(ord(c) < 33 or ord(c) > 126 or c in ";|&$`<>\\\"" for c in keys):
                raise ValueError("invalid key combo")
            run_tern(["send", block, "keys", keys])
        elif kind == "paste":
            text = self._text(body.get("value"), 65536)
            run_tern(["send", block, "paste", text], stdin_text=text)
        else:
            raise ValueError("kind must be text, submit, keys or paste")
        return {"ok": True}

    def _text(self, text, limit: int) -> str:
        if not isinstance(text, str) or not text or len(text) > limit or "\0" in text:
            raise ValueError(f"value must be 1-{limit} chars of text without NUL")
        text.encode("utf-8")  # Reject lone JSON surrogate escapes before invoking the CLI.
        return text

    def _capture(self, body: dict) -> bytes:
        block = self._block(body)
        # OMP paints a native Tern surface, not the underlying shell/ANSI grid.
        # --surfaces extracts its current main-region text through the public CLI.
        _, out = run_tern(["capture", block, "--surfaces"])
        return out.encode("utf-8")

    def _new_session(self, body: dict) -> dict:
        name = body.get("name")
        if not isinstance(name, str) or not NAME_PATTERN.fullmatch(name):
            raise ValueError("name must be 1-64 letters, digits, dots, underscores or hyphens")
        args = ["new", "session", name]
        cwd = body.get("cwd")
        if cwd:
            if (not isinstance(cwd, str) or len(cwd) > 4096 or "\0" in cwd
                    or not cwd.startswith("/") or ".." in cwd.split("/")):
                raise ValueError("cwd must be an absolute path without ..")
            c = cwd
            c.encode("utf-8")
            args += ["--cwd", c]
        run_tern(args)
        return {"ok": True}

    def _kill_session(self, body: dict) -> dict:
        name = body.get("session")
        if not isinstance(name, str) or not NAME_PATTERN.fullmatch(name):
            raise ValueError("session must be a session name")
        run_tern(["kill", "session", name])
        return {"ok": True}


def _tailscale_ip() -> str | None:
    try:
        out = subprocess.run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=5)
        ip = out.stdout.strip().splitlines()
        return ip[0] if ip and out.returncode == 0 else None
    except Exception:
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=9260)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--expose", action="store_true",
                        help="bind 0.0.0.0 instead of loopback (phone on same LAN; plaintext HTTP)")
    opts = parser.parse_args()

    token = load_token()
    Handler.token = token
    host = "0.0.0.0" if opts.expose else opts.host
    httpd = ThreadingHTTPServer((host, opts.port), Handler)
    httpd.daemon_threads = True
    print(f"tern bridge listening on {host}:{opts.port}")
    print(f"token file: {TOKEN_FILE} (keep private; not printed)")
    if host == "127.0.0.1":
        ts_ip = _tailscale_ip()
        if ts_ip:
            print(f"phone can’t reach 127.0.0.1 — for phone access run:")
            print(f"  python3 tern_bridge.py --host {ts_ip}")
        else:
            print("phone can’t reach 127.0.0.1 — for phone access pass --host <PC’s LAN/Tailscale IP>")
    else:
        print("phone: add host with this address and token; prefer Tailscale (plaintext HTTP otherwise)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")
    return 0


if __name__ == "__main__":
    sys.exit(main())
