#!/usr/bin/env python3
"""Private, owner-only store for files the phone attaches to an OMP prompt.

An upload belongs to one canonical OMP transcript identity: every lookup is
keyed by the session id the bridge resolved from the pane's own open
transcript, so a stored file is never reachable from another block or another
computer. Originals are written once under a generated id (never the client's
filename, never a caller-supplied path) with owner-only permissions, and a
partial write never becomes a usable id.

`reference` renders the exact native OMP `@file` mention the bridge appends to
the submitted prompt; `enrich_conversation` maps those mention lines back to the
metadata the phone renders while leaving every other user line — including
code and markup — untouched. Nothing here decodes or embeds file contents.
"""
from __future__ import annotations

import json
import mimetypes
import os
import re
import secrets
import time
from pathlib import Path

__all__ = ["UPLOAD_ROOT", "MAX_UPLOAD_BYTES", "MAX_ATTACHMENTS", "ID_PATTERN",
           "session_dir", "reference", "store", "metadata", "original",
           "reference_index", "enrich_conversation"]

UPLOAD_ROOT = Path.home() / ".tern" / "uploads"
MAX_UPLOAD_BYTES = 20 * 1024 * 1024
MAX_ATTACHMENTS = 8
ID_PATTERN = re.compile(r"[0-9a-f]{32}\Z")
SESSION_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
MIME_PATTERN = re.compile(r"[A-Za-z0-9!#$&^_.+-]{1,127}/[A-Za-z0-9!#$&^_.+-]{1,127}\Z")
EXTENSION_PATTERN = re.compile(r"\.[a-z0-9]{1,8}\Z")
NAME_LIMIT = 255
SIDECAR_SUFFIX = ".meta.json"
WIRE_FIELDS = ("id", "name", "mime", "size")
READ_CHUNK = 64 * 1024


def _private_dir(path: Path) -> Path:
    """Create a directory chain's leaf and keep it owner-only."""
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)
    return path


def _session_path(session) -> Path:
    """One session's storage directory; validates the id without creating it."""
    if not isinstance(session, str) or not SESSION_PATTERN.fullmatch(session):
        raise ValueError("session must be an OMP session id")
    return UPLOAD_ROOT / session


def session_dir(session: str) -> Path:
    """The owner-only directory holding one session's uploads."""
    return _private_dir(_session_path(session))


def _sidecar(directory: Path, upload_id: str) -> Path:
    return directory / (upload_id + SIDECAR_SUFFIX)


def _display_name(name) -> str:
    """The client's own filename, kept verbatim as display metadata only."""
    if not isinstance(name, str) or not name or len(name) > NAME_LIMIT or "\0" in name:
        raise ValueError(f"name must be 1-{NAME_LIMIT} characters without NUL")
    if "/" in name or "\\" in name or name in (".", ".."):
        raise ValueError("name must be a filename, not a path")
    name.encode("utf-8")  # Reject lone JSON surrogate escapes before storing.
    return name


def _content_type(mime) -> str:
    """The stored content type: the client's when well formed, else octet-stream."""
    if mime is None or mime == "":
        return "application/octet-stream"
    if not isinstance(mime, str) or not MIME_PATTERN.fullmatch(mime):
        raise ValueError("mime must be a type/subtype")
    return mime


def _extension(content_type: str) -> str:
    """A safe, non-authoritative suffix so tools recognise the stored original."""
    guess = mimetypes.guess_extension(content_type, strict=True)
    if not isinstance(guess, str):
        return ""
    guess = guess.lower()
    return guess if EXTENSION_PATTERN.fullmatch(guess) else ""


def reference(path: Path) -> str:
    """The native OMP mention the bridge appends for one stored upload.

    Quoted form, so a home directory containing spaces still resolves; the
    generated path never contains a quote or a newline.
    """
    text = str(path)
    if '"' in text or "\n" in text:
        raise ValueError("stored path cannot be mentioned")
    return '@"' + text + '"'


