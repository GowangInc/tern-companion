#!/usr/bin/env python3
"""Tern phone bridge: token-authenticated HTTP endpoint over the public `tern` CLI.

Lets the Tern Companion Android APK list sessions and drive blocks on this PC
(type or submit text, send keys, read the screen, start/stop sessions) and
attach files to a prompt: `POST /upload` stores one original for the pane's own
OMP session, `GET /attachment` returns it to the authenticated phone, and a
submit carrying upload ids appends their native OMP `@file` mentions to the same
prompt. The phone reaches it over Tailscale (recommended, encrypted) or an SSH
tunnel; the server itself binds loopback only unless you pass --expose.

Run:  python3 tern_bridge.py            (token stored privately in ~/.tern/bridge-token)
      python3 tern_bridge.py --port 9260 --expose   # bind 0.0.0.0 (only on trusted networks)
"""

import argparse
import ctypes
import hmac
import json
import os
import re
import socket
import secrets
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import phone_uploads
from conversation import read_conversation, read_session_header

TOKEN_FILE = Path.home() / ".tern" / "bridge-token"
MAX_BODY = 64 * 1024
MAX_OUT = 1024 * 1024
CMD_TIMEOUT = 15
NAME_PATTERN = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}\Z")
SESSION_FILE_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T[^/]+_[0-9a-f-]{36}\.jsonl\Z")
TRANSCRIPT_UUID_PATTERN = re.compile(r"_([0-9a-f-]{36})\.jsonl\Z")
OMP_PROCESS_NAMES = ("omp", "omp-custom")
INLINE_IMAGE_TYPES = frozenset({"image/png", "image/jpeg", "image/webp", "image/gif"})
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


def _open_paths(pid: int) -> set[Path]:
    """The paths one process holds open, from its own file table only."""
    fd_dir = Path(f"/proc/{pid}/fd")
    if fd_dir.is_dir():
        paths = set()
        for fd in fd_dir.iterdir():
            try:
                paths.add(fd.resolve(strict=True))
            except OSError:
                continue
        return paths
    # -b avoids macOS filesystem stat stalls; -nP avoids DNS/service lookups.
    proc = subprocess.run(["/usr/sbin/lsof" if sys.platform == "darwin" else "lsof",
                           "-nP", "-b", "-p", str(pid), "-Fn"],
                          capture_output=True, text=True, timeout=4)
    if proc.returncode != 0:
        raise RuntimeError("Cannot inspect OMP's open transcript; install lsof")
    return {Path(line[1:]) for line in proc.stdout.splitlines() if line.startswith("n/")}


def _exe_path(pid: int) -> str | None:
    """The executable one process was started from, or None when it is unreadable."""
    if sys.platform == "darwin":
        try:
            libc = ctypes.CDLL("/usr/lib/libSystem.B.dylib")
            buffer = ctypes.create_string_buffer(4096)  # PROC_PIDPATHINFO_MAXSIZE
            length = libc.proc_pidpath(ctypes.c_int(pid), buffer, ctypes.c_uint32(len(buffer)))
        except (OSError, AttributeError, ValueError):
            return None
        return buffer.value.decode("utf-8", "replace") if length > 0 else None
    try:
        return os.readlink(f"/proc/{pid}/exe")
    except OSError:
        return None


def _is_omp_executable(exe: str | None) -> bool:
    """Whether a process's own executable is OMP, including Tern's managed versions."""
    if not exe:
        return False
    program = Path(exe)
    if program.name.lower() in OMP_PROCESS_NAMES:
        return True
    return program.parent.name in ("bin", "versions") and program.parent.parent.name == "omp"


