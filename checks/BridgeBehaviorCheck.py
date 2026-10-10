#!/usr/bin/env python3
"""Run with python3 checks/BridgeBehaviorCheck.py (no installed Tern required).

HTTP checks model a live terminal's finite visible native surface, its scrollback
history and its live session/agent state — never a CLI argv echo. Runner checks
use real disposable Python child processes. The release integration harness
separately exercises the installed Tern CLI.
"""
import contextlib
from html import escape
import http.client
import io
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.parse
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tern_bridge as bridge
import conversation
import phone_uploads

# Mirrors OMP's documented @file extractor: a quoted or bare mention that starts
# a word. Used only to prove a generated prompt is a native mention the agent
# would really read, never to restate bridge internals.
NATIVE_MENTION_REGEX = re.compile("@(?:\"([^\"]+)\"|'([^']+)'|([^\\s@]+))")
MENTION_BOUNDARY_REGEX = re.compile(r"[\s([{<\"'`]")


def native_mentions(text: str) -> list[str]:
    """The file paths OMP's own extractor would resolve from this prompt text."""
    found: list[str] = []
    for match in NATIVE_MENTION_REGEX.finditer(text):
        if match.start() != 0 and not MENTION_BOUNDARY_REGEX.match(text[match.start() - 1]):
            continue
        raw = match.group(1) or match.group(2) or match.group(3)
        if raw:
            found.append(raw)
    return found


class Terminal:
    """Functional boundary double: a finite visible native surface plus scrollback.

    Input changes screen and session state; capture reads the modeled surface.
    Nothing here reports an argv echo as an outcome: callers observe what a phone
    would see — painted rows, styled HTML and created agent sessions.
    """

    TEXT_COLOR = "#f0e8f4"      # desktop surface palette (body text and background)
    BACKGROUND = "#140f18"
    PROMPT_COLOR = "#8eb897"
    VISIBLE_ROWS = 2            # only the newest rows fit the physical screen

    def __init__(self):
        self.old_shell = "PREVIOUS_SHELL_SYNTHETIC\n"   # raw PTY grid; OMP paints over it
        self.history = [(self.TEXT_COLOR, "earlier synthetic output line\n")]
        self.visible = [(self.PROMPT_COLOR, "ACTIVE_NATIVE_SYNTHETIC\n"),
                        (self.TEXT_COLOR, "Unicode: café 世界\n")]
        self.sessions = {}
        self.pending = ""
        self.submitted = []
        self.pasted = []
        self.model_cycles = 0
        self.operations = 0
        self.failure = None

    def _rows(self, scrollback):
        return self.history + self.visible if scrollback else list(self.visible)

    def screen(self, scrollback=False):
        """Plain text as `capture ... --surfaces` (plus `--scrollback`) returns it."""
        return "".join(text for _, text in self._rows(scrollback))

    def styled(self, scrollback=False):
        """The same surface as `capture ... --html` paints it: escaped text, palette spans."""
        spans = "".join('<span style="color:%s">%s</span>' % (color, escape(text))
                        for color, text in self._rows(scrollback))
        return '<pre style="color:%s;background-color:%s">%s</pre>' % (
            self.TEXT_COLOR, self.BACKGROUND, spans)

    def _paint(self, line):
        """Print one line; the oldest visible row scrolls off into scrollback history."""
        self.visible.append((self.TEXT_COLOR, line + "\n"))
        while len(self.visible) > self.VISIBLE_ROWS:
            self.history.append(self.visible.pop(0))

    def new_session(self, args):
        """Interpret `new session NAME [--cwd DIR] -- CMD` into live session state."""
        state = {"cwd": None, "command": "login shell"}
        if "--cwd" in args:
            state["cwd"] = args[args.index("--cwd") + 1]
        if "--" in args and args.index("--") + 1 < len(args):
            state["command"] = args[args.index("--") + 1]
        self.sessions[args[2]] = state

    def run(self, args, stdin_text=None):
        self.operations += 1
        if self.failure is not None:
            raise self.failure
        command = args[0]
        if command == "process":
            return 0, json.dumps({"foreground": {"name": "synthetic-shell", "pid": 1}})
        if command == "ls":
            sessions = [{"name": name, **state} for name, state in sorted(self.sessions.items())]
            return 0, json.dumps({"sessions": sessions})
        if command == "new":
            self.new_session(args)
            return 0, ""
        if command == "kill":
            self.sessions.pop(args[2], None)
            return 0, ""
        if args[1] not in ("42", "qa/42", "Flow - demo/42"):
            raise RuntimeError("unknown synthetic block")
        if command == "capture":
            if "--surfaces" not in args:
                return 0, self.old_shell
            scrollback = "--scrollback" in args
            return 0, self.styled(scrollback) if "--html" in args else self.screen(scrollback)
        if command != "send":
            raise RuntimeError("unsupported synthetic operation")
        kind, value = args[2:4]
        if kind == "text":
            for char in value:
                if char == "\r":
                    self.submitted.append(self.pending)
                    self._paint(self.pending)
                    self.pending = ""
                else:
                    self.pending += char
        elif kind == "paste":
            self.pasted.append(value)
            self.pending += value
        elif kind == "keys":
            if value == "ctrl+p":
                self.model_cycles += 1
            elif value == "Enter":
                self.submitted.append(self.pending)
                self._paint(self.pending)
                self.pending = ""
            else:
                raise RuntimeError("unsupported synthetic key")
        return 0, ""


class BridgeHttpFixture(unittest.TestCase):
    def build_terminal(self):
        return Terminal()

    def setUp(self):
        self.terminal = self.build_terminal()
        self.stub = patch.object(bridge, "run_tern", self.terminal.run)
        self.stub.start()
        self.addCleanup(self.stub.stop)
        # A boundary double for the pane's file table: this synthetic terminal
        # holds no transcript, so it stays an ordinary terminal.
        open_paths = patch.object(bridge, "_open_paths", return_value=set())
        open_paths.start()
        self.addCleanup(open_paths.stop)
        executable = patch.object(bridge, "_exe_path", return_value="")
        executable.start()
        self.addCleanup(executable.stop)
        self.token = secrets.token_hex(32)
        handler = type("SyntheticHandler", (bridge.Handler,), {"token": self.token})
        self.server = bridge.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, path, body=None, method="POST", auth=True, headers=None, raw=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        try:
            request_headers = {"Content-Type": "application/json"}
            if auth:
                request_headers["Authorization"] = "Bearer " + self.token
            if headers:
                request_headers.update(headers)
            payload = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
            connection.request(method, path, payload, request_headers)
            response = connection.getresponse()
            return response.status, response.getheader("Content-Type"), response.read()
        finally:
            connection.close()

    def send(self, kind, value, block="42"):
        return self.request("/send", {"block": block, "kind": kind, "value": value})[0]