def _write_body(stream, partial: Path, length: int) -> None:
    """Copy exactly `length` bytes of the request body into a fresh partial file."""
    written = 0
    with open(os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as out:
        while written < length:
            chunk = stream.read(min(READ_CHUNK, length - written))
            if not chunk:
                raise ValueError("incomplete body")
            out.write(chunk)
            written += len(chunk)
        out.flush()
        os.fsync(out.fileno())


def _write_sidecar(sidecar: Path, record: dict) -> None:
    """Persist one upload's metadata, atomically and owner-only."""
    partial = sidecar.with_name(sidecar.name + ".part")
    with open(os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w",
              encoding="utf-8") as out:
        json.dump(record, out, ensure_ascii=False)
        out.flush()
        os.fsync(out.fileno())
    os.replace(partial, sidecar)


def store(stream, length: int, *, session: str, name, mime) -> dict:
    """Persist one uploaded original and return its wire metadata.

    The body is read for exactly `length` bytes before anything is reachable,
    so a truncated body can never leave a usable id behind. Only this call's
    own partial file is ever removed; existing bytes and metadata are not.
    """
    directory = session_dir(session)
    display = _display_name(name)
    content_type = _content_type(mime)
    if not isinstance(length, int) or isinstance(length, bool) or not 0 < length <= MAX_UPLOAD_BYTES:
        raise ValueError("bad upload size")
    while True:
        upload_id = secrets.token_hex(16)
        original = directory / (upload_id + _extension(content_type))
        sidecar = _sidecar(directory, upload_id)
        if not original.exists() and not sidecar.exists():
            break
    partial = directory / ("." + upload_id + ".part")
    try:
        _write_body(stream, partial, length)
        os.replace(partial, original)
    finally:
        if partial.exists():
            partial.unlink()
    record = {"id": upload_id, "name": display, "mime": content_type, "size": length}
    _write_sidecar(sidecar, {**record, "created": time.time(), "file": original.name})
    return record


def _record(session, upload_id) -> dict | None:
    """The parsed, validated sidecar for one session-bound upload, or None."""
    if not isinstance(upload_id, str) or not ID_PATTERN.fullmatch(upload_id):
        return None
    try:
        directory = _session_path(session)
    except ValueError:
        return None
    try:
        record = json.loads(_sidecar(directory, upload_id).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    fields = (("id", str), ("name", str), ("mime", str), ("size", int), ("file", str))
    if not isinstance(record, dict) or record.get("id") != upload_id:
        return None
    if any(not isinstance(record.get(field), kind) or (kind is int and isinstance(record.get(field), bool))
           for field, kind in fields):
        return None
    if os.path.basename(record["file"]) != record["file"]:
        return None
    return record


def _wire(record: dict) -> dict:
    return {field: record[field] for field in WIRE_FIELDS}


def metadata(session: str, upload_id: str) -> dict | None:
    """The stored wire metadata for one session-bound upload, or None."""
    record = _record(session, upload_id)
    return None if record is None else _wire(record)


def original(session: str, upload_id: str) -> Path | None:
    """The stored original bytes for one session-bound upload, or None."""
    record = _record(session, upload_id)
    if record is None:
        return None
    try:
        path = _session_path(session) / record["file"]
    except ValueError:
        return None
    return path if path.is_file() else None


def reference_index(session) -> dict[str, dict]:
    """Every stored upload's mention line for one session, keyed by that line."""
    try:
        directory = _session_path(session)
    except ValueError:
        return {}
    index: dict[str, dict] = {}
    for sidecar in sorted(directory.glob("*" + SIDECAR_SUFFIX)):
        upload_id = sidecar.name[: -len(SIDECAR_SUFFIX)]
        record = _record(session, upload_id)
        if record is None:
            continue
        index[reference(directory / record["file"])] = _wire(record)
    return index


def enrich_conversation(payload: dict) -> dict:
    """Attach stored uploads to the user messages that mentioned them.

    Only the bridge's own generated mention lines are removed — exact whole
    lines, never trimmed — so user prose, code and markup stay byte-for-byte
    intact. A message that held nothing but mentions keeps an empty text so the
    phone can still render its attachment cards.
    """
    references = reference_index(payload.get("session"))
    messages = payload.get("messages")
    if not references or not isinstance(messages, list):
        return payload
    for message in messages:
        if not isinstance(message, dict) or message.get("role") != "user":
            continue
        text = message.get("text")
        if not isinstance(text, str) or not text:
            continue
        kept: list[str] = []
        attached: list[dict] = []
        seen: set[str] = set()
        for line in text.split("\n"):
            record = references.get(line)
            if record is None:
                kept.append(line)
            elif record["id"] not in seen:
                seen.add(record["id"])
                attached.append(_wire(record))
        if not attached:
            continue
        message["text"] = "\n".join(kept)
        message["attachments"] = attached
    return payload