def _omp_program(foreground: dict, pid: int | None) -> bool:
    """Whether the pane's foreground program is OMP itself, never its shell.

    Tern-managed OMP renames its process to the installed version (for example
    `18.8.7`), so identity comes from the executable it was started as, its
    argv, its reported name, or Tern's own versioned install path. A shell,
    editor or pager that merely holds an OMP transcript open is never OMP.
    """
    name = foreground.get("name")
    if isinstance(name, str) and name.lower() in OMP_PROCESS_NAMES:
        return True
    argv = foreground.get("argv")
    if isinstance(argv, list) and argv and isinstance(argv[0], str) \
            and Path(argv[0]).name.lower() in OMP_PROCESS_NAMES:
        return True
    return _is_omp_executable(_exe_path(pid) if pid is not None else None)


def _proc_stat_ids(pid: int) -> tuple[int, int] | None:
    """One process's (process group, terminal foreground group), or None off Linux."""
    try:
        stat = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    except OSError:
        return None
    # comm sits inside the last parenthesised field and may itself hold spaces.
    fields = stat.rsplit(")", 1)[-1].split()
    try:
        return int(fields[2]), int(fields[5])  # pgrp, tpgid
    except (IndexError, ValueError):
        return None


def _proc_argv(pid: int) -> list[str]:
    """One process's argv exactly as the kernel reports it, or [] when unreadable."""
    try:
        raw = Path(f"/proc/{pid}/cmdline").read_bytes()
    except OSError:
        return []
    return [part.decode("utf-8", "replace") for part in raw.split(b"\0") if part]


def _group_pids(pgid: int) -> list[int]:
    """Every live process in one process group, from the kernel's own /proc table."""
    try:
        entries = os.listdir("/proc")
    except OSError:
        return []
    members = []
    for entry in entries:
        if entry.isdigit():
            ids = _proc_stat_ids(int(entry))
            if ids is not None and ids[0] == pgid:
                members.append(int(entry))
    return members


def _omp_kernel_identity(pid: int) -> bool:
    """Whether one live process is OMP, from the kernel's records, never held files."""
    if _is_omp_executable(_exe_path(pid)):
        return True
    argv = _proc_argv(pid)
    return bool(argv) and Path(argv[0]).name.lower() in OMP_PROCESS_NAMES


def _foreground_group_omp(pid: int) -> int | None:
    """The single OMP inside the pane's terminal-foreground process group, if any.

    A shell reports itself as the pane's foreground program while OMP runs as a
    child of that shell inside the same foreground process group. The group
    qualifies only while it actually holds the terminal (pgrp == tpgid), so a
    background or unrelated OMP never does. More than one eligible OMP is
    ambiguous and fails loudly.
    """
    ids = _proc_stat_ids(pid)
    if ids is None:
        return None
    pgid, tpgid = ids
    if pgid <= 0 or pgid != tpgid:
        return None
    members = [member for member in _group_pids(pgid)
               if member != pid and _omp_kernel_identity(member)]
    if len(members) > 1:
        raise RuntimeError("the pane's foreground group holds multiple OMP processes")
    return members[0] if members else None


def _omp_pid(foreground: dict) -> int | None:
    """The pane's OMP process id, or None when the pane is not running OMP.

    OMP qualifies as the pane's own foreground program or as the single OMP
    inside that program's terminal-foreground process group (a shell that
    launched it); anything less keeps the pane's ordinary terminal capture.
    """
    pid = foreground.get("pid")
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        pid = None
    if _omp_program(foreground, pid):
        if pid is None:
            raise RuntimeError("OMP process identity unavailable")
        return pid
    if pid is None:
        return None
    return _foreground_group_omp(pid)


def _session_of(path: Path) -> str:
    """The session id a validated transcript's filename carries."""
    match = TRANSCRIPT_UUID_PATTERN.search(path.name)
    return match.group(1) if match else ""


def _transcript_session(path: Path) -> str | None:
    """The candidate's session id, or None when its header does not own its filename."""
    match = TRANSCRIPT_UUID_PATTERN.search(path.name)
    if match is None:
        return None
    try:
        header = read_session_header(path)
    except OSError:
        return None
    return header if header == match.group(1) else None


