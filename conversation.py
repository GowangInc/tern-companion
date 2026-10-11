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
never stripped. The HTTP layer owns the 1 MiB `MAX_OUT` cap; this module does
not truncate.

Images the active branch actually shows are staged into the session's private
store via phone_uploads and surface as ordinary `attachments` wire metadata
on their message: native `{type:"image"}` content blocks and
`fileMention` `files[].image` payloads (inline base64 or `blob:sha256:`
references resolved from OMP's real content-addressed blob store), toolResult
`details.images`, and assistant image outputs: Markdown image/image-file links
whose destination is a local raster file (angle-bracket paths with spaces,
`file://` URLs, and paths relative to the session's own `cwd`), explicit
`MEDIA:<path>` markers, and inline `data:image/...;base64,` Markdown images
whose payload decodes to a bounded raster. External URLs, fenced code, inline
code spans, and unreadable, malformed or non-raster destinations stay
byte-for-byte untouched; a missing image never breaks the capture. Every
capture republishes the referenced media ids so hidden or abandoned-branch
images stay unservable.

A missing path raises `FileNotFoundError`. Complete (newline-terminated)
records are decoded and parsed strictly, so a malformed terminated record or a
missing/blank session header raises `ValueError`; only an unterminated final
record is treated as an interrupted append and ignored.

`read_session_header` answers only the canonical identity question — which
session, if any, a candidate file is — and reads just the leading records, so
the bridge can confirm a pane's own open transcript without loading a whole
session.
"""
from __future__ import annotations

import base64
import json
import os
import re
from pathlib import Path
from datetime import datetime
from urllib.parse import unquote, urlsplit

import phone_uploads

__all__ = ["read_conversation", "read_session_header"]

HEADER_RECORD_LIMIT = 64
HEADER_LINE_BYTES = 1024 * 1024

# Native OMP shapes (verified against coding-agent session sources): image
# payloads are {type:"image", data: base64 | "blob:sha256:<64hex>", mimeType};
# oversized base64 and provider data URLs are externalized to the
# content-addressed blob store at <agentDir>/blobs/<sha256-of-bytes>.
BLOB_REF_PREFIX = "blob:sha256:"
BLOB_HASH_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
DATA_URL_PREFIX = "data:image/"
MAX_IMAGE_BASE64 = (phone_uploads.MAX_UPLOAD_BYTES // 3 + 1) * 4 + 1024
IMAGE_LINK_PATTERN = re.compile(
    r"(!)?\[([^\]\n]*)\]\(\s*(<[^>\n]+>|[^\s)]+)(?:\s+[\"'][^\"'\n]*[\"'])?\s*\)")
IMAGE_LINK_EXTENSIONS = frozenset({".png", ".jpg", ".jpeg", ".webp", ".gif"})
# The explicit marker OMP assistants emit for a generated file, e.g.
# `Here it is: MEDIA:/tmp/shot.png`; angle brackets carry paths with spaces.
MEDIA_MARKER_PATTERN = re.compile(r"(?<![\w(])MEDIA:(<[^<>\n]*>|[^\s<>]+)")
SCHEME_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*:")


def _blob_dirs() -> list[Path]:
    """Candidate roots of OMP's content-addressed blob store, most specific first."""
    override = os.environ.get("PI_CODING_AGENT_DIR")
    if override:
        return [Path(override) / "blobs"]
    roots: list[Path] = []
    xdg_data = os.environ.get("XDG_DATA_HOME")
    if xdg_data:
        roots.append(Path(xdg_data) / "omp" / "blobs")
    roots.append(Path.home() / ".omp" / "agent" / "blobs")
    return roots


def _blob_bytes(ref) -> bytes | None:
    """The bytes behind a native `blob:sha256:<hash>` reference, or None."""
    if not isinstance(ref, str) or not ref.startswith(BLOB_REF_PREFIX):
        return None
    digest = ref[len(BLOB_REF_PREFIX):]
    if not BLOB_HASH_PATTERN.fullmatch(digest):
        return None
    for directory in _blob_dirs():
        try:
            candidate = directory / digest
            if not candidate.is_file():
                continue
            if candidate.stat().st_size > MAX_IMAGE_BASE64:
                return None
            data = candidate.read_bytes()
        except OSError:
            continue
        if data.startswith(b"data:image/"):
            # `image_url` externalization stores the whole data URL as the blob.
            _, separator, payload = data.partition(b";base64,")
            if not separator:
                return None
            try:
                return base64.b64decode(payload)
            except ValueError:
                return None
        return data
    return None


def _image_bytes(block) -> tuple[bytes | None, str | None]:
    """(raw bytes, declared mime) for one native image payload, else (None, mime)."""
    if not isinstance(block, dict) or block.get("type") != "image":
        return None, None
    mime = block.get("mimeType")
    mime = mime if isinstance(mime, str) else None
    data = block.get("data")
    if not isinstance(data, str) or not data or len(data) > MAX_IMAGE_BASE64:
        return None, mime
    if data.startswith(BLOB_REF_PREFIX):
        return _blob_bytes(data), mime
    if data.startswith(DATA_URL_PREFIX):
        _, separator, payload = data.partition(";base64,")
        if not separator:
            return None, mime
        data = payload
    try:
        return base64.b64decode(data), mime
    except ValueError:
        return None, mime


def _attach(attachments: list[dict], record) -> None:
    """Append one wire record unless its id is already attached."""
    if isinstance(record, dict) and all(item["id"] != record.get("id") for item in attachments):
        attachments.append(record)


def _stage_image(session: str, block, name=None) -> dict | None:
    """Stage one native image payload; None when it is absent or ineligible."""
    data, mime = _image_bytes(block)
    if data is None:
        return None
    return phone_uploads.stage_media_bytes(session, data, name=name, mime=mime)


def _content_attachments(session: str, content, attachments: list[dict]) -> None:
    """Stage every image block of one content list into `attachments`."""
    if not isinstance(content, list):
        return
    for block in content:
        if isinstance(block, dict) and block.get("type") == "image":
            _attach(attachments, _stage_image(session, block))


def _local_image_path(dest: str, cwd: str) -> Path | None:
    """The local file a Markdown destination names, or None for external ones."""
    if dest.startswith("<") and dest.endswith(">"):
        dest = dest[1:-1]
    if not dest:
        return None
    scheme = SCHEME_PATTERN.match(dest)
    if scheme:
        if scheme.group(0).lower() != "file:":
            return None
        parsed = urlsplit(dest)
        if parsed.netloc not in ("", "localhost"):
            return None
        dest = unquote(parsed.path)
    elif dest.startswith("~"):
        dest = os.path.expanduser(dest)
    path = Path(dest)
    if not path.is_absolute():
        if not cwd:
            return None
        path = Path(cwd) / path
    return path


def _stage_data_url(session: str, dest: str) -> dict | None:
    """Stage the bytes of one inline `data:image/...;base64,` destination."""
    if len(dest) > MAX_IMAGE_BASE64:
        return None
    _, separator, payload = dest.partition(";base64,")
    if not separator:
        return None
    try:
        data = base64.b64decode(payload, validate=True)
    except ValueError:
        return None
    return phone_uploads.stage_media_bytes(session, data)


def _replace_link(match, session: str, cwd: str, attachments: list[dict]) -> str:
    """Stage one matched local or inline data image link, or leave it untouched."""
    dest = match.group(3)
    if dest.startswith("<") and dest.endswith(">"):
        dest = dest[1:-1]
    if dest.startswith(DATA_URL_PREFIX):
        record = _stage_data_url(session, dest)
        if record is None:
            return match.group(0)
        _attach(attachments, record)
        return match.group(2).strip()
    path = _local_image_path(dest, cwd)
    if path is None:
        return match.group(0)
    if match.group(1) != "!" and path.suffix.lower() not in IMAGE_LINK_EXTENSIONS:
        return match.group(0)
    record = phone_uploads.stage_media(session, path, name=path.name)
    if record is None:
        return match.group(0)
    _attach(attachments, record)
    caption = match.group(2).strip()
    return caption if caption else path.name


def _replace_media(match, session: str, cwd: str, attachments: list[dict]) -> str:
    """Stage one `MEDIA:<path>` marker, or leave the marker untouched."""
    path = _local_image_path(match.group(1), cwd)
    if path is None:
        return match.group(0)
    record = phone_uploads.stage_media(session, path, name=path.name)
    if record is None:
        return match.group(0)
    _attach(attachments, record)
    return ""


def _inline_code_spans(line: str) -> list[tuple[int, int]]:
    """Character spans of one line's inline code, keyed by matched backtick runs."""
    spans: list[tuple[int, int]] = []
    index = 0
    while index < len(line):
        if line[index] != "`":
            index += 1
            continue
        start = index
        while index < len(line) and line[index] == "`":
            index += 1
        ticks = line[start:index]
        cursor = index
        while True:
            close = line.find(ticks, cursor)
            if close < 0:
                break
            end = close + len(ticks)
            while end < len(line) and line[end] == "`":
                end += 1
            if end - close == len(ticks):
                spans.append((start, end))
                index = end
                break
            cursor = end
    return spans


def _sub_generated_images(session: str, cwd: str, segment: str, attachments: list[dict]) -> str:
    """Stage the image constructs of one text segment outside any inline code."""
    segment = IMAGE_LINK_PATTERN.sub(
        lambda match: _replace_link(match, session, cwd, attachments), segment)
    return MEDIA_MARKER_PATTERN.sub(
        lambda match: _replace_media(match, session, cwd, attachments), segment)


def _rewrite_line_images(session: str, cwd: str, line: str, attachments: list[dict]) -> str:
    """Rewrite one non-fenced line; inline code spans stay byte-for-byte."""
    out: list[str] = []
    previous = 0
    for start, end in _inline_code_spans(line):
        out.append(_sub_generated_images(session, cwd, line[previous:start], attachments))
        out.append(line[start:end])
        previous = end
    out.append(_sub_generated_images(session, cwd, line[previous:], attachments))
    return "".join(out)


def _rewrite_local_images(session: str, cwd: str, text: str, attachments: list[dict]) -> str:
    """Replace generated image constructs with captions plus staged attachments.

    Fenced code lines and inline code spans are copied verbatim; external URLs
    and markers/links whose file is missing or not a supported raster stay
    untouched. A staged `MEDIA:<path>` marker is dropped from the text.
    """
    out: list[str] = []
    fence = None
    for line in text.split("\n"):
        marker = re.match(r" {0,3}(`{3,}|~{3,})(.*)$", line)
        if fence is not None:
            if (marker and marker[1][0] == fence[0] and len(marker[1]) >= fence[1]
                    and not marker[2].strip()):
                fence = None
            out.append(line)
            continue
        if marker and (marker[1][0] != "`" or "`" not in marker[2]):
            fence = (marker[1][0], len(marker[1]))
            out.append(line)
            continue
        out.append(_rewrite_line_images(session, cwd, line, attachments))
    return "\n".join(out)


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


def _session_id(record) -> str | None:
    """The id of a canonical session header record, or None when it is not one."""
    if not isinstance(record, dict) or record.get("type") != "session":
        return None
    header_id = record.get("id")
    return header_id if isinstance(header_id, str) and header_id else None


def read_session_header(path: Path) -> str | None:
    """The session id a file's canonical header declares, or None when it has none.

    Only the leading records are read, and an unparsable line is skipped rather
    than fatal: this answers "is this OMP's own transcript, and for which
    session?" for a file another process merely holds open.
    """
    with Path(path).open("rb") as stream:
        for _ in range(HEADER_RECORD_LIMIT):
            line = stream.readline(HEADER_LINE_BYTES)
            if not line:
                break
            try:
                header_id = _session_id(json.loads(line.decode("utf-8")))
            except (UnicodeError, ValueError):
                continue
            if header_id:
                return header_id
    return None


def _read_entries(path: Path) -> tuple[str, str, list[dict]]:
    """Return (session id, session cwd, DAG entries) from a session file.

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
    cwd = ""
    entries: list[dict] = []
    for record in records:
        if not isinstance(record, dict):
            raise ValueError("session record is not a JSON object")
        kind = record.get("type")
        if kind == "title":
            continue
        if kind == "session":
            if not session:
                header_id = _session_id(record)
                if header_id is None:
                    raise ValueError("session header has no id")
                session = header_id
                header_cwd = record.get("cwd")
                cwd = header_cwd if isinstance(header_cwd, str) else ""
            continue
        if "id" in record and "parentId" in record:
            entries.append(record)
    if not session:
        raise ValueError("session file has no session header")
    return session, cwd, entries


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


def _entry_messages(entry: dict, session: str, cwd: str) -> list[dict]:
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
        attachments: list[dict] = []
        _content_attachments(session, message.get("content"), attachments)
        if not text.strip() and not attachments:
            return []
        wire = _message(entry_id, "user", text, "markdown")
        if attachments:
            wire["attachments"] = attachments
        return [wire]
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
                    attachments = []
                    text = _rewrite_local_images(session, cwd, text, attachments)
                    if attachments and not text.strip():
                        text = "[image]"
                    wire = _message(entry_id, "assistant", text, "markdown")
                    if attachments:
                        wire["attachments"] = attachments
                    emitted.append(wire)
            elif block.get("type") == "image":
                attachments = []
                _attach(attachments, _stage_image(session, block))
                if attachments:
                    wire = _message(entry_id, "assistant", "[image]", "markdown")
                    wire["attachments"] = attachments
                    emitted.append(wire)
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
        attachments = []
        _content_attachments(session, message.get("content"), attachments)
        details = message.get("details")
        if isinstance(details, dict) and isinstance(details.get("images"), list):
            for image in details["images"]:
                _attach(attachments, _stage_image(session, image))
        wire = _message(entry_id, "tool", _text_of(message.get("content")), "text", message.get("toolName"))
        if attachments:
            wire["attachments"] = attachments
        return [wire]
    if role == "fileMention":
        files = message.get("files")
        if not isinstance(files, list):
            return []
        attachments = []
        for file in files:
            if not isinstance(file, dict):
                continue
            path = file.get("path")
            name = Path(path).name if isinstance(path, str) and path else None
            resolved = None
            if isinstance(path, str) and path:
                try:
                    resolved = Path(path).resolve()
                except (OSError, ValueError):
                    resolved = None
            if resolved is not None and phone_uploads.is_stored_upload(session, resolved):
                continue  # phone uploads already render via enrich_conversation
            record = _stage_image(session, file.get("image"), name=name)
            if record is None and resolved is not None:
                record = phone_uploads.stage_media(session, resolved, name=name)
            _attach(attachments, record)
        if not attachments:
            return []
        wire = _message(entry_id, "user", "[image]", "markdown")
        wire["attachments"] = attachments
        return [wire]
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
    session, cwd, entries = _read_entries(Path(path))
    messages: list[dict] = []
    media_ids: list[str] = []
    for entry in _active_path(entries):
        timestamp = None
        saved_time = entry.get("timestamp")
        if isinstance(saved_time, str):
            try:
                moment = datetime.fromisoformat(saved_time.replace("Z", "+00:00"))
                if moment.tzinfo is not None:
                    timestamp = round(moment.timestamp() * 1000)
            except (ValueError, OverflowError, OSError):
                pass
        for wire in _entry_messages(entry, session, cwd):
            if timestamp is not None:
                wire["timestamp"] = timestamp
            attachments = wire.get("attachments")
            if isinstance(attachments, list):
                media_ids.extend(item["id"] for item in attachments
                                 if isinstance(item, dict) and isinstance(item.get("id"), str))
            messages.append(wire)
    phone_uploads.publish_media(session, media_ids)
    return {"type": "conversation", "session": session, "messages": messages}
