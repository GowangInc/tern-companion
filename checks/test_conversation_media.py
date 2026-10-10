#!/usr/bin/env python3
"""Behavior checks for conversation image staging (no installed Tern required).

Run with `python3 checks/test_conversation_media.py` or via the unittest
discovery in checks/run_all.py. Covers the consumer-visible boundaries of
conversation.py + phone_uploads.py media handling: magic-byte typing, stable
content ids, active-branch visibility gating, native OMP image/blob/fileMention
shapes, Markdown image-link rewriting, and traversal/malformed-input safety.
All fixtures are synthetic; no private transcript or credential data is used.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import threading
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import conversation
import phone_uploads

PNG = b"\x89PNG\r\n\x1a\n" + bytes(range(32))
JPEG = b"\xff\xd8\xff\xe0" + bytes(range(32))
GIF = b"GIF89a" + bytes(range(32))
WEBP = b"RIFF" + (36).to_bytes(4, "little") + b"WEBPVP8 " + bytes(20)


def media_id(data: bytes) -> str:
    """The stable id stage_media derives for these exact bytes."""
    return hashlib.sha256(data).hexdigest()[:32]


class MediaStoreChecks(unittest.TestCase):
    """phone_uploads staging, stability, dedupe and visibility gating."""

    SESSION = "media-store-checks"

    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name) / "uploads"
        self.root_patch = patch.object(phone_uploads, "UPLOAD_ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def stage_file(self, data: bytes, name: str = "shot.png"):
        source = Path(self.work.name) / name
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_bytes(data)
        return phone_uploads.stage_media(self.SESSION, source), source

    def test_magic_bytes_type_all_supported_rasters(self):
        cases = [("a.png", PNG, "image/png"), ("b.jpg", JPEG, "image/jpeg"),
                 ("c.gif", GIF, "image/gif"), ("d.webp", WEBP, "image/webp")]
        for name, data, mime in cases:
            record, source = self.stage_file(data, name)
            self.assertIsNotNone(record, name)
            self.assertEqual(record["mime"], mime)
            self.assertEqual(record["id"], media_id(data))
            self.assertEqual(record["size"], len(data))
            self.assertEqual(record["name"], name)
            self.assertEqual(sorted(record), ["id", "mime", "name", "size"])
            phone_uploads.publish_media(self.SESSION, [record["id"]])
            self.assertEqual(
                phone_uploads.original(self.SESSION, record["id"]).read_bytes(), data)
            phone_uploads.publish_media(self.SESSION, [])

    def test_misnamed_file_is_typed_by_bytes(self):
        record, _ = self.stage_file(JPEG, "actually.png")
        self.assertEqual(record["mime"], "image/jpeg")

    def test_ineligible_sources_stage_nothing(self):
        before = self.root.exists() and list(self.root.iterdir())
        self.assertIsNone(phone_uploads.stage_media(self.SESSION, Path(self.work.name) / "gone.png"))
        self.assertIsNone(phone_uploads.stage_media(self.SESSION, Path(self.work.name)))
        self.assertIsNone(self.stage_file(b"plain text, not raster")[0])
        self.assertIsNone(self.stage_file(b"")[0])
        with patch.object(phone_uploads, "MAX_UPLOAD_BYTES", 16):
            self.assertIsNone(self.stage_file(PNG)[0])
        self.assertIsNone(phone_uploads.stage_media("../escape", Path(self.work.name) / "a.png"))
        self.assertIsNone(phone_uploads.stage_media_bytes(self.SESSION, "not bytes"))
        self.assertIsNone(phone_uploads.stage_media_bytes(self.SESSION, b"\x00" * 8))
        after = self.root.exists() and list(self.root.iterdir())
        self.assertEqual(before, after)

    def test_staging_a_stored_upload_returns_its_existing_id(self):
        stream = tempfile.TemporaryFile()
        self.addCleanup(stream.close)
        stream.write(PNG)
        stream.flush()
        stream.seek(0)
        upload = phone_uploads.store(stream, len(PNG), session=self.SESSION,
                                     name="phone.png", mime="image/png")
        original = phone_uploads.original(self.SESSION, upload["id"])
        before = sorted(path.name for path in original.parent.iterdir())
        record = phone_uploads.stage_media(self.SESSION, original)
        self.assertEqual(record, upload)
        after = sorted(path.name for path in original.parent.iterdir())
        self.assertEqual(before, after)

    def test_restaging_same_bytes_is_id_free_and_copy_free(self):
        record, _ = self.stage_file(PNG)
        directory = phone_uploads.session_dir(self.SESSION)
        sidecar = directory / (record["id"] + ".meta.json")
        first_body = sidecar.read_bytes()
        first_mtime = sidecar.stat().st_mtime_ns
        again, _ = self.stage_file(PNG, "copy.png")
        self.assertEqual(again, record)
        self.assertEqual(sidecar.read_bytes(), first_body)
        self.assertEqual(sidecar.stat().st_mtime_ns, first_mtime)
        originals = [p for p in directory.iterdir() if p.name.startswith(record["id"])]
        self.assertEqual(len(originals), 2)  # original bytes + its sidecar

    def test_concurrent_captures_stage_same_image_without_losing_it(self):
        barrier = threading.Barrier(2)
        write_bytes = phone_uploads._write_bytes

        def simultaneous_write(data, partial):
            write_bytes(data, partial)
            barrier.wait(timeout=3)

        with patch.object(phone_uploads, "_write_bytes", simultaneous_write), ThreadPoolExecutor(2) as pool:
            jobs = [pool.submit(phone_uploads.stage_media_bytes, self.SESSION, PNG) for _ in range(2)]
            records = [job.result(timeout=5) for job in jobs]
        self.assertTrue(all(record is not None for record in records))
        phone_uploads.publish_media(self.SESSION, [record["id"] for record in records])
        for record in records:
            self.assertEqual(phone_uploads.original(self.SESSION, record["id"]).read_bytes(), PNG)

    def test_bytes_roundtrip(self):
        record = phone_uploads.stage_media_bytes(self.SESSION, WEBP, name="inline.webp")
        self.assertEqual(record["mime"], "image/webp")
        phone_uploads.publish_media(self.SESSION, [record["id"]])
        self.assertEqual(
            phone_uploads.original(self.SESSION, record["id"]).read_bytes(), WEBP)

    def test_media_is_served_only_while_published(self):
        record, _ = self.stage_file(PNG)
        upload_stream = tempfile.TemporaryFile()
        self.addCleanup(upload_stream.close)
        upload_stream.write(b"note body\n")
        upload_stream.flush()
        upload_stream.seek(0)
        upload = phone_uploads.store(upload_stream, 10, session=self.SESSION,
                                     name="notes.txt", mime="text/plain")
        # Not yet published: media unservable, upload unaffected.
        self.assertIsNone(phone_uploads.metadata(self.SESSION, record["id"]))
        self.assertIsNone(phone_uploads.original(self.SESSION, record["id"]))
        self.assertEqual(phone_uploads.metadata(self.SESSION, upload["id"]), upload)
        phone_uploads.publish_media(self.SESSION, [record["id"]])
        self.assertEqual(phone_uploads.metadata(self.SESSION, record["id"]), record)
        self.assertIsNotNone(phone_uploads.original(self.SESSION, record["id"]))
        # Republishing the same ids does not rewrite the manifest.
        manifest = phone_uploads.session_dir(self.SESSION) / ".visible-media.json"
        body = manifest.read_bytes()
        mtime = manifest.stat().st_mtime_ns
        phone_uploads.publish_media(self.SESSION, [record["id"], record["id"]])
        self.assertEqual(manifest.read_bytes(), body)
        self.assertEqual(manifest.stat().st_mtime_ns, mtime)
        # Unreferenced media becomes unservable again; uploads never do.
        phone_uploads.publish_media(self.SESSION, [])
        self.assertIsNone(phone_uploads.metadata(self.SESSION, record["id"]))
        self.assertEqual(phone_uploads.metadata(self.SESSION, upload["id"]), upload)
        # A corrupt manifest is a closed manifest.
        manifest.write_text("{not json", encoding="utf-8")
        phone_uploads.publish_media(self.SESSION, [record["id"]])
        manifest.write_text("{not json", encoding="utf-8")
        self.assertIsNone(phone_uploads.metadata(self.SESSION, record["id"]))

    def test_sidecar_cannot_escape_the_session_directory(self):
        record, _ = self.stage_file(PNG)
        directory = phone_uploads.session_dir(self.SESSION)
        phone_uploads.publish_media(self.SESSION, [record["id"]])
        sidecar = json.loads((directory / (record["id"] + ".meta.json")).read_text())
        sidecar["file"] = "../" + record["id"] + ".png"
        (directory / (record["id"] + ".meta.json")).write_text(json.dumps(sidecar))
        self.assertIsNone(phone_uploads.metadata(self.SESSION, record["id"]))
        self.assertIsNone(phone_uploads.original(self.SESSION, record["id"]))


class ConversationMediaChecks(unittest.TestCase):
    """read_conversation staging of native image shapes and Markdown links."""

    SESSION = "media-conversation-checks"

    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name) / "uploads"
        self.agent = Path(self.work.name) / "agent"
        self.root_patch = patch.object(phone_uploads, "UPLOAD_ROOT", self.root)
        self.env_patch = patch.dict(os.environ, {"PI_CODING_AGENT_DIR": str(self.agent)})
        self.root_patch.start()
        self.env_patch.start()
        self.addCleanup(self.env_patch.stop)
        self.addCleanup(self.root_patch.stop)

    def blob_ref(self, data: bytes) -> str:
        digest = hashlib.sha256(data).hexdigest()
        blobs = self.agent / "blobs"
        blobs.mkdir(parents=True, exist_ok=True)
        (blobs / digest).write_bytes(data)
        return "blob:sha256:" + digest

    def capture(self, records):
        header = {"type": "session", "version": 3, "id": self.SESSION,
                  "timestamp": "t", "cwd": str(self.work.name)}
        path = Path(self.work.name) / "session.jsonl"
        path.write_text("".join(json.dumps(record) + "\n"
                                for record in [header, *records]), encoding="utf-8")
        return conversation.read_conversation(path)

    def message(self, entry_id, parent, message):
        return {"type": "message", "id": entry_id, "parentId": parent,
                "timestamp": "t", "message": message}

    def inline_image(self, data: bytes, mime: str) -> dict:
        return {"type": "image", "data": base64.b64encode(data).decode(), "mimeType": mime}

    def attachments_of(self, payload, role):
        found = []
        for message in payload["messages"]:
            if message["role"] == role and "attachments" in message:
                found.extend(message["attachments"])
        return found

    def test_user_tool_result_and_details_images_are_staged(self):
        blobbed = self.blob_ref(JPEG)
        detail = self.blob_ref(WEBP)
        payload = self.capture([
            self.message("u1", None, {"role": "user", "content": [
                {"type": "text", "text": "look"}, self.inline_image(PNG, "image/png")]}),
            self.message("t1", "u1", {"role": "toolResult", "toolCallId": "c1",
                                      "toolName": "screenshot", "isError": False,
                                      "content": [{"type": "image", "data": blobbed,
                                                   "mimeType": "image/jpeg"}],
                                      "details": {"images": [
                                          {"type": "image", "data": detail,
                                           "mimeType": "image/webp"}]}}),
        ])
        user = self.attachments_of(payload, "user")
        tool = self.attachments_of(payload, "tool")
        self.assertEqual([item["id"] for item in user], [media_id(PNG)])
        self.assertEqual({item["id"] for item in tool}, {media_id(JPEG), media_id(WEBP)})
        self.assertEqual({item["mime"] for item in tool}, {"image/jpeg", "image/webp"})
        for item in user + tool:
            self.assertEqual(
                phone_uploads.original(self.SESSION, item["id"]).stat().st_size,
                item["size"])
        self.assertEqual(phone_uploads.original(
            self.SESSION, media_id(PNG)).read_bytes(), PNG)

    def test_filemention_image_payloads_and_upload_dedupe(self):
        stream = tempfile.TemporaryFile()
        self.addCleanup(stream.close)
        stream.write(GIF)
        stream.flush()
        stream.seek(0)
        upload = phone_uploads.store(stream, len(GIF), session=self.SESSION,
                                     name="phone.gif", mime="image/gif")
        upload_path = phone_uploads.original(self.SESSION, upload["id"])
        mention = phone_uploads.reference(upload_path)
        payload = self.capture([
            self.message("u1", None, {"role": "user", "content": "check this\n" + mention}),
            self.message("f1", "u1", {"role": "fileMention", "timestamp": 1, "files": [
                {"path": str(upload_path), "content": "",
                 "image": self.inline_image(GIF, "image/gif")}]}),
            self.message("f2", "f1", {"role": "fileMention", "timestamp": 2, "files": [
                {"path": "/nonexistent/remote.png", "content": "",
                 "image": self.inline_image(PNG, "image/png")}]}),
        ])
        # The stored upload is not duplicated as a second staged preview.
        self.assertEqual(self.attachments_of(payload, "user"),
                         [{"id": media_id(PNG), "name": "remote.png",
                           "mime": "image/png", "size": len(PNG)}])
        enriched = phone_uploads.enrich_conversation(payload)
        user_message = enriched["messages"][0]
        self.assertEqual(user_message["text"], "check this")
        self.assertEqual([item["id"] for item in user_message["attachments"]],
                         [upload["id"]])
        mentions = [message for message in enriched["messages"] if message["id"] == "f1"]
        self.assertEqual(mentions, [])

    def test_assistant_markdown_local_image_links_become_previews(self):
        spaced_dir = Path(self.work.name) / "shared pictures"
        spaced_dir.mkdir()
        (spaced_dir / "chat shot.png").write_bytes(PNG)
        (Path(self.work.name) / "plain.jpg").write_bytes(JPEG)
        (Path(self.work.name) / "notes.txt").write_bytes(b"not an image")
        file_url = "file://" + str(spaced_dir / "chat shot.png").replace(" ", "%20")
        text = "\n".join([
            "See ![the shot](<" + str(spaced_dir / "chat shot.png") + ">) now.",
            "And [the jpeg](plain.jpg) too.",
            "Also [via url](" + file_url + ").",
            "External stays [site](https://example.com/i.png).",
            "Doc stays [notes](notes.txt).",
            "Missing stays [gone](absent.png).",
            "```md",
            "![fenced](plain.jpg)",
            "```",
        ])
        payload = self.capture([
            self.message("a1", None, {"role": "assistant",
                                      "content": [{"type": "text", "text": text}]}),
        ])
        message = payload["messages"][0]
        lines = message["text"].split("\n")
        self.assertEqual(lines[0], "See the shot now.")
        self.assertEqual(lines[1], "And the jpeg too.")
        self.assertEqual(lines[2], "Also via url.")
        self.assertEqual(lines[3], "External stays [site](https://example.com/i.png).")
        self.assertEqual(lines[4], "Doc stays [notes](notes.txt).")
        self.assertEqual(lines[5], "Missing stays [gone](absent.png).")
        self.assertEqual(lines[7], "![fenced](plain.jpg)")
        self.assertEqual({item["id"] for item in message["attachments"]},
                         {media_id(PNG), media_id(JPEG)})
        self.assertEqual(message["attachments"][0]["name"], "chat shot.png")
        for item in message["attachments"]:
            self.assertIsNotNone(phone_uploads.original(self.SESSION, item["id"]))

    def test_long_and_mixed_code_fences_preserve_image_link_source(self):
        (Path(self.work.name) / "code.png").write_bytes(PNG)
        text = "````markdown\n```\n~~~\n![source](code.png)\n````\n![preview](code.png)"
        payload = self.capture([
            self.message("a1", None, {"role": "assistant", "content": [{"type": "text", "text": text}]}),
        ])
        self.assertEqual(payload["messages"][0]["text"],
                         "````markdown\n```\n~~~\n![source](code.png)\n````\npreview")
        self.assertEqual(len(payload["messages"][0]["attachments"]), 1)

    def test_abandoned_branch_media_is_never_servable(self):
        payload = self.capture([
            self.message("u1", None, {"role": "user", "content": "base"}),
            self.message("old", "u1", {"role": "assistant", "content": [
                {"type": "image", "data": base64.b64encode(JPEG).decode(),
                 "mimeType": "image/jpeg"}]}),
            self.message("u2", "u1", {"role": "user", "content": "rewound"}),
            self.message("new", "u2", {"role": "assistant", "content": [
                self.inline_image(PNG, "image/png")]}),
        ])
        self.assertIsNone(phone_uploads.metadata(self.SESSION, media_id(JPEG)))
        self.assertIsNone(phone_uploads.original(self.SESSION, media_id(JPEG)))
        self.assertEqual(self.attachments_of(payload, "assistant"),
                         [{"id": media_id(PNG), "name": "image",
                           "mime": "image/png", "size": len(PNG)}])
        self.assertIsNotNone(phone_uploads.original(self.SESSION, media_id(PNG)))

    def test_malformed_media_never_breaks_capture(self):
        payload = self.capture([
            self.message("u1", None, {"role": "user", "content": [
                {"type": "image", "data": "!!!not base64!!!", "mimeType": "image/png"},
                {"type": "image", "data": "blob:sha256:" + "0" * 64, "mimeType": "image/png"}]}),
            self.message("f1", "u1", {"role": "fileMention", "timestamp": 1,
                                      "files": [{"path": "/nope.png", "content": ""}]}),
            self.message("a1", "f1", {"role": "assistant",
                                      "content": [{"type": "text", "text": "still here"}]}),
        ])
        self.assertEqual(payload["messages"][-1]["text"], "still here")
        self.assertEqual(self.attachments_of(payload, "user"), [])

    def test_enrich_merges_with_staged_attachments(self):
        stream = tempfile.TemporaryFile()
        self.addCleanup(stream.close)
        stream.write(b"doc")
        stream.flush()
        stream.seek(0)
        upload = phone_uploads.store(stream, 3, session=self.SESSION,
                                     name="d.txt", mime="text/plain")
        mention = phone_uploads.reference(phone_uploads.original(self.SESSION, upload["id"]))
        staged = {"id": media_id(PNG), "name": "a.png", "mime": "image/png", "size": len(PNG)}
        payload = {"session": self.SESSION, "messages": [
            {"id": "u1", "role": "user", "text": "hi\n" + mention,
             "attachments": [staged]}]}
        enriched = phone_uploads.enrich_conversation(payload)
        self.assertEqual(enriched["messages"][0]["attachments"], [staged, upload])

    def test_offset_timestamps_are_normalized_and_missing_times_are_not_invented(self):
        timed = self.message("a1", None, {"role": "assistant", "content": [
            {"type": "text", "text": "reply"},
            {"type": "toolCall", "name": "read", "arguments": {"path": "example.txt"}}]})
        timed["timestamp"] = "2026-10-11T08:30:10+08:00"
        legacy = self.message("u2", "a1", {"role": "user", "content": "legacy message"})
        payload = self.capture([timed, legacy])
        self.assertEqual([m["timestamp"] for m in payload["messages"] if m["id"] == "a1"],
                         [1791678610000, 1791678610000])
        self.assertNotIn("timestamp", payload["messages"][-1])
        self.assertEqual(payload["messages"][-1]["text"], "legacy message")



if __name__ == "__main__":
    unittest.main()
