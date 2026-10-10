"""Pairing trust boundaries and private, non-overwriting QR artifacts."""
import json
import stat
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tern_qr


class PairingGeneratorCheck(unittest.TestCase):
    def payload(self, **changes):
        value = tern_qr.build_payload("Test PC", "192.168.1.10", 9260, "0" * 64)
        value.update(changes)
        return value

    def test_private_and_funnel_origin_normalization(self):
        for address, port, expected in (
            ("192.168.1.10", 9260, "http://192.168.1.10"),
            ("100.64.0.42", 9260, "http://100.64.0.42"),
            ("https://PC.EXAMPLE.TS.NET/", 8443, "https://pc.example.ts.net"),
        ):
            with self.subTest(address=address):
                result = tern_qr.validate_payload(self.payload(address=address, port=port))
                self.assertEqual(result["address"], expected)
                self.assertEqual(result["port"], port)
                self.assertNotIn("accessSecret", result)

    def test_public_https_requires_both_access_credentials(self):
        result = tern_qr.validate_payload(self.payload(address="https://tern.example.com",
            port=443, accessId="test-id", accessSecret="test-secret"))
        self.assertEqual(result["address"], "https://tern.example.com")
        for changes in ({}, {"accessId": "test-id"}, {"accessSecret": "test-secret"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                tern_qr.validate_payload(self.payload(address="https://tern.example.com", **changes))

    def test_untrusted_origins_rejected(self):
        for address in ("8.8.8.8", "172.15.0.1", "100.63.255.255", "192.168.999.1",
                        "http://tern.example.com", "ftp://192.168.1.10", "",
                        "http://192.168.1.10:9260", "http://192.168.1.10/path",
                        "http://192.168.1.10?token=x", "http://user:pass@192.168.1.10",
                        "http://192.168.1.10#fragment"):
            with self.subTest(address=address), self.assertRaises(ValueError):
                tern_qr.validate_payload(self.payload(address=address))

    def test_ports_and_tokens_rejected(self):
        for port in (-1, 0, 65536, "invalid", None):
            with self.subTest(port=port), self.assertRaises(ValueError):
                tern_qr.validate_payload(self.payload(port=port))
        for token in ("", "a" * 63, "a" * 65, "g" * 64, "A" * 64):
            with self.subTest(length=len(token)), self.assertRaises(ValueError) as error:
                tern_qr.validate_payload(self.payload(token=token))
            if token:
                self.assertNotIn(token, str(error.exception))

    def test_control_characters_and_http_access_rejected(self):
        for changes in ({"name": ""}, {"name": "x" * 81}, {"name": "PC\nspoof"},
                        {"accessId": "test-id", "accessSecret": "test-secret"},
                        {"address": "https://pc.example.ts.net", "accessId": "id\r\nheader",
                         "accessSecret": "test-secret"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                tern_qr.validate_payload(self.payload(**changes))

    def test_existing_artifact_preserved_and_new_png_private(self):
        payload = tern_qr.validate_payload(self.payload())
        with tempfile.TemporaryDirectory(prefix="tern-qr-check-") as folder:
            original = Path(folder) / "pairing.png"
            original.write_bytes(b"existing pairing artifact")
            created, _ = tern_qr._write_artifact(original, json.dumps(payload))
            self.assertEqual(original.read_bytes(), b"existing pairing artifact")
            self.assertEqual(created.name, "pairing-2.png")
            self.assertEqual(created.read_bytes()[:8], b"\x89PNG\r\n\x1a\n")
            self.assertEqual(stat.S_IMODE(created.stat().st_mode), 0o600)


if __name__ == "__main__":
    unittest.main(verbosity=2)