class BridgeHttpChecks(BridgeHttpFixture):
    def test_default_text_capture_is_readable_and_includes_scrollback(self):
        status, content_type, body = self.request("/capture", {"block": "qa/42"})
        self.assertEqual(status, 200)
        self.assertEqual(content_type, "text/plain; charset=utf-8")
        text = body.decode("utf-8")
        self.assertIn("ACTIVE_NATIVE_SYNTHETIC", text)
        self.assertIn("Unicode: café 世界", text)
        self.assertIn("earlier synthetic output line", text)
        self.assertLess(text.index("earlier synthetic"), text.index("ACTIVE_NATIVE_SYNTHETIC"))
        self.assertNotIn("<pre", text)
        self.assertNotIn(self.terminal.old_shell.encode("utf-8"), body)
        self.assertEqual(self.terminal.operations, 1)

    def test_capture_reads_the_current_surface_frame_as_utf8(self):
        self.terminal.visible = [(self.terminal.TEXT_COLOR, "NEXT_NATIVE_FRAME\n"),
                                 (self.terminal.TEXT_COLOR, "Unicode: 世界 café\n")]
        status, content_type, body = self.request("/capture", {"block": "42"})
        self.assertEqual(status, 200)
        self.assertEqual(content_type, "text/plain; charset=utf-8")
        self.assertIn("NEXT_NATIVE_FRAME\nUnicode: 世界 café\n", body.decode("utf-8"))
        self.assertNotIn(b"ACTIVE_NATIVE_SYNTHETIC", body)

    def test_html_capture_preserves_escaped_user_text_and_desktop_palette(self):
        typed = "<b>&</b> synthetic"
        self.assertEqual(self.send("submit", typed), 200)
        status, content_type, body = self.request("/capture", {"block": "42", "format": "html"})
        self.assertEqual(status, 200)
        self.assertEqual(content_type, "text/html; charset=utf-8")
        page = body.decode("utf-8")
        self.assertTrue(page.startswith('<pre style="color:#f0e8f4;background-color:#140f18">'))
        self.assertIn('<span style="color:#8eb897">ACTIVE_NATIVE_SYNTHETIC\n</span>', page)
        self.assertNotIn(typed, page)
        self.assertIn("&lt;b&gt;&amp;&lt;/b&gt; synthetic", page)
        self.assertIn("earlier synthetic output line", page)   # html keeps scrollback too
        self.assertNotIn(self.terminal.old_shell, page)

    def test_finite_visible_screen_versus_unbounded_scrollback(self):
        for line in ["first synthetic command", "second synthetic command", "third synthetic command"]:
            self.assertEqual(self.send("submit", line), 200)
        visible_only = self.terminal.run(["capture", "42", "--surfaces"])[1]
        self.assertNotIn("first synthetic command", visible_only)
        self.assertIn("third synthetic command", visible_only)
        scrolled = self.terminal.run(["capture", "42", "--scrollback", "--surfaces"])[1]
        self.assertIn("first synthetic command", scrolled)
        status, _, body = self.request("/capture", {"block": "42"})
        self.assertEqual(status, 200)
        self.assertIn(b"first synthetic command", body)

    def test_formats_other_than_text_or_html_are_rejected_before_the_cli(self):
        for fmt in ["xml", "HTML", "ansi", "", 42, None, True, {}, []]:
            with self.subTest(fmt=fmt):
                self.assertEqual(self.request("/capture", {"block": "42", "format": fmt})[0], 400)
        self.assertEqual(self.terminal.operations, 0)

    def test_submit_enters_text_and_clears_the_terminal_draft(self):
        self.assertEqual(self.send("submit", "synthetic café"), 200)
        self.assertEqual(self.terminal.submitted, ["synthetic café"])
        self.assertEqual(self.terminal.pending, "")

    def test_spaced_flow_session_can_submit_and_capture(self):
        self.assertEqual(self.send("submit", "Flow command", block="Flow - demo/42"), 200)
        self.assertEqual(self.terminal.submitted, ["Flow command"])
        status, _, screen = self.request("/capture", {"block": "Flow - demo/42"})
        self.assertEqual(status, 200)
        self.assertIn("Flow command", screen.decode("utf-8"))

    def test_text_paste_and_enter_keep_distinct_semantics(self):
        self.assertEqual(self.send("text", "raw"), 200)
        self.assertEqual(self.terminal.pending, "raw")
        self.assertEqual(self.terminal.submitted, [])
        self.assertEqual(self.send("paste", "\nmultiline\r"), 200)
        self.assertEqual(self.terminal.pasted, ["\nmultiline\r"])
        self.assertEqual(self.terminal.submitted, [])
        self.assertEqual(self.send("keys", "Enter"), 200)
        self.assertEqual(self.terminal.submitted, ["raw\nmultiline\r"])

    def test_ctrl_p_reaches_terminal(self):
        self.assertEqual(self.send("keys", "ctrl+p"), 200)
        self.assertEqual(self.terminal.model_cycles, 1)

    def test_all_routes_require_authorization_before_work(self):
        for method, path in [("GET", "/health"), ("GET", "/sessions"), ("GET", "/attachment"),
                             ("POST", "/send"), ("POST", "/capture"), ("POST", "/upload"),
                             ("POST", "/new"), ("POST", "/kill")]:
            with self.subTest(method=method, path=path):
                self.assertEqual(self.request(path, {}, method=method, auth=False)[0], 401)
        for auth in ["Bearer wrong", "Basic wrong", "Bearer café"]:
            self.assertEqual(self.request("/health", method="GET", headers={"Authorization": auth})[0], 401)
        self.assertEqual(self.terminal.operations, 0)

    def test_health_and_unknown_routes(self):
        self.assertEqual(json.loads(self.request("/health", method="GET")[2]), {"ok": True})
        self.assertEqual(self.request("/missing", method="GET")[0], 404)
        self.assertEqual(self.request("/missing", {})[0], 404)

    def test_malformed_bodies_and_framing_are_rejected(self):
        for raw in [b"{", b"[]", b"null", b"1", b'"text"', b"\xff"]:
            with self.subTest(raw=raw):
                self.assertEqual(self.request("/send", raw=raw)[0], 400)
        for length in ["0", "-1", "bad", "9" * 20, str(bridge.MAX_BODY + 1)]:
            with self.subTest(length=length):
                self.assertEqual(self.request("/send", raw=b"{}", headers={"Content-Length": length})[0], 400)
        self.assertEqual(self.request("/send", {}, headers={"Transfer-Encoding": "chunked"})[0], 400)
        self.assertEqual(self.terminal.operations, 0)

    def test_duplicate_and_short_bodies_are_rejected(self):
        cases = [("Content-Length: 2\r\nContent-Length: 2\r\n", b"{}"),
                 ("Content-Length: 20\r\n", b"{}"), ("", b"")]
        for framing, body in cases:
            with self.subTest(framing=framing):
                with socket.create_connection(self.server.server_address, timeout=3) as client:
                    request = ("POST /send HTTP/1.0\r\nAuthorization: Bearer " + self.token
                               + "\r\n" + framing + "\r\n").encode() + body
                    client.sendall(request)
                    client.shutdown(socket.SHUT_WR)
                    response = http.client.HTTPResponse(client)
                    response.begin()
                    self.assertEqual(response.status, 400)
                    response.read()
        self.assertEqual(self.terminal.operations, 0)

    def test_exact_body_limit_is_accepted_but_next_byte_is_rejected(self):
        raw = b'{"block":"42"}'
        raw += b" " * (bridge.MAX_BODY - len(raw))
        self.assertEqual(self.request("/capture", raw=raw)[0], 200)
        self.assertEqual(self.request("/capture", raw=raw + b" ")[0], 400)
        self.assertEqual(self.terminal.operations, 1)

    def test_stalled_body_has_a_bounded_error_response(self):
        with patch.object(bridge, "CMD_TIMEOUT", 0.05):
            with socket.create_connection(self.server.server_address, timeout=3) as client:
                request = ("POST /send HTTP/1.0\r\nAuthorization: Bearer " + self.token
                           + "\r\nContent-Length: 20\r\n\r\n{}").encode()
                client.sendall(request)
                response = http.client.HTTPResponse(client)
                response.begin()
                self.assertEqual(response.status, 408)
                self.assertEqual(json.loads(response.read())["error"], "request timed out")
        self.assertEqual(self.terminal.operations, 0)

    def test_invalid_blocks_never_reach_cli(self):
        for block in [None, 42, {}, "", "-1", "--json/42", "qa/-42", "qa/42/extra",
                      "qa/", "qa /42", "qa/42\0", "a" * 65 + "/42", "１"]:
            with self.subTest(block=block):
                self.assertEqual(self.request("/capture", {"block": block})[0], 400)
        self.assertEqual(self.terminal.operations, 0)

    def test_text_validation_and_exact_boundary(self):
        for kind in ["text", "submit", "paste"]:
            limit = 65536 if kind == "paste" else 4096
            for value in [None, 42, {}, "", "has\0nul", "\ud800", "x" * (limit + 1)]:
                with self.subTest(kind=kind, value_type=type(value).__name__):
                    self.assertEqual(self.send(kind, value), 400)
        self.assertEqual(self.terminal.operations, 0)
        self.assertEqual(self.send("submit", "x" * 4096), 200)
        self.assertEqual(self.terminal.submitted, ["x" * 4096])
        # The JSON body cap is independent of the paste character cap.
        self.assertEqual(self.send("paste", "x" * 65536), 400)

    def test_key_and_kind_validation(self):
        for value in [None, 1, "", "--json", "ctrl p", "ctrl+p\n", "ctrl+p\0", "x" * 65,
                      "ctrl+é", "ctrl+p;touch", "$(command)", "a\\b"]:
            with self.subTest(value=value):
                self.assertEqual(self.send("keys", value), 400)
        self.assertEqual(self.send("unknown", "value"), 400)
        self.assertEqual(self.terminal.operations, 0)

    def test_session_input_validation(self):
        for value in [None, 1, {}, "", "--json", "has spaces", "bad\0name", "x" * 65]:
            self.assertEqual(self.request("/new", {"name": value})[0], 400)
            self.assertEqual(self.request("/kill", {"session": value})[0], 400)
        for cwd in ["relative", "/tmp/../other", "/tmp\0", 42, "/" + "a" * 4096, "\ud800"]:
            self.assertEqual(self.request("/new", {"name": "qa", "cwd": cwd})[0], 400)
        self.assertEqual(self.terminal.operations, 0)

    def test_new_session_starts_an_omp_agent_named_by_the_request(self):
        status, content_type, body = self.request("/new", {"name": "qa-42"})
        self.assertEqual(status, 200)
        self.assertEqual(content_type, "application/json")
        self.assertEqual(json.loads(body), {"ok": True})
        self.assertEqual(self.terminal.sessions, {"qa-42": {"cwd": None, "command": "omp"}})
        # The phone's own session list is what proves live agent state, not an argv echo.
        listed = json.loads(self.request("/sessions", method="GET")[2])["sessions"]
        self.assertEqual(listed, [{"name": "qa-42", "cwd": None, "command": "omp"}])

    def test_new_session_cwd_reaches_the_created_agent(self):
        self.assertEqual(self.request("/new", {"name": "qa-42", "cwd": "/tmp/qa"})[0], 200)
        self.assertEqual(self.terminal.sessions["qa-42"], {"cwd": "/tmp/qa", "command": "omp"})
        # An absent or empty cwd means "inherit the bridge's directory".
        self.assertEqual(self.request("/new", {"name": "qa-43"})[0], 200)
        self.assertEqual(self.request("/new", {"name": "qa-44", "cwd": ""})[0], 200)
        self.assertIsNone(self.terminal.sessions["qa-43"]["cwd"])
        self.assertIsNone(self.terminal.sessions["qa-44"]["cwd"])

    def test_new_session_accepts_maximum_length_name(self):
        longest = "a" * 64
        self.assertEqual(self.request("/new", {"name": longest})[0], 200)
        self.assertEqual(self.terminal.sessions[longest], {"cwd": None, "command": "omp"})

    def test_kill_session_removes_the_named_agent(self):
        self.assertEqual(self.request("/new", {"name": "qa-42"})[0], 200)
        self.assertEqual(self.request("/kill", {"session": "qa-42"})[0], 200)
        self.assertEqual(self.terminal.sessions, {})

    def test_new_session_rejections_never_reach_cli(self):
        for name in ["Flow - demo", "", "a" * 65, "-qa", "qa/42", "qa\0", None, 42]:
            with self.subTest(name=name):
                self.assertEqual(self.request("/new", {"name": name})[0], 400)
        for cwd in ["relative/path", "/a/../b", "/tmp\0qa", "/" + "a" * 4096, 42]:
            with self.subTest(cwd=cwd):
                self.assertEqual(self.request("/new", {"name": "qa-42", "cwd": cwd})[0], 400)
        self.assertEqual(self.terminal.sessions, {})
        self.assertEqual(self.terminal.operations, 0)

    def test_new_session_requires_authorization(self):
        self.assertEqual(self.request("/new", {"name": "qa-42"}, auth=False)[0], 401)
        self.assertEqual(self.terminal.sessions, {})
        self.assertEqual(self.terminal.operations, 0)

    def test_cli_errors_are_returned_for_post_and_get(self):
        for failure, expected in [(RuntimeError("synthetic CLI rejection"), 409),
                                  (subprocess.TimeoutExpired("tern", 15), 504),
                                  (FileNotFoundError("synthetic missing CLI"), 500)]:
            self.terminal.failure = failure
            for method, path, body in [("POST", "/capture", {"block": "42"}),
                                       ("POST", "/send", {"block": "42", "kind": "submit", "value": "text"}),
                                       ("GET", "/sessions", None)]:
                with self.subTest(failure=type(failure).__name__, path=path):
                    status, _, payload = self.request(path, body, method=method)
                    self.assertEqual(status, expected)
                    self.assertTrue(json.loads(payload)["error"])
            self.assertEqual(self.terminal.submitted, [])


