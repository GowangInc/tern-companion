#!/usr/bin/env python3
"""Build a clean, role-tagged conversation payload from an OMP session JSONL file.

`read_conversation` backs tern_bridge's `POST /capture` `format="conversation"`
response. It walks the active `parentId` branch of the session DAG (never
abandoned sibling branches), and turns it into a flat list of
user/assistant/tool/context messages so the phone renders a conversation rather
than the raw injected `<files>` context dump.

Excluded by design: assistant thinking, developer/system messages, hidden
(`display: false`) custom messages, synthetic user injections, and every
metadata entry kind. Steering user messages carry genuine user text, so they
stay visible. Text is kept verbatim — angle-bracket tags and fenced code are
never stripped. Embedded image data is never emitted. The HTTP layer owns the
1 MiB `MAX_OUT` cap; this module does not truncate.

A missing path raises `FileNotFoundError`. Complete (newline-terminated)
records are decoded and parsed strictly, so a malformed terminated record or a
missing/blank session header raises `ValueError`; only an unterminated final
record is treated as an interrupted append and ignored.
"""
from __future__ import annotations

import json
from pathlib import Path

__all__ = ["read_conversation"]


def _text_of(content) -> str:
    """Join the text blocks of an AgentMessage content field; images become `[image]`."""
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts: list[str] = []
    has_image = False
    for block in content:
        if not isinstance(block, dict):
            continue
        if block.get("type") == "text" and isinstance(block.get("text"), str):
            parts.append(block["text"])
        elif block.get("type") == "image":
            has_image = True
    if parts:
        return "\n".join(parts)
    return "[image]" if has_image else ""


def _message(entry_id: str, role: str, text: str, fmt: str, name=None) -> dict:
    """One wire message. `name` is only carried for tool and context roles."""
    message = {"id": entry_id, "role": role, "text": text, "format": fmt}
    if name is not None:
        message["name"] = name
    return message


def _read_entries(path: Path) -> tuple[str, list[dict]]:
    """Return (session id, DAG entries) from a session file.

    Only the final, unterminated record may be an interrupted append: it is
    dropped when it is not complete valid JSON. Every newline-terminated record
    is parsed strictly, so a malformed one propagates `ValueError` instead of
    being silently skipped. A missing session header is also a `ValueError`.
    """
    raw = path.read_bytes()
    head, separator, tail = raw.rpartition(b"\n")
    complete, pending = (head, tail) if separator else (b"", raw)
    records: list[dict] = []
    if complete:
        for line in complete.decode("utf-8").split("\n"):
            records.append(json.loads(line))
    if pending:
        try:
            trailing = json.loads(pending.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            trailing = None
        if isinstance(trailing, dict):
            records.append(trailing)
    session = ""
    entries: list[dict] = []
    for record in records:
        if not isinstance(record, dict):
            raise ValueError("session record is not a JSON object")
        kind = record.get("type")
        if kind == "title":
            continue
        if kind == "session":
            if not session:
                header_id = record.get("id")
                if not isinstance(header_id, str) or not header_id:
                    raise ValueError("session header has no id")
                session = header_id
            continue
        if "id" in record and "parentId" in record:
            entries.append(record)
    if not session:
        raise ValueError("session file has no session header")
    return session, entries


def _active_path(entries: list[dict]) -> list[dict]:
    """Root-first entries of the persisted leaf's parent chain, cycle-safe."""
    if not entries:
        return []
    by_id = {entry["id"]: entry for entry in entries}
    path: list[dict] = []
    seen: set[str] = set()
    current = entries[-1]
    while isinstance(current, dict) and current["id"] not in seen:
        seen.add(current["id"])
        path.append(current)
        parent_id = current.get("parentId")
        current = by_id.get(parent_id) if isinstance(parent_id, str) else None
    path.reverse()
    # A `/clear` boundary starts the collapsed live transcript: everything at or
    # before the latest one belongs to the discarded pre-reset screen.
    boundary = -1
    for index, entry in enumerate(path):
        if entry.get("type") == "reset_boundary":
            boundary = index
    return path[boundary + 1 :] if boundary >= 0 else path


def _entry_messages(entry: dict) -> list[dict]:
    """Map one DAG entry to its wire messages (empty for metadata-only entries)."""
    kind = entry.get("type")
    entry_id = entry["id"]
    if kind == "custom_message":
        if entry.get("display") is False:
            return []
        text = _text_of(entry.get("content"))
        return [_message(entry_id, "context", text, "markdown", entry.get("customType"))] if text.strip() else []
    if kind != "message":
        return []
    message = entry.get("message")
    if not isinstance(message, dict):
        return []
    role = message.get("role")
    if role == "user":
        # synthetic inputs are system injections; steering carries genuine user text.
        if message.get("synthetic"):
            return []
        text = _text_of(message.get("content"))
        return [_message(entry_id, "user", text, "markdown")] if text.strip() else []
    if role == "assistant":
        content = message.get("content")
        if not isinstance(content, list):
            return []
        emitted: list[dict] = []
        for block in content:
            if not isinstance(block, dict):
                continue
            if block.get("type") == "text":
                text = block.get("text")
                if isinstance(text, str) and text.strip():
                    emitted.append(_message(entry_id, "assistant", text, "markdown"))
            elif block.get("type") == "toolCall":
                arguments = block.get("arguments")
                if arguments is None:
                    arguments = {}
                text = (
                    json.dumps(arguments, ensure_ascii=False, indent=2)
                    if isinstance(arguments, dict)
                    else json.dumps(arguments, ensure_ascii=False)
                )
                emitted.append(_message(entry_id, "tool", text, "text", block.get("name")))
        return emitted
    if role == "toolResult":
        return [_message(entry_id, "tool", _text_of(message.get("content")), "text", message.get("toolName"))]
    if role in ("custom", "hookMessage"):
        if message.get("display") is False:
            return []
        text = _text_of(message.get("content"))
        return [_message(entry_id, "context", text, "markdown", message.get("customType"))] if text.strip() else []
    if role in ("bashExecution", "pythonExecution"):
        source = message.get("command") if role == "bashExecution" else message.get("code")
        text = source if isinstance(source, str) else ""
        output = message.get("output")
        if isinstance(output, str) and output:
            text = f"{text}\n\n{output}"
        if not text.strip():
            return []
        return [_message(entry_id, "tool", text, "text", "bash" if role == "bashExecution" else "python")]
    return []


def read_conversation(path: Path) -> dict:
    """Build the `format="conversation"` payload for one session file."""
    session, entries = _read_entries(Path(path))
    messages: list[dict] = []
    for entry in _active_path(entries):
        messages.extend(_entry_messages(entry))
    return {"type": "conversation", "session": session, "messages": messages}