def omp_session(block: str) -> Path | None:
    """Resolve the pane's actual foreground OMP transcript, never a newest file.

    The pane must be running OMP itself — directly, or as the single OMP inside
    its shell's terminal-foreground process group — and that exact process must
    hold one canonical transcript open whose header session id agrees with its
    filename. Anything less is an ordinary terminal, which keeps its native
    capture.
    """
    _, output = run_tern(["process", block, "--json"])
    foreground = json.loads(output).get("foreground") or {}
    pid = _omp_pid(foreground)
    if pid is None:
        return None
    confirmed = [p for p in _open_paths(pid)
                 if SESSION_FILE_PATTERN.fullmatch(p.name) and _transcript_session(p)]
    if not confirmed:
        return None
    if len(confirmed) > 1:
        raise RuntimeError("OMP's active transcript is ambiguous")
    return confirmed[0]


def accepted_prompt(path: Path, offset: int, text: str) -> bool:
    """Confirm only a newly persisted user message, not a PTY echo or an old send."""
    # OMP's submit handler applies JavaScript String.trim(); internal whitespace stays intact.
    text = text.strip(" \t\n\r\v\f\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005"
                      "\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff")
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

    def _route(self) -> tuple[str, dict]:
        parts = urllib.parse.urlsplit(self.path)
        return parts.path, urllib.parse.parse_qs(parts.query, keep_blank_values=True)

    def _query_one(self, query: dict, key: str) -> str:
        values = query.get(key)
        if not isinstance(values, list) or len(values) != 1:
            raise ValueError(f"{key} must be given exactly once")
        return values[0]

    def _body_length(self, limit: int) -> int:
        if self.headers.get("Transfer-Encoding") is not None:
            raise ValueError("transfer encoding is not supported")
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
            raise ValueError("bad body size")
        length = int(lengths[0])
        if length <= 0 or length > limit:
            raise ValueError("bad body size")
        return length

    def _read_json(self) -> dict:
        length = self._body_length(MAX_BODY)
        raw = self.rfile.read(length)
        if len(raw) != length:
            raise ValueError("incomplete body")
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("body must be a JSON object")
        return data

    def _send_file(self, path: Path, mime: str):
        """Stream one stored original; only raster images keep their type."""
        body_type = mime if mime in INLINE_IMAGE_TYPES else "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", body_type)
        self.send_header("Content-Length", str(path.stat().st_size))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "private, no-store")
        self.end_headers()
        with path.open("rb") as stream:
            while True:
                chunk = stream.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def _attachment(self, query: dict):
        """Serve one stored original for the exact session its block resolves to."""
        block = self._block({"block": self._query_one(query, "block")})
        upload_id = self._query_one(query, "id")
        if not phone_uploads.ID_PATTERN.fullmatch(upload_id):
            raise ValueError("invalid attachment id")
        path = omp_session(block)
        if path is None:
            raise RuntimeError("this block is not an OMP session")
        session = _session_of(path)
        record = phone_uploads.metadata(session, upload_id)
        original = phone_uploads.original(session, upload_id)
        if record is None or original is None:
            self._send(404, {"error": "attachment not found"})
            return
        self._send_file(original, record["mime"])

    def _upload(self, query: dict):
        """Store one phone upload for the exact session its block resolves to."""
        block = self._block({"block": self._query_one(query, "block")})
        name = self._query_one(query, "name")
        mime = query.get("mime", [""])
        if not isinstance(mime, list) or len(mime) != 1:
            raise ValueError("mime must be given at most once")
        length = self._body_length(phone_uploads.MAX_UPLOAD_BYTES)
        path = omp_session(block)
        if path is None:
            raise RuntimeError("this block is not an OMP session")
        record = phone_uploads.store(self.rfile, length, session=_session_of(path),
                                     name=name, mime=mime[0])
        self._send(200, record)

    def do_GET(self):
        if not self._authed():
            self._send(401, {"error": "unauthorized"})
            return
        try:
            route, query = self._route()
            if route == "/health":
                self._send(200, {"ok": True})
            elif route == "/sessions":
                _, out = run_tern(["ls", "--json"])
                self._send(200, out.encode("utf-8"), content_type="application/json")
            elif route == "/attachment":
                self._attachment(query)
            else:
                self._send(404, {"error": "not found"})
        except Exception as e:
            self._error(e)

    def do_POST(self):
        if not self._authed():
            self._send(401, {"error": "unauthorized"})
            return
        try:
            route, query = self._route()
            if route == "/upload":
                self._upload(query)
            elif route == "/send":
                self._send(200, self._send_keys(self._read_json()))
            elif route == "/capture":
                body = self._read_json()
                fmt = self._capture_format(body)
                if fmt == "conversation":
                    block = self._block(body)
                    path = omp_session(block)
                    if path is None:
                        payload = {"type": "terminal", "format": "html",
                                   "html": self._capture(body, "html").decode("utf-8")}
                    else:
                        payload = conversation_page(
                            phone_uploads.enrich_conversation(read_conversation(path)),
                            body.get("before"))
                    encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                    if len(encoded) > MAX_OUT:
                        raise RuntimeError("Conversation exceeds the phone's capture limit")
                    self._send(200, encoded)
                else:
                    content_type = "text/html; charset=utf-8" if fmt == "html" else "text/plain; charset=utf-8"
                    self._send(200, self._capture(body, fmt), content_type=content_type)
            elif route == "/new":
                self._send(200, self._new_session(self._read_json()))
            elif route == "/kill":
                self._send(200, self._kill_session(self._read_json()))
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
        attachments = self._attachment_ids(body)
        if kind in ("text", "keys", "paste") and attachments:
            raise ValueError("attachments are only supported for submit")
        if kind in ("text", "submit"):
            text = self._text(body.get("value"), 4096, allow_empty=bool(attachments))
            if kind == "submit":
                if "\x1b" in text:
                    raise ValueError("message must not contain terminal escape characters")
                with INPUT_LOCK:
                    path = omp_session(block)
                    # A file mention rides in the same prompt as the draft, so
                    # OMP auto-reads the original the phone uploaded.
                    prompt = text
                    if attachments:
                        if path is None:
                            raise RuntimeError("attachments require an OMP session")
                        references = self._attachment_references(_session_of(path), attachments)
                        prompt = "\n".join(([text] if text else []) + references)
                    offset = path.stat().st_size if path else 0
                    # Native Kitty Enter can leave OMP's draft unsubmitted. One
                    # bracketed paste preserves newlines; literal CR submits it.
                    value = "\x1b[200~" + prompt + "\x1b[201~\r" if path else prompt + "\r"
                    run_tern(["send", block, "text", value])
                    # ponytail: one global lock covers acceptance; use per-pane
                    # locks if simultaneous phones cause cross-pane contention.
                    if path:
                        deadline = time.monotonic() + 4
                        while not accepted_prompt(path, offset, prompt):
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

    def _attachment_ids(self, body: dict) -> list[str]:
        """Validated, deduplicated upload ids from a send body."""
        value = body.get("attachments")
        if value is None:
            return []
        if not isinstance(value, list) or len(value) > phone_uploads.MAX_ATTACHMENTS:
            raise ValueError(f"attachments must be at most {phone_uploads.MAX_ATTACHMENTS} upload ids")
        ids: list[str] = []
        for item in value:
            if not isinstance(item, str) or not phone_uploads.ID_PATTERN.fullmatch(item):
                raise ValueError("attachments must be 32-character upload ids")
            if item not in ids:
                ids.append(item)
        return ids

    def _attachment_references(self, session: str, ids: list[str]) -> list[str]:
        """Native OMP mentions for uploads owned by this exact session."""
        references: list[str] = []
        for upload_id in ids:
            record = phone_uploads.metadata(session, upload_id)
            original = phone_uploads.original(session, upload_id)
            if record is None or original is None:
                raise RuntimeError("an attachment is not available for this session")
            references.append(phone_uploads.reference(original))
        return references

    def _text(self, text, limit: int, allow_empty: bool = False) -> str:
        if not isinstance(text, str) or (not text and not allow_empty) or "\0" in text or len(text) > limit:
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
