#!/usr/bin/env python3
"""Private, owner-only store for files the phone attaches to an OMP prompt.

An upload belongs to one canonical OMP transcript identity: every lookup is
keyed by the session id the bridge resolved from the pane's own open
transcript, so a stored file is never reachable from another block or another
computer. Originals are written once under a generated id (never the client's
filename, never a caller-supplied path) with owner-only permissions, and a
partial write never becomes a usable id.

`reference` renders the exact native OMP mention the bridge appends to the
submitted prompt; `enrich_conversation` maps those mention lines back to the
metadata the phone renders while leaving every other user line — including
code and markup — untouched. Nothing here decodes or embeds file contents.

`stage_media`/`stage_media_bytes` let conversation.py stage raster images the
canonical active branch actually references (image payloads, blob refs and
local Markdown image links) into the same owner-only store, under a stable
content-derived id so the 2-second capture refresh never recopies or churns
ids. Only PNG/JPEG/WebP/GIF magic bytes are accepted; the claimed mime and
display name are never trusted for typing. Staged media sidecars carry
"kind": "media" and are served by metadata()/original() only while listed in
the session's `.visible-media.json` manifest, which read_conversation
republishes from the active branch on every capture — media referenced only
by hidden or abandoned-branch records is never retrievable. Uploads (no
"kind") are unaffected by the manifest.
"""
from __future__ import annotations

import hashlib
import json
import mimetypes
import os
import re
import secrets
import time
from pathlib import Path

__all__ = ["UPLOAD_ROOT", "MAX_UPLOAD_BYTES", "MAX_ATTACHMENTS", "ID_PATTERN",
           "MEDIA_MIMES", "session_dir", "reference", "store", "metadata",
           "original", "reference_index", "enrich_conversation",
           "is_stored_upload", "stage_media", "stage_media_bytes", "publish_media"]