class BridgeRunnerChecks(unittest.TestCase):
    def run_child(self, source):
        actual_run = subprocess.run

        def launch(args, **kwargs):
            # Real processes test decoding, exit status, output bounds and deadlines.
            # No shell command or production Tern state is used.
            return actual_run([sys.executable, "-c", source], **kwargs)

        with patch.object(bridge.subprocess, "run", side_effect=launch):
            return bridge.run_tern(["synthetic"])

    def test_utf8_output(self):
        self.assertEqual(self.run_child("import os; os.write(1, 'café 世界\\n'.encode('utf-8'))"),
                         (0, "café 世界\n"))

    def test_nonzero_exit_is_not_dropped(self):
        with self.assertRaisesRegex(RuntimeError, "synthetic rejection"):
            self.run_child("import sys; print('synthetic rejection',file=sys.stderr); sys.exit(3)")

    def test_output_cap_rejects_instead_of_truncating(self):
        with patch.object(bridge, "MAX_OUT", 8):
            with self.assertRaisesRegex(RuntimeError, "exceeds limit"):
                self.run_child("import os; os.write(1, '界界界'.encode('utf-8'))")

    def test_command_deadline(self):
        with patch.object(bridge, "CMD_TIMEOUT", 0.05):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.run_child("import time; time.sleep(10)")

    def test_existing_token_is_preserved_and_not_printed(self):
        with tempfile.TemporaryDirectory() as directory:
            token_file = Path(directory) / "bridge-token"
            original = secrets.token_hex(32) + "\n"
            token_file.write_text(original)
            output = io.StringIO()
            with patch.object(bridge, "TOKEN_FILE", token_file), contextlib.redirect_stdout(output):
                self.assertEqual(bridge.load_token(), original.strip())
            self.assertEqual(token_file.read_text(), original)
            self.assertEqual(output.getvalue(), "")

    def test_new_token_file_is_private(self):
        with tempfile.TemporaryDirectory() as directory:
            token_file = Path(directory) / "state" / "bridge-token"
            with patch.object(bridge, "TOKEN_FILE", token_file):
                token = bridge.load_token()
            self.assertGreaterEqual(len(token), 32)
            self.assertEqual(token_file.read_text().strip(), token)
            self.assertEqual(os.stat(token_file).st_mode & 0o777, 0o600)


