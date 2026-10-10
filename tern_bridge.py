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
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from conversation import read_conversation

TOKEN_FILE = Path.home() / ".tern" / "bridge-token"
MAX_BODY = 64 * 1024
MAX_OUT = 1024 * 1024
CMD_TIMEOUT = 15
NAME_PATTERN = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}\Z")
SESSION_FILE_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T[^/]+_[0-9a-f-]{36}\.jsonl\Z")
INPUT_LOCK = threading.Lock()
PAGE_BYTES = 192 * 1024


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


def omp_session(block: str) -> Path | None:
    """Resolve the pane's actual foreground OMP, never the newest file in its cwd."""
    _, output = run_tern(["process", block, "--json"])
    foreground = json.loads(output).get("foreground") or {}
    if foreground.get("name") not in ("omp", "omp-custom"):
        return None
    pid = foreground.get("pid")
    if not isinstance(pid, int) or pid <= 0:
        raise RuntimeError("OMP process identity unavailable")
    fd_dir = Path(f"/proc/{pid}/fd")
    if fd_dir.is_dir():
        paths = set()
        for fd in fd_dir.iterdir():
            try:
                paths.add(fd.resolve(strict=True))
            except OSError:
                continue
    else:
        # -b avoids macOS filesystem stat stalls; -nP avoids DNS/service lookups.
        proc = subprocess.run(["/usr/sbin/lsof" if sys.platform == "darwin" else "lsof",
                               "-nP", "-b", "-p", str(pid), "-Fn"],
                              capture_output=True, text=True, timeout=4)
        if proc.returncode != 0:
            raise RuntimeError("Cannot inspect OMP's open transcript; install lsof")
        paths = {Path(line[1:]) for line in proc.stdout.splitlines() if line.startswith("n/")}
    candidates = [p for p in paths if SESSION_FILE_PATTERN.fullmatch(p.name)]
    if len(candidates) != 1:
        raise RuntimeError("OMP's active transcript is not ready or is ambiguous")
    return candidates[0]


def accepted_prompt(path: Path, offset: int, text: str) -> bool:
    """Confirm only a newly persisted user message, not a PTY echo or an old send."""
    with path.open("rb") as stream:
        stream.seek(offset)
        tail = stream.read()
    for line in tail.split(b"\n")[:-1]:
        try:
            entry = json.loads(line)
        except (ValueError, UnicodeError):
            continue  # offset may precede completion of another record's write
        message = entry.get("message", {})
        if entry.get("type") != "message" or message.get("role") != "user" or message.get("synthetic"):
            continue
        content = message.get("content")
        if isinstance(content, str) and content == text:
            return True
        if isinstance(content, list) and any(
                isinstance(part, dict) and part.get("type") == "text" and part.get("text") == text
                for part in content):
            return True
    return False


def conversation_page(payload: dict, before=None) -> dict:
    """Bound whole-message pages without discarding older conversation history."""
    if before is not None and (type(before) is not int or before < 0):
        raise ValueError("before must be a non-negative message index")
    messages = payload["messages"]
    end = len(messages) if before is None else min(before, len(messages))
    start, size = end, 2
    while start > 0:
        cost = len(json.dumps(messages[start - 1], ensure_ascii=False).encode("utf-8")) + 2
        if start < end and size + cost > PAGE_BYTES:
            break
        start -= 1
        size += cost
    return {**payload, "messages": messages[start:end],
            "start": start, "end": end, "total": len(messages)}


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
                fmt = self._capture_format(body)
                if fmt == "conversation":
                    block = self._block(body)
                    path = omp_session(block)
                    payload = (conversation_page(read_conversation(path), body.get("before")) if path else
                               {"type": "terminal", "format": "html",
                                "html": self._capture(body, "html").decode("utf-8")})
                    encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                    if len(encoded) > MAX_OUT:
                        raise RuntimeError("Conversation exceeds the phone's capture limit")
                    self._send(200, encoded)
                else:
                    content_type = "text/html; charset=utf-8" if fmt == "html" else "text/plain; charset=utf-8"
                    self._send(200, self._capture(body, fmt), content_type=content_type)
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
            if kind == "submit":
                if "\x1b" in text:
                    raise ValueError("message must not contain terminal escape characters")
                with INPUT_LOCK:
                    path = omp_session(block)
                    offset = path.stat().st_size if path else 0
                    # Native Kitty Enter can leave OMP's draft unsubmitted. One
                    # bracketed paste preserves newlines; literal CR submits it.
                    value = "\x1b[200~" + text + "\x1b[201~\r" if path else text + "\r"
                    run_tern(["send", block, "text", value])
                    # ponytail: one global lock covers acceptance; use per-pane
                    # locks if simultaneous phones cause cross-pane contention.
                    if path:
                        deadline = time.monotonic() + 4
                        while not accepted_prompt(path, offset, text):
                            if time.monotonic() >= deadline:
                                raise RuntimeError("OMP acceptance not confirmed; check the desktop before retrying")
                            time.sleep(0.1)
                        return {"ok": True, "accepted": True}
            else:
                run_tern(["send", block, "text", text])
        elif kind == "keys":
            keys = body.get("value")
            if not isinstance(keys, str) or not keys or len(keys) > 64:
                raise ValueError("value must be a key combo like Enter or ctrl+c")
            if keys.startswith("-") or any(ord(c) < 33 or ord(c) > 126 or c in ";|&$`<>\\\"" for c in keys):
                raise ValueError("invalid key combo")
            if keys == "Enter":
                run_tern(["send", block, "text", "\r"])
            else:
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

    def _capture_format(self, body: dict) -> str:
        fmt = body.get("format", "text")
        if fmt not in ("text", "html", "conversation"):
            raise ValueError("format must be text, html or conversation")
        return fmt

    def _capture(self, body: dict, fmt: str) -> bytes:
        block = self._block(body)
        # OMP paints a native Tern surface, not the underlying shell/ANSI grid.
        # --surfaces extracts its current main-region text through the public CLI;
        # --html returns that surface as styled HTML for phone rendering, and
        # --scrollback adds output that has scrolled off the visible screen.
        args = ["capture", block]
        if fmt == "html":
            args.append("--html")
        args += ["--scrollback", "--surfaces"]
        _, out = run_tern(args)
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
        # New sessions default to OMP, not the bare login shell.
        run_tern(args + ["--", "omp"])
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