UPLOAD_ROOT = Path.home() / ".tern" / "uploads"
MAX_UPLOAD_BYTES = 20 * 1024 * 1024
MAX_ATTACHMENTS = 8
MEDIA_MIMES = frozenset({"image/png", "image/jpeg", "image/webp", "image/gif"})
ID_PATTERN = re.compile(r"[0-9a-f]{32}\Z")
SESSION_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
MIME_PATTERN = re.compile(r"[A-Za-z0-9!#$&^_.+-]{1,127}/[A-Za-z0-9!#$&^_.+-]{1,127}\Z")
EXTENSION_PATTERN = re.compile(r"\.[a-z0-9]{1,8}\Z")
NAME_LIMIT = 255
SIDECAR_SUFFIX = ".meta.json"
VISIBLE_MANIFEST = ".visible-media.json"
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
    partial = sidecar.with_name(sidecar.name + "." + secrets.token_hex(16) + ".part")
    with open(os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w",
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


def _raster_mime(data: bytes) -> str | None:
    """The magic-byte raster type of a bounded buffer, or None."""
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def _media_name(name) -> str:
    """Display name for staged media; falls back to "image" instead of raising."""
    if isinstance(name, str) and name:
        candidate = name.replace("\\", "/").rsplit("/", 1)[-1]
        try:
            return _display_name(candidate)
        except ValueError:
            pass
    return "image"


def _write_bytes(data: bytes, partial: Path) -> None:
    """Write a fresh partial file with staged media bytes, owner-only."""
    with open(os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as out:
        out.write(data)
        out.flush()
        os.fsync(out.fileno())


def _stage_bytes(session: str, data: bytes, name, mime) -> dict | None:
    """Type, address and store bounded raster bytes under a stable content id."""
    try:
        if not 0 < len(data) <= MAX_UPLOAD_BYTES:
            return None
        content_type = _raster_mime(data)
        if content_type is None:
            return None
        media_id = hashlib.sha256(data).hexdigest()[:32]
        existing = _record(session, media_id)
        if existing is not None:
            return _wire(existing)
        directory = session_dir(session)
        stored = directory / (media_id + _extension(content_type))
        partial = directory / ("." + media_id + "." + secrets.token_hex(16) + ".part")
        try:
            _write_bytes(data, partial)
            os.replace(partial, stored)
        finally:
            try:
                partial.unlink()
            except OSError:
                pass
        record = {"id": media_id, "name": _media_name(name),
                  "mime": content_type, "size": len(data)}
        _write_sidecar(_sidecar(directory, media_id),
                       {**record, "created": time.time(), "file": stored.name, "kind": "media"})
        return record
    except (OSError, ValueError):
        return None


def stage_media(session: str, source, *, name=None, mime=None) -> dict | None:
    """Stage one referenced raster file into the session store, or None.

    `source` must be a path a canonical visible record actually referenced.
    A source that already is one of this session's stored originals returns
    that record's existing wire metadata, so a phone upload never gains a
    duplicate preview. Never raises: ineligible input is simply not staged.
    """
    try:
        directory = _session_path(session)
    except ValueError:
        return None
    try:
        resolved = Path(source).resolve(strict=True)
    except (OSError, TypeError, ValueError):
        return None
    if not resolved.is_file():
        return None
    for sidecar in sorted(directory.glob("*" + SIDECAR_SUFFIX)):
        record = _record(session, sidecar.name[: -len(SIDECAR_SUFFIX)])
        if record is None:
            continue
        try:
            if (directory / record["file"]).resolve() == resolved:
                return _wire(record)
        except OSError:
            continue
    try:
        size = resolved.stat().st_size
        if not 0 < size <= MAX_UPLOAD_BYTES:
            return None
        data = resolved.read_bytes()
    except OSError:
        return None
    display = name if isinstance(name, str) and name else resolved.name
    return _stage_bytes(session, data, display, mime)


def stage_media_bytes(session: str, data, *, name=None, mime=None) -> dict | None:
    """Stage in-memory raster bytes (inline/base64 image payloads), or None."""
    try:
        _session_path(session)
    except ValueError:
        return None
    if not isinstance(data, (bytes, bytearray, memoryview)):
        return None
    return _stage_bytes(session, bytes(data), name, mime)


def publish_media(session: str, ids) -> None:
    """Publish the media ids the latest active-branch capture references.

    The manifest is rewritten only when the id set changed, so the capture
    refresh never churns it. Never raises; a session with nothing staged keeps
    no manifest at all.
    """
    try:
        directory = _session_path(session)
    except ValueError:
        return
    if not isinstance(ids, (list, tuple, set)):
        return
    valid = sorted({item for item in ids
                    if isinstance(item, str) and ID_PATTERN.fullmatch(item)})
    if not directory.is_dir():
        return
    manifest = directory / VISIBLE_MANIFEST
    payload = json.dumps({"ids": valid}, ensure_ascii=False)
    try:
        if manifest.is_file() and manifest.read_text(encoding="utf-8") == payload:
            return
    except OSError:
        return
    try:
        _write_sidecar(manifest, {"ids": valid})
    except OSError:
        pass


def is_stored_upload(session, path) -> bool:
    """True when this exact file is one of the session's stored uploads."""
    try:
        resolved = Path(path).resolve()
        directory = _session_path(session)
    except (OSError, TypeError, ValueError):
        return False
    for sidecar in sorted(directory.glob("*" + SIDECAR_SUFFIX)):
        record = _record(session, sidecar.name[: -len(SIDECAR_SUFFIX)])
        if record is None or record.get("kind") == "media":
            continue
        try:
            if (directory / record["file"]).resolve() == resolved:
                return True
        except OSError:
            continue
    return False


def _visible_ids(session) -> set:
    """The media ids the latest active-branch capture published, or an empty set."""
    try:
        data = json.loads((_session_path(session) / VISIBLE_MANIFEST).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return set()
    ids = data.get("ids") if isinstance(data, dict) else None
    if not isinstance(ids, list):
        return set()
    return {item for item in ids if isinstance(item, str) and ID_PATTERN.fullmatch(item)}


def _servable(session, record: dict) -> bool:
    """Uploads are always served; staged media only while the active branch shows it."""
    return record.get("kind") != "media" or record["id"] in _visible_ids(session)


def metadata(session: str, upload_id: str) -> dict | None:
    """The stored wire metadata for one session-bound upload, or None."""
    record = _record(session, upload_id)
    if record is None or not _servable(session, record):
        return None
    return _wire(record)


def original(session: str, upload_id: str) -> Path | None:
    """The stored original bytes for one session-bound upload, or None."""
    record = _record(session, upload_id)
    if record is None or not _servable(session, record):
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
        # read_conversation may already have attached staged media; merge by id.
        merged: list[dict] = []
        merged_ids: set[str] = set()
        prior = message.get("attachments")
        if isinstance(prior, list):
            for item in prior:
                if isinstance(item, dict) and isinstance(item.get("id"), str) \
                        and item["id"] not in merged_ids:
                    merged_ids.add(item["id"])
                    merged.append(item)
        for record in attached:
            if record["id"] not in merged_ids:
                merged_ids.add(record["id"])
                merged.append(record)
        message["attachments"] = merged
    return payload