class ConversationPagingChecks(unittest.TestCase):
    def test_pages_preserve_every_whole_message_in_order(self):
        messages = [{"id": str(i), "text": "café" * (i + 2)} for i in range(20)]
        payload = {"session": "test", "messages": messages}
        with patch.object(bridge, "PAGE_BYTES", 160):
            page = bridge.conversation_page(payload)
            parts = [page["messages"]]
            self.assertGreater(page["start"], 0)
            while page["start"]:
                boundary = page["start"]
                page = bridge.conversation_page(payload, boundary)
                self.assertEqual(page["end"], boundary)
                self.assertLess(page["start"], boundary)
                parts.insert(0, page["messages"])
        self.assertEqual([message for part in parts for message in part], messages)

    def test_empty_and_oversized_single_message_are_not_dropped(self):
        self.assertEqual(bridge.conversation_page({"messages": []})["messages"], [])
        message = {"text": "x" * (bridge.PAGE_BYTES + 1)}
        self.assertEqual(bridge.conversation_page({"messages": [message]})["messages"], [message])

    def test_invalid_boundaries_are_rejected(self):
        for value in (-1, True, 1.5, "1"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                bridge.conversation_page({"messages": []}, value)


class PromptAcceptanceChecks(unittest.TestCase):
    def test_old_echo_partial_write_and_assistant_text_do_not_acknowledge_send(self):
        def record(role, text):
            return json.dumps({"type": "message", "message": {
                "role": role, "content": [{"type": "text", "text": text}]}}).encode() + b"\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "session.jsonl"
            prompt = "new café\nprompt"
            prior = record("user", prompt)
            path.write_bytes(prior)
            offset = len(prior)
            self.assertFalse(bridge.accepted_prompt(path, offset, prompt))
            path.write_bytes(prior + record("assistant", prompt))
            self.assertFalse(bridge.accepted_prompt(path, offset, prompt))
            accepted = record("user", prompt)
            path.write_bytes(prior + accepted[:-1])
            self.assertFalse(bridge.accepted_prompt(path, offset, prompt))
            path.write_bytes(prior + accepted)
            self.assertTrue(bridge.accepted_prompt(path, offset, prompt))

    def test_native_trim_is_acknowledged_without_ignoring_internal_whitespace(self):
        expected = "first line\n  indented café\nlast line"
        for content in (expected, [{"type": "text", "text": expected}]):
            with self.subTest(content=content), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "session.jsonl"
                row = {"type": "message", "message": {"role": "user", "content": content}}
                path.write_text(json.dumps(row) + "\n", encoding="utf-8")
                self.assertTrue(bridge.accepted_prompt(path, 0, "\ufeff \n" + expected + "\n\u3000"))
                self.assertFalse(bridge.accepted_prompt(path, 0, expected.replace("  indented", "indented")))
                self.assertFalse(bridge.accepted_prompt(path, path.stat().st_size, " \n" + expected + "\n "))

    def test_identical_concurrent_send_needs_its_own_persisted_message(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "session.jsonl"
            path.write_bytes(b"")
            first_started, second_write = threading.Event(), threading.Event()
            writes, writers, outcomes = [], [], {}
            record = json.dumps({"type": "message", "message": {
                "role": "user", "content": [{"type": "text", "text": "same prompt"}]}}).encode() + b"\n"

            def persist_first():
                second_write.wait(0.4)
                path.write_bytes(record)

            def write_cli(*args, **kwargs):
                writes.append(True)
                if len(writes) == 1:
                    writer = threading.Thread(target=persist_first)
                    writers.append(writer)
                    writer.start()
                    first_started.set()
                else:
                    second_write.set()  # Second PTY write is never accepted by OMP.
                return 0, ""

            def send(name):
                try:
                    handler = bridge.Handler.__new__(bridge.Handler)
                    outcomes[name] = handler._send_keys({
                        "block": "42", "kind": "submit", "value": "same prompt"})
                except RuntimeError as error:
                    outcomes[name] = error

            with patch.object(bridge, "omp_session", return_value=path), \
                    patch.object(bridge, "run_tern", side_effect=write_cli):
                first = threading.Thread(target=send, args=("first",))
                first.start()
                self.assertTrue(first_started.wait(2))
                second = threading.Thread(target=send, args=("second",))
                second.start()
                first.join(8)
                second.join(8)
                for writer in writers:
                    writer.join(2)
            self.assertFalse(first.is_alive() or second.is_alive())
            self.assertEqual(outcomes["first"], {"ok": True, "accepted": True})
            self.assertIsInstance(outcomes["second"], RuntimeError)


class ConversationCaptureChecks(unittest.TestCase):
    """`conversation.read_conversation` maps the active OMP session branch to wire messages."""

    SESSION_ID = "11111111-2222-3333-4444-555555555555"
    FENCED = "Here is code:\n```py\nprint(1) <system-reminder>x</system-reminder>\n```"

    @staticmethod
    def lines(records):
        return "".join(json.dumps(record) + "\n" for record in records)

    def fixture_path(self, body):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "2026-01-01_11111111.jsonl"
        path.write_text(body, encoding="utf-8")
        return path

    def dag_fixture(self):
        records = [
            {"type": "title", "v": 1, "title": "Fixture", "updatedAt": "2026-01-01T00:00:00Z", "pad": ""},
            {"type": "session", "version": 3, "id": self.SESSION_ID,
             "timestamp": "2026-01-01T00:00:00Z", "cwd": "/tmp"},
            {"type": "message", "id": "e1", "parentId": None, "timestamp": "t1",
             "message": {"role": "user", "content": "hello <files>\n</files>"}},
            {"type": "message", "id": "e2", "parentId": "e1", "timestamp": "t2",
             "message": {"role": "assistant", "content": [
                 {"type": "thinking", "thinking": "SECRET_THINKING"},
                 {"type": "text", "text": self.FENCED},
                 {"type": "toolCall", "id": "tc1", "name": "read", "arguments": {"path": "a.py"}}]}},
            {"type": "message", "id": "e3", "parentId": "e2", "timestamp": "t3",
             "message": {"role": "toolResult", "toolCallId": "tc1", "toolName": "read",
                         "content": [{"type": "text", "text": "file body"}], "isError": False}},
            {"type": "message", "id": "e4", "parentId": "e3", "timestamp": "t4",
             "message": {"role": "assistant", "content": [{"type": "text", "text": "ABANDONED_SIBLING"}]}},
            {"type": "message", "id": "e5", "parentId": "e3", "timestamp": "t5",
             "message": {"role": "user", "content": "after rewind"}},
            {"type": "message", "id": "e6", "parentId": "e5", "timestamp": "t6",
             "message": {"role": "assistant", "content": [{"type": "text", "text": "final answer"}]}},
            {"type": "custom_message", "id": "e7", "parentId": "e6", "timestamp": "t7",
             "customType": "note", "content": "visible note", "display": True, "attribution": "user"},
            {"type": "custom_message", "id": "e8", "parentId": "e7", "timestamp": "t8",
             "customType": "hidden", "content": "HIDDEN_CONTEXT", "display": False},
            {"type": "message", "id": "e9", "parentId": "e8", "timestamp": "t9",
             "message": {"role": "developer", "content": "dev directive"}},
        ]
        body = self.lines(records)
        # Final record is an interrupted append: no closing brace, no newline.
        return self.fixture_path(
            body + '{"type":"message","id":"e10","parentId":"e9","timestamp":"t10",'
                   '"message":{"role":"user","content":"PARTIAL')

    def expected_messages(self):
        return [
            {"id": "e1", "role": "user", "text": "hello <files>\n</files>", "format": "markdown"},
            {"id": "e2", "role": "assistant", "text": self.FENCED, "format": "markdown"},
            {"id": "e2", "role": "tool", "name": "read", "text": '{\n  "path": "a.py"\n}', "format": "text"},
            {"id": "e3", "role": "tool", "name": "read", "text": "file body", "format": "text"},
            {"id": "e5", "role": "user", "text": "after rewind", "format": "markdown"},
            {"id": "e6", "role": "assistant", "text": "final answer", "format": "markdown"},
            {"id": "e7", "role": "context", "name": "note", "text": "visible note", "format": "markdown"},
        ]

    def test_steering_user_message_stays_visible(self):
        records = [
            {"type": "session", "version": 3, "id": "STEER", "timestamp": "t", "cwd": "/tmp"},
            {"type": "message", "id": "s1", "parentId": None, "timestamp": "t1",
             "message": {"role": "user", "content": "steered turn", "steering": True}},
            {"type": "message", "id": "s2", "parentId": "s1", "timestamp": "t2",
             "message": {"role": "user", "content": "auto continue", "synthetic": True}},
        ]
        payload = conversation.read_conversation(self.fixture_path(self.lines(records)))
        self.assertEqual(payload["messages"],
                         [{"id": "s1", "role": "user", "text": "steered turn", "format": "markdown"}])

    def test_malformed_terminated_record_raises(self):
        body = self.lines([
            {"type": "session", "version": 3, "id": "BAD", "timestamp": "t", "cwd": "/tmp"},
            {"type": "message", "id": "b1", "parentId": None, "timestamp": "t1",
             "message": {"role": "user", "content": "kept"}},
        ])
        with self.assertRaises(ValueError):
            conversation.read_conversation(self.fixture_path(body + "not-json\n"))

    def test_missing_session_header_raises(self):
        body = self.lines([
            {"type": "message", "id": "h1", "parentId": None, "timestamp": "t1",
             "message": {"role": "user", "content": "orphan"}},
        ])
        with self.assertRaises(ValueError):
            conversation.read_conversation(self.fixture_path(body))

    def test_incomplete_final_record_is_ignored(self):
        body = self.lines([
            {"type": "session", "version": 3, "id": "TAIL", "timestamp": "t", "cwd": "/tmp"},
            {"type": "message", "id": "t1", "parentId": None, "timestamp": "t1",
             "message": {"role": "user", "content": "complete"}},
        ])
        truncated = conversation.read_conversation(self.fixture_path(body + '{"type":"message","id":"t2"'))
        self.assertEqual([message["id"] for message in truncated["messages"]], ["t1"])
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        raw = Path(directory.name) / "invalid-utf8.jsonl"
        raw.write_bytes(body.encode("utf-8") + b'{"type":"message","id":"t2","content":"\xff\xfe')
        self.assertEqual([message["id"] for message in conversation.read_conversation(raw)["messages"]], ["t1"])

    def test_active_branch_excludes_abandoned_sibling(self):
        payload = conversation.read_conversation(self.dag_fixture())
        self.assertEqual(payload["session"], self.SESSION_ID)
        self.assertEqual(payload["messages"], self.expected_messages())
        self.assertNotIn("ABANDONED_SIBLING", self.joined_text(payload))

    def test_metadata_excluded(self):
        text = self.joined_text(conversation.read_conversation(self.dag_fixture()))
        for hidden in ("SECRET_THINKING", "HIDDEN_CONTEXT", "dev directive", "PARTIAL"):
            self.assertNotIn(hidden, text)

    def test_code_and_tool_content_preserved(self):
        payload = conversation.read_conversation(self.dag_fixture())
        by_id = {}
        for message in payload["messages"]:
            by_id.setdefault(message["id"], []).append(message)
        self.assertIn("<files>", by_id["e1"][0]["text"])
        self.assertEqual(by_id["e2"][0]["text"], self.FENCED)
        self.assertIn("<system-reminder>", by_id["e2"][0]["text"])
        self.assertEqual([message["name"] for message in by_id["e2"] + by_id["e3"]
                          if message["role"] == "tool"], ["read", "read"])

    def test_reset_boundary_collapses_to_clean_screen(self):
        records = [
            {"type": "session", "version": 3, "id": "RFIX", "timestamp": "t", "cwd": "/tmp"},
            {"type": "message", "id": "r1", "parentId": None, "timestamp": "t1",
             "message": {"role": "user", "content": "OLD_BEFORE_CLEAR"}},
            {"type": "reset_boundary", "id": "r2", "parentId": "r1", "timestamp": "t2"},
            {"type": "message", "id": "r3", "parentId": "r2", "timestamp": "t3",
             "message": {"role": "user", "content": "new after clear"}},
        ]
        body = self.lines(records)
        payload = conversation.read_conversation(self.fixture_path(body))
        self.assertEqual(payload["session"], "RFIX")
        self.assertEqual(payload["messages"],
                         [{"id": "r3", "role": "user", "text": "new after clear", "format": "markdown"}])
        self.assertNotIn("OLD_BEFORE_CLEAR", self.joined_text(payload))

    def test_missing_file_raises(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        with self.assertRaises(FileNotFoundError):
            conversation.read_conversation(Path(directory.name) / "absent.jsonl")

    @staticmethod
    def joined_text(payload):
        return "".join(message["text"] for message in payload["messages"])


class OmpSessionChecks(unittest.TestCase):
    """A pane classifies as OMP only from its own process and its own transcript."""

    SESSION = "33333333-3333-3333-3333-333333333333"
    OTHER = "44444444-4444-4444-4444-444444444444"

    def setUp(self):
        self.state = tempfile.TemporaryDirectory()
        self.addCleanup(self.state.cleanup)

    def transcript(self, session=None, name_session=None, header=True, body=""):
        """One candidate transcript file in a temp session store."""
        path = Path(self.state.name) / f"2026-01-01T00-00-00-000Z_{name_session or session or self.SESSION}.jsonl"
        lines = []
        if header:
            lines.append(json.dumps({"type": "title", "v": 1, "title": "Fixture"}))
            lines.append(json.dumps({"type": "session", "version": 3, "id": session or self.SESSION,
                                     "timestamp": "2026-01-01T00:00:00Z", "cwd": "/tmp"}))
        path.write_text("".join(line + "\n" for line in lines) + body, encoding="utf-8")
        return path

    def resolve(self, open_paths, *, name="18.8.7", argv=("omp",), pid=4242, exe=""):
        """Resolve block 42 against a synthetic foreground process and file table."""
        foreground = {"pid": pid, "name": name, "argv": list(argv), "cwd": "/tmp"}
        if open_paths is None:
            # A non-OMP program's file table must never even be inspected.
            paths = patch.object(bridge, "_open_paths",
                                 side_effect=AssertionError("a non-OMP process has no OMP transcript"))
        else:
            paths = patch.object(bridge, "_open_paths", return_value=set(open_paths))
        with patch.object(bridge, "run_tern",
                          return_value=(0, json.dumps({"foreground": foreground}))), \
                patch.object(bridge, "_exe_path", return_value=exe), paths:
            return bridge.omp_session("42")

    def test_version_renamed_omp_resolves_its_own_transcript(self):
        path = self.transcript()
        self.assertEqual(self.resolve({path}), path)

    def test_classic_omp_identity_still_resolves(self):
        path = self.transcript()
        self.assertEqual(self.resolve({path}, name="omp", argv=("/usr/local/bin/omp",)), path)

    def test_processes_holding_an_omp_transcript_are_not_omp(self):
        path = self.transcript()
        for name, argv in [("zsh", ("/bin/zsh", "-l")), ("vim", ("vim",)), ("18.8.7", ("cat",))]:
            with self.subTest(name=name, argv=argv):
                self.assertIsNone(self.resolve(None, name=name, argv=argv))
        self.assertTrue(path.exists())

    def test_a_tern_managed_version_binary_is_omp_by_its_own_executable(self):
        path = self.transcript()
        managed = self.resolve({path}, name="18.8.7", argv=(),
                               exe="/Applications/Support/Tern/omp/versions/18.8.7")
        self.assertEqual(managed, path)
        link = self.resolve({path}, name="omp", argv=(),
                            exe="/Applications/Support/Tern/omp/bin/omp")
        self.assertEqual(link, path)
        other = self.resolve(None, name="18.8.7", argv=(),
                             exe="/Applications/Support/Other/versions/18.8.7")
        self.assertIsNone(other)

    def test_files_that_are_not_this_pane_own_transcript_are_ignored(self):
        headerless = self.transcript(header=False, body=json.dumps({"type": "message"}) + "\n")
        mismatched = self.transcript(name_session=self.OTHER)
        log = Path(self.state.name) / "omp.2026-10-10.99574.log"
        log.write_text("synthetic log\n", encoding="utf-8")
        self.assertIsNone(self.resolve({headerless, mismatched, log}))

    def test_no_open_transcript_and_no_process_id_stay_terminal(self):
        self.assertIsNone(self.resolve(set()))
        with self.assertRaises(RuntimeError):
            self.resolve(set(), pid=0)

    def test_two_confirmed_transcripts_are_refused_as_ambiguous(self):
        first = self.transcript(self.SESSION)
        second = self.transcript(self.OTHER)
        with self.assertRaises(RuntimeError):
            self.resolve({first, second})


class UploadStoreChecks(unittest.TestCase):
    """The private store's own layout, limits and conversation enrichment."""

    SESSION = "55555555-5555-5555-5555-555555555555"
    OTHER = "66666666-6666-6666-6666-666666666666"

    def setUp(self):
        self.state = tempfile.TemporaryDirectory()
        self.addCleanup(self.state.cleanup)
        self.store = Path(self.state.name) / "uploads"
        root = patch.object(phone_uploads, "UPLOAD_ROOT", self.store)
        root.start()
        self.addCleanup(root.stop)

    def store_one(self, payload=b"bytes", name="a.txt", mime="text/plain", session=None):
        return phone_uploads.store(io.BytesIO(payload), len(payload), session=session or self.SESSION,
                                   name=name, mime=mime)

    def test_originals_and_metadata_are_private_and_generated(self):
        record = self.store_one(b"payload", name="My Report (final).txt", mime="text/plain")
        self.assertEqual(sorted(record), ["id", "mime", "name", "size"])
        self.assertRegex(record["id"], r"[0-9a-f]{32}\Z")
        self.assertEqual(record["name"], "My Report (final).txt")
        self.assertEqual(record["size"], 7)
        directory = phone_uploads.session_dir(self.SESSION)
        self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
        original = phone_uploads.original(self.SESSION, record["id"])
        self.assertEqual(original.read_bytes(), b"payload")
        self.assertEqual(original.stat().st_mode & 0o777, 0o600)
        self.assertNotIn(record["name"], original.name)

    def test_bad_inputs_are_rejected_without_storing_anything(self):
        cases = [
            dict(session="bad/session", name="a.bin", mime="text/plain"),
            dict(session=self.SESSION, name="../a.bin", mime="text/plain"),
            dict(session=self.SESSION, name="", mime="text/plain"),
            dict(session=self.SESSION, name="a.bin", mime="not-a-mime"),
        ]
        for case in cases:
            with self.subTest(case=case), self.assertRaises(ValueError):
                phone_uploads.store(io.BytesIO(b"data"), 4, **case)
        for length in (0, phone_uploads.MAX_UPLOAD_BYTES + 1):
            with self.subTest(length=length), self.assertRaises(ValueError):
                phone_uploads.store(io.BytesIO(b"data"), length, session=self.SESSION,
                                    name="a.bin", mime="")

    def test_incomplete_body_never_becomes_a_usable_id(self):
        with self.assertRaises(ValueError):
            phone_uploads.store(io.BytesIO(b"short"), 10, session=self.SESSION,
                                name="a.bin", mime="application/octet-stream")
        self.assertEqual(list(phone_uploads.session_dir(self.SESSION).glob("*")), [])

    def test_lookups_are_bound_to_one_session(self):
        record = self.store_one()
        self.assertEqual(phone_uploads.metadata(self.SESSION, record["id"]), record)
        self.assertIsNone(phone_uploads.metadata(self.OTHER, record["id"]))
        self.assertIsNone(phone_uploads.original(self.OTHER, record["id"]))
        self.assertIsNone(phone_uploads.metadata(self.SESSION, "not-an-id"))
        self.assertIsNone(phone_uploads.metadata(self.SESSION, "0" * 32))

    def test_enrichment_removes_only_the_generated_mention_lines(self):
        record = self.store_one(b"image", name="a.png", mime="image/png")
        mention = phone_uploads.reference(phone_uploads.original(self.SESSION, record["id"]))
        payload = {"session": self.SESSION, "messages": [
            {"id": "u1", "role": "user", "text": "\n".join(["keep `code` & <markup>\n", mention])},
            {"id": "u2", "role": "user", "text": "  " + mention},
            {"id": "a1", "role": "assistant", "text": "reply " + mention},
        ]}
        enriched = phone_uploads.enrich_conversation(payload)
        self.assertEqual(enriched["messages"][0]["text"], "keep `code` & <markup>\n")
        self.assertEqual(enriched["messages"][0]["attachments"], [record])
        self.assertNotIn("attachments", enriched["messages"][1])
        self.assertEqual(enriched["messages"][1]["text"], "  " + mention)
        self.assertEqual(enriched["messages"][2]["text"], "reply " + mention)
        self.assertEqual(native_mentions("draft\n" + mention), [str(phone_uploads.original(
            self.SESSION, record["id"]))])

    def test_a_session_without_uploads_is_left_untouched(self):
        payload = {"session": self.OTHER, "messages": [{"id": "u1", "role": "user", "text": "plain"}]}
        self.assertIs(phone_uploads.enrich_conversation(payload), payload)
        self.assertNotIn("attachments", payload["messages"][0])


class OmpTerminal(Terminal):
    """A Terminal double whose pane runs a version-renamed OMP in its own session.

    A submit that takes the bracketed-paste path persists a real user record in
    the fixture transcript, so acceptance is proven by an appended record rather
    than by the CLI call itself.
    """

    def __init__(self, transcript):
        super().__init__()
        self.transcript = Path(transcript)
        self.process_name = "18.8.7"
        self.argv = ["omp"]
        self.fail_send = False
        self.persisted = []

    def run(self, args, stdin_text=None):
        if args[0] == "process":
            return 0, json.dumps({"pane": 42, "foreground": {
                "pid": 4242, "name": self.process_name, "argv": list(self.argv),
                "cwd": str(self.transcript.parent)}})
        if args[0] == "send" and self.fail_send:
            raise RuntimeError("synthetic CLI rejection")
        if args[0] == "send" and args[2] == "text" and args[3].startswith("\x1b[200~"):
            prompt = args[3][len("\x1b[200~"):-len("\x1b[201~\r")]
            self.persisted.append(prompt)
            self.submitted.append(prompt)
            self.persist(prompt)
            return 0, ""
        return super().run(args, stdin_text)

    def persist(self, text):
        """Append one persisted user message, as OMP itself would."""
        record = {"type": "message", "id": secrets.token_hex(4), "parentId": self.last_entry_id(),
                  "timestamp": "t", "message": {"role": "user", "content": text}}
        with self.transcript.open("ab") as out:
            out.write((json.dumps(record) + "\n").encode("utf-8"))

    def last_entry_id(self):
        for line in reversed(self.transcript.read_text(encoding="utf-8").splitlines()):
            try:
                record = json.loads(line)
            except ValueError:
                continue
            if isinstance(record, dict) and record.get("type") == "message":
                return record.get("id")
        return None


class BridgeAttachmentChecks(BridgeHttpFixture):
    """Uploads and native file mentions stay bound to the pane's own OMP session."""

    SESSION = "77777777-7777-7777-7777-777777777777"
    OTHER = "88888888-8888-8888-8888-888888888888"
    BLOCK = "42"

    def setUp(self):
        self.state = tempfile.TemporaryDirectory()
        self.addCleanup(self.state.cleanup)
        self.transcript = self.transcript_for(self.SESSION)
        # A space in the store path proves quoted mentions still resolve.
        self.store = Path(self.state.name) / "with space" / "uploads"
        root = patch.object(phone_uploads, "UPLOAD_ROOT", self.store)
        root.start()
        self.addCleanup(root.stop)
        super().setUp()
        paths = patch.object(bridge, "_open_paths", lambda pid: {self.transcript})
        paths.start()
        self.addCleanup(paths.stop)

    def build_terminal(self):
        return OmpTerminal(self.transcript)

    def transcript_for(self, session):
        path = Path(self.state.name) / f"2026-01-01T00-00-00-000Z_{session}.jsonl"
        if not path.exists():
            with path.open("w", encoding="utf-8") as out:
                for record in ({"type": "title", "v": 1, "title": "Fixture"},
                               {"type": "session", "version": 3, "id": session,
                                "timestamp": "2026-01-01T00:00:00Z", "cwd": "/tmp"}):
                    out.write(json.dumps(record) + "\n")
        return path

    def use_session(self, session):
        """Point the pane's own process at another session's transcript."""
        replacement = patch.object(bridge, "_open_paths", lambda pid: {self.transcript_for(session)})
        replacement.start()
        self.addCleanup(replacement.stop)

    def upload(self, payload, name="photo.png", mime="image/png", block=None, auth=True):
        query = urllib.parse.urlencode({"block": block or self.BLOCK, "name": name, "mime": mime})
        return self.request("/upload?" + query, raw=payload, auth=auth,
                            headers={"Content-Type": "application/octet-stream"})

    def upload_one(self, payload, **kwargs):
        status, _, body = self.upload(payload, **kwargs)
        self.assertEqual(status, 200)
        return json.loads(body)

    def stored(self, upload_id, session=None):
        directory = self.store / (session or self.SESSION)
        if not directory.is_dir():
            return []
        return [path for path in directory.glob(upload_id + "*")
                if not path.name.endswith(phone_uploads.SIDECAR_SUFFIX)]

    def fetch(self, upload_id, block=None, auth=True):
        """One authenticated attachment read, headers included."""
        query = urllib.parse.urlencode({"block": block or self.BLOCK, "id": upload_id})
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        try:
            headers = {"Authorization": "Bearer " + self.token} if auth else {}
            connection.request("GET", "/attachment?" + query, None, headers)
            response = connection.getresponse()
            return response.status, response.headers, response.read()
        finally:
            connection.close()

    def raw_exchange(self, request_line, headers, body=b""):
        """Send a hand-built request so framing can be wrong on purpose."""
        with socket.create_connection(self.server.server_address, timeout=3) as client:
            request = (request_line + "\r\nAuthorization: Bearer " + self.token + "\r\n"
                       + headers + "\r\n").encode() + body
            client.sendall(request)
            client.shutdown(socket.SHUT_WR)
            response = http.client.HTTPResponse(client)
            response.begin()
            status = response.status
            response.read()
        return status

    def last_user_text(self):
        for line in reversed(self.transcript.read_text(encoding="utf-8").splitlines()):
            try:
                record = json.loads(line)
            except ValueError:
                continue
            if isinstance(record, dict) and record.get("type") == "message":
                return record["message"]["content"]
        return None

    def test_upload_round_trips_exact_bytes_from_a_private_store(self):
        payload = b"\x89PNG\r\n\x1a\n" + bytes(range(256)) * 3
        record = self.upload_one(payload, name="Class Photos 世界 (1).png", mime="image/png")
        self.assertEqual(sorted(record), ["id", "mime", "name", "size"])
        self.assertRegex(record["id"], r"[0-9a-f]{32}\Z")
        self.assertEqual(record["name"], "Class Photos 世界 (1).png")
        self.assertEqual(record["mime"], "image/png")
        self.assertEqual(record["size"], len(payload))
        originals = self.stored(record["id"])
        self.assertEqual(len(originals), 1)
        self.assertEqual(originals[0].read_bytes(), payload)
        self.assertEqual(originals[0].stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.store / self.SESSION).stat().st_mode & 0o777, 0o700)
        self.assertNotIn(record["name"], originals[0].name)
        status, headers, body = self.fetch(record["id"])
        self.assertEqual(status, 200)
        self.assertEqual(headers["content-type"], "image/png")
        self.assertEqual(headers["x-content-type-options"], "nosniff")
        self.assertEqual(headers["cache-control"], "private, no-store")
        self.assertEqual(headers["content-length"], str(len(payload)))
        self.assertEqual(body, payload)

    def test_non_image_attachments_are_served_as_opaque_bytes(self):
        payload = b"%PDF-1.4 synthetic\n"
        record = self.upload_one(payload, name="report.pdf", mime="application/pdf")
        status, headers, body = self.fetch(record["id"])
        self.assertEqual(status, 200)
        self.assertEqual(headers["content-type"], "application/octet-stream")
        self.assertEqual(body, payload)

    def test_uploads_and_attachments_require_auth_and_their_own_session(self):
        self.assertEqual(self.upload(b"unauthorized", auth=False)[0], 401)
        self.assertFalse(self.store.exists())
        record = self.upload_one(b"synthetic", name="note.txt", mime="text/plain")
        self.assertEqual(self.fetch(record["id"], auth=False)[0], 401)
        self.use_session(self.OTHER)
        self.assertEqual(self.fetch(record["id"])[0], 404)
        status, _, body = self.request("/send", {"block": self.BLOCK, "kind": "submit",
                                                "value": "steal it", "attachments": [record["id"]]})
        self.assertEqual(status, 409)
        self.assertTrue(json.loads(body)["error"])
        self.assertEqual(self.terminal.persisted, [])
        self.assertEqual(self.last_user_text(), None)

    def test_non_omp_panes_reject_uploads_and_keep_terminal_submits(self):
        record = self.upload_one(b"synthetic")
        with patch.object(bridge, "_open_paths", lambda pid: set()):
            self.assertEqual(self.upload(b"more")[0], 409)
            self.assertEqual(self.fetch(record["id"])[0], 409)
            self.assertEqual(self.request("/send", {"block": self.BLOCK, "kind": "submit",
                                                    "value": "shell cmd"})[0], 200)
            self.assertEqual(self.terminal.submitted, ["shell cmd"])
        self.assertEqual(self.terminal.persisted, [])

    def test_framing_limits_and_partial_bodies_never_become_ids(self):
        query = "block=42&name=a.png&mime=image/png"
        cases = [
            ("POST /upload?" + query + " HTTP/1.0",
             "Content-Length: " + str(phone_uploads.MAX_UPLOAD_BYTES + 1) + "\r\n", b""),
            ("POST /upload?" + query + " HTTP/1.0", "Transfer-Encoding: chunked\r\n", b"0\r\n\r\n"),
            ("POST /upload?" + query + " HTTP/1.0",
             "Content-Length: 2\r\nContent-Length: 2\r\n", b"ab"),
            ("POST /upload?" + query + " HTTP/1.0", "Content-Length: 10\r\n", b"abc"),
            ("POST /upload?" + query + " HTTP/1.0", "Content-Length: 0\r\n", b""),
        ]
        for line, headers, body in cases:
            with self.subTest(headers=headers):
                self.assertEqual(self.raw_exchange(line, headers, body), 400)
        self.assertEqual([path.name for path in self.store.glob("**/*") if path.is_file()], [])
        self.assertEqual(self.upload(b"synthetic", name="../../etc/passwd")[0], 400)
        self.assertEqual(self.upload(b"synthetic", mime="not-a-mime")[0], 400)
        self.assertEqual(self.upload(b"synthetic", name="")[0], 400)
        self.assertEqual([path.name for path in self.store.glob("**/*") if path.is_file()], [])

    def test_submit_appends_native_file_mentions_to_the_accepted_prompt(self):
        image = b"\x89PNG\r\n\x1a\n" + bytes(range(64))
        first = self.upload_one(image, name="shot one.png", mime="image/png")
        second = self.upload_one(b"note body\n", name="notes.txt", mime="text/plain")
        draft = "Look at these\n> keep `code` & <markup>\n"
        attachments = [first["id"], second["id"]]
        status, content_type, body = self.request("/send", {"block": self.BLOCK, "kind": "submit",
                                                            "value": draft, "attachments": attachments})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"ok": True, "accepted": True})
        paths = [str(path) for upload in attachments for path in self.stored(upload)]
        expected = "\n".join([draft] + ['@"%s"' % path for path in paths])
        self.assertEqual(self.terminal.persisted, [expected])
        self.assertEqual(self.last_user_text(), expected)
        self.assertEqual(native_mentions(expected), paths)
        self.assertEqual(self.stored(first["id"])[0].read_bytes(), image)
        self.assertEqual(self.stored(second["id"])[0].read_bytes(), b"note body\n")
        status, _, body = self.request("/capture", {"block": self.BLOCK, "format": "conversation"})
        payload = json.loads(body)
        self.assertEqual(status, 200)
        self.assertEqual(payload["type"], "conversation")
        self.assertEqual(payload["session"], self.SESSION)
        message, = payload["messages"]
        self.assertEqual(message["role"], "user")
        self.assertEqual(message["text"], draft)
        self.assertEqual(message["attachments"], [
            {"id": first["id"], "name": "shot one.png", "mime": "image/png", "size": len(image)},
            {"id": second["id"], "name": "notes.txt", "mime": "text/plain", "size": 10}])

    def test_image_only_send_is_its_own_mention_and_still_renders(self):
        record = self.upload_one(b"\x89PNG\r\n\x1a\n0", name="photo.png", mime="image/png")
        status, _, body = self.request("/send", {"block": self.BLOCK, "kind": "submit",
                                                "value": "", "attachments": [record["id"]]})
        self.assertEqual(status, 200)
        expected = phone_uploads.reference(self.stored(record["id"])[0])
        self.assertEqual(self.terminal.persisted, [expected])
        status, _, body = self.request("/capture", {"block": self.BLOCK, "format": "conversation"})
        message, = json.loads(body)["messages"]
        self.assertEqual(message["text"], "")
        self.assertEqual([attachment["id"] for attachment in message["attachments"]], [record["id"]])

    def test_attachment_rejections_never_reach_the_pty(self):
        record = self.upload_one(b"synthetic")
        malformed = [
            {"attachments": [record["id"]] * 9},
            {"attachments": ["not-hex"]},
            {"attachments": [record["id"]], "kind": "text", "value": "x"},
            {"attachments": [record["id"]], "kind": "paste", "value": "x"},
            {"attachments": [], "value": ""},
            {"attachments": record["id"]},
        ]
        for case in malformed:
            with self.subTest(case=case):
                body = {"block": self.BLOCK, "kind": "submit", "value": "unused"}
                body.update(case)
                self.assertEqual(self.request("/send", body)[0], 400)
        for unknown in ({"attachments": ["0" * 32]}, {"attachments": [record["id"]] * 1 + ["1" * 32]}):
            with self.subTest(case=unknown):
                body = {"block": self.BLOCK, "kind": "submit", "value": "unused"}
                body.update(unknown)
                self.assertEqual(self.request("/send", body)[0], 409)
        self.assertEqual(self.terminal.persisted, [])
        self.assertEqual(self.terminal.pasted, [])
        self.assertEqual(self.last_user_text(), None)

    def test_a_failed_send_keeps_the_upload_for_a_retry(self):
        record = self.upload_one(b"synthetic bytes", name="keep.bin", mime="application/octet-stream")
        before = self.transcript.read_text(encoding="utf-8")
        self.terminal.fail_send = True
        status, _, body = self.request("/send", {"block": self.BLOCK, "kind": "submit",
                                                "value": "retry me", "attachments": [record["id"]]})
        self.assertEqual(status, 409)
        self.assertTrue(json.loads(body)["error"])
        self.assertEqual(self.transcript.read_text(encoding="utf-8"), before)
        self.assertEqual(self.stored(record["id"])[0].read_bytes(), b"synthetic bytes")
        self.assertEqual(self.fetch(record["id"])[0], 200)


if __name__ == "__main__":
    unittest.main()
