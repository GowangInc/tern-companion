#!/usr/bin/env python3
"""Run with python3 checks/BridgeBehaviorCheck.py (no installed Tern required).

HTTP checks model a live terminal's visible native surface and input state, not
CLI output echoes. Runner checks use real disposable Python child processes.
The release integration harness separately exercises the installed Tern CLI.
"""
import contextlib
import http.client
import io
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tern_bridge as bridge


class Terminal:
    """Functional boundary double: input changes state, capture reads a surface."""

    def __init__(self):
        self.old_shell = "PREVIOUS_SHELL_SYNTHETIC\n"
        self.visible = "ACTIVE_NATIVE_SYNTHETIC\nUnicode: café 世界\n"
        self.pending = ""
        self.submitted = []
        self.pasted = []
        self.new_sessions = []
        self.model_cycles = 0
        self.operations = 0
        self.failure = None

    def run(self, args, stdin_text=None):
        self.operations += 1
        if self.failure is not None:
            raise self.failure
        command = args[0]
        if command == "ls":
            return 0, json.dumps({"sessions": []})
        if command == "new":
            self.new_sessions.append(list(args))
            return 0, ""
        if command == "kill":
            return 0, ""
        if args[1] not in ("42", "qa/42", "Flow - demo/42"):
            raise RuntimeError("unknown synthetic block")
        if command == "capture":
            return 0, self.visible if "--surfaces" in args[2:] else self.old_shell
        if command != "send":
            raise RuntimeError("unsupported synthetic operation")
        kind, value = args[2:4]
        if kind == "text":
            for char in value:
                if char == "\r":
                    self.submitted.append(self.pending)
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
                self.pending = ""
            else:
                raise RuntimeError("unsupported synthetic key")
        return 0, ""


class BridgeHttpChecks(unittest.TestCase):
    def setUp(self):
        self.terminal = Terminal()
        self.stub = patch.object(bridge, "run_tern", self.terminal.run)
        self.stub.start()
        self.addCleanup(self.stub.stop)
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

    def test_capture_reads_active_native_surface_as_utf8(self):
        status, content_type, body = self.request("/capture", {"block": "qa/42"})
        self.assertEqual(status, 200)
        self.assertEqual(content_type, "text/plain; charset=utf-8")
        self.assertEqual(body.decode("utf-8"), self.terminal.visible)
        self.assertNotIn(self.terminal.old_shell.encode(), body)
        self.terminal.visible = "NEXT_NATIVE_FRAME\n"
        self.assertEqual(self.request("/capture", {"block": "42"})[2], b"NEXT_NATIVE_FRAME\n")

    def test_submit_is_one_operation_that_enters_text(self):
        self.assertEqual(self.send("submit", "synthetic café"), 200)
        self.assertEqual(self.terminal.submitted, ["synthetic café"])
        self.assertEqual(self.terminal.pending, "")
        self.assertEqual(self.terminal.operations, 1)

    def test_spaced_flow_session_can_submit_and_capture(self):
        self.assertEqual(self.send("submit", "Flow command", block="Flow - demo/42"), 200)
        self.assertEqual(self.terminal.submitted, ["Flow command"])
        status, _, screen = self.request("/capture", {"block": "Flow - demo/42"})
        self.assertEqual(status, 200)
        self.assertEqual(screen.decode("utf-8"), self.terminal.visible)

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
        for method, path in [("GET", "/health"), ("GET", "/sessions"),
                             ("POST", "/send"), ("POST", "/capture"),
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

    def test_new_session_creates_named_session(self):
        status, content_type, body = self.request("/new", {"name": "qa-42"})
        self.assertEqual(status, 200)
        self.assertEqual(content_type, "application/json")
        self.assertEqual(json.loads(body), {"ok": True})
        self.assertEqual(self.terminal.new_sessions, [["new", "session", "qa-42"]])
        self.assertEqual(self.terminal.operations, 1)

    def test_new_session_passes_absolute_cwd_and_omits_it_when_absent(self):
        self.assertEqual(self.request("/new", {"name": "qa-42", "cwd": "/tmp/qa"})[0], 200)
        self.assertEqual(self.terminal.new_sessions, [["new", "session", "qa-42", "--cwd", "/tmp/qa"]])
        # An absent or empty cwd means "inherit the bridge's directory": no --cwd argument.
        self.assertEqual(self.request("/new", {"name": "qa-42"})[0], 200)
        self.assertEqual(self.request("/new", {"name": "qa-42", "cwd": ""})[0], 200)
        self.assertEqual(self.terminal.new_sessions[1:], [["new", "session", "qa-42"]] * 2)

    def test_new_session_accepts_maximum_length_name(self):
        longest = "a" * 64
        self.assertEqual(self.request("/new", {"name": longest})[0], 200)
        self.assertEqual(self.terminal.new_sessions, [["new", "session", longest]])

    def test_new_session_rejections_never_reach_cli(self):
        for name in ["Flow - demo", "", "a" * 65, "-qa", "qa/42", "qa\0", None, 42]:
            with self.subTest(name=name):
                self.assertEqual(self.request("/new", {"name": name})[0], 400)
        for cwd in ["relative/path", "/a/../b", "/tmp\0qa", "/" + "a" * 4096, 42]:
            with self.subTest(cwd=cwd):
                self.assertEqual(self.request("/new", {"name": "qa-42", "cwd": cwd})[0], 400)
        self.assertEqual(self.terminal.new_sessions, [])
        self.assertEqual(self.terminal.operations, 0)

    def test_new_session_requires_authorization(self):
        self.assertEqual(self.request("/new", {"name": "qa-42"}, auth=False)[0], 401)
        self.assertEqual(self.terminal.new_sessions, [])
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


if __name__ == "__main__":
    unittest.main()
