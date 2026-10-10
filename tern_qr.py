#!/usr/bin/env python3
"""Local QR generator for the Tern Companion Android app.

Encodes a bridge connection (name, address, port, token [, Cloudflare Access
credentials]) as the compact JSON payload the app expects, then renders it into
a plain black/white PNG QR code on this machine. Nothing is sent anywhere: the
bridge token and any Access secrets stay on the host, and never appear in
stdout, logs or filenames.

Run:  python3 tern_qr.py
      python3 tern_qr.py --name "My PC" --address 192.168.1.10 --port 9260
      python3 tern_qr.py --access-creds ~/.tern/access.json \\
                         --address https://tern.example.com

The payload contract (mirrored from the app's Host.java validation):
    {"app":"tern-companion","version":1,"name":...,"address":...,"port":...,
     "token":<64 lowercase hex>[, "accessId":..., "accessSecret":...]}
"""

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

APP_ID = "tern-companion"
APP_VERSION = 1
DEFAULT_PORT = 9260
TOKEN_FILE = Path.home() / ".tern" / "bridge-token"
DEFAULT_OUTPUT = Path.home() / "Desktop" / "Tern-Companion-QR.png"
ISOLATED_PYTHON = Path.home() / ".local" / "share" / "tern-qr" / "venv" / "bin" / "python"
TOKEN_RE = re.compile(r"^[0-9a-f]{64}$")
MAX_ACCESS_LEN = 256

_ENCODER_PROGRAM = r"""
import sys
import qrcode
data = sys.stdin.read()
img = qrcode.make(
    data,
    error_correction=qrcode.constants.ERROR_CORRECT_Q,
    box_size=12,
    border=4,
)
img.save(sys.argv[1])
"""


def _is_private_host(host: str) -> bool:
    """Mirror of Host.isPrivateHost: hosts that may be reached over plain HTTP."""
    if host == "localhost" or host.endswith(".ts.net"):
        return True
    octets = host.split(".")
    if len(octets) != 4:
        return False
    numbers = []
    for octet in octets:
        if not re.fullmatch(r"[0-9]{1,3}", octet):
            return False
        value = int(octet)
        if value > 255:
            return False
        numbers.append(value)
    a, b = numbers[0], numbers[1]
    return (
        a == 10
        or a == 127
        or (a == 192 and b == 168)
        or (a == 172 and 16 <= b <= 31)
        or (a == 100 and 64 <= b <= 127)
    )


def build_payload(name: str, address: str, port: int, token: str,
                  access_id: str = "", access_secret: str = "") -> dict:
    """Assemble the contract payload; validation happens in validate_payload."""
    payload = {
        "app": APP_ID,
        "version": APP_VERSION,
        "name": name.strip(),
        "address": address.strip(),
        "port": port,
        "token": token.strip(),
    }
    access_id = access_id.strip()
    access_secret = access_secret.strip()
    if access_id:
        payload["accessId"] = access_id
    if access_secret:
        payload["accessSecret"] = access_secret
    return payload


def validate_payload(payload: dict) -> dict:
    """Validate and normalize a payload against the app's Host.java rules.

    Returns the payload with a normalized address; raises ValueError otherwise.
    """
    name = str(payload.get("name", "")).strip()
    if not name or len(name) > 80 or any(ord(c) < 32 or ord(c) == 127 for c in name):
        raise ValueError("name must be a single line of 1-80 characters")
    token = str(payload.get("token", "")).strip()
    if not TOKEN_RE.fullmatch(token):
        raise ValueError("token must be exactly 64 lowercase hex characters")
    try:
        port = int(payload.get("port"))
    except (TypeError, ValueError):
        raise ValueError("port must be an integer")
    if not 1 <= port <= 65535:
        raise ValueError("port must be 1-65535")

    value = str(payload.get("address", "")).strip()
    if not value:
        raise ValueError("address is required")
    if "://" not in value:
        value = "http://" + value
    parts = urlsplit(value)
    scheme = (parts.scheme or "").lower()
    host = (parts.hostname or "").lower()
    if scheme not in ("http", "https"):
        raise ValueError("address scheme must be http or https")
    if not host:
        raise ValueError("address must include a host")
    if parts.port is not None:
        raise ValueError("address must not include a port (use --port)")
    if parts.path not in ("", "/") or parts.query or parts.fragment or parts.username or parts.password:
        raise ValueError("address must not include a path, query or credentials")

    access_id = str(payload.get("accessId", "")).strip()
    access_secret = str(payload.get("accessSecret", "")).strip()
    if bool(access_id) != bool(access_secret):
        raise ValueError("provide both Cloudflare Access credentials or neither")
    for label, cred in (("accessId", access_id), ("accessSecret", access_secret)):
        if "\n" in cred or "\r" in cred or len(cred) > MAX_ACCESS_LEN:
            raise ValueError(f"{label} must be a single line of at most {MAX_ACCESS_LEN} characters")

    private = _is_private_host(host)
    if not private and scheme != "https":
        raise ValueError("public hosts require https")
    if not private and not access_id:
        raise ValueError("public HTTPS hosts require Cloudflare Access credentials")
    if access_id and scheme != "https":
        raise ValueError("Cloudflare Access credentials require https")

    normalized = dict(payload)
    normalized["name"] = name
    normalized["address"] = f"{scheme}://{host}"
    normalized["port"] = port
    normalized["token"] = token
    if access_id:
        normalized["accessId"] = access_id
        normalized["accessSecret"] = access_secret
    else:
        normalized.pop("accessId", None)
        normalized.pop("accessSecret", None)
    return normalized


def load_token(path: Path = TOKEN_FILE) -> str:
    """Read the existing bridge token; never creates or rotates it."""
    if not path.exists():
        raise SystemExit(f"no bridge token at {path}; start tern_bridge.py first")
    token = path.read_text().strip()
    if not TOKEN_RE.fullmatch(token):
        raise SystemExit(f"token at {path} is not 64 lowercase hex characters")
    return token


def tailscale_ip() -> str | None:
    """First Tailscale IPv4 address for this host, if Tailscale is available."""
    candidates = [
        ["tailscale", "ip", "-4"],
        ["/Applications/Tailscale.app/Contents/MacOS/Tailscale", "ip", "-4"],
    ]
    for command in candidates:
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=5)
        except (OSError, subprocess.SubprocessError):
            continue
        if result.returncode == 0:
            for line in result.stdout.splitlines():
                if IPV4_RE.fullmatch(line.strip()):
                    return line.strip()
    return None


def read_access_creds(path: Path) -> tuple[str, str]:
    """Read Access credentials from an existing JSON or KEY=VALUE secret store."""
    if not path.exists():
        raise SystemExit(f"access credentials file not found: {path}")
    text = path.read_text()
    values: dict[str, str] = {}
    stripped = text.lstrip()
    if path.suffix.lower() == ".json" or stripped.startswith("{"):
        try:
            data = json.loads(text)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"invalid JSON in {path}: {exc}")
        if not isinstance(data, dict):
            raise SystemExit(f"expected a JSON object in {path}")
        values = {str(k): str(v) for k, v in data.items()}
    else:
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip().strip('"').strip("'")
    access_id = values.get("accessId") or values.get("accessClientId") or ""
    access_secret = values.get("accessSecret") or values.get("accessClientSecret") or ""
    if not access_id or not access_secret:
        raise SystemExit(
            f"{path} must provide accessId/accessClientId and accessSecret/accessClientSecret"
        )
    return access_id, access_secret


def _qrencode_cli() -> str | None:
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        candidate = Path(directory) / "qrencode"
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


def _python_encoder() -> str | None:
    """Interpreter that can `import qrcode`, preferring the isolated env."""
    candidates: list[str] = []
    override = os.environ.get("TERN_QR_PYTHON")
    if override:
        candidates.append(override)
    candidates.append(str(ISOLATED_PYTHON))
    candidates.append(sys.executable)
    for candidate in candidates:
        try:
            probe = subprocess.run(
                [candidate, "-c", "import qrcode"],
                capture_output=True, text=True, timeout=30,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        if probe.returncode == 0:
            return candidate
    return None


def encode_png(payload_text: str, destination: Path) -> str:
    """Render payload_text into destination (PNG). Returns the encoder used."""
    cli = _qrencode_cli()
    if cli:
        # No payload on argv: qrencode reads the string from stdin.
        result = subprocess.run(
            [cli, "-o", str(destination), "-t", "PNG", "-l", "Q", "-m", "4", "-s", "12"],
            input=payload_text, capture_output=True, text=True, timeout=30,
        )
        if result.returncode != 0:
            raise SystemExit(f"qrencode failed: {result.stderr.strip()}")
        return f"qrencode ({cli})"

    python = _python_encoder()
    if not python:
        raise SystemExit(
            "no QR encoder available: install qrencode, or set TERN_QR_PYTHON to a "
            f"python with the qrcode package (looked at {ISOLATED_PYTHON})"
        )
    result = subprocess.run(
        [python, "-c", _ENCODER_PROGRAM, str(destination)],
        input=payload_text, capture_output=True, text=True, timeout=60,
    )
    if result.returncode != 0:
        raise SystemExit(f"python qrcode encoder failed: {result.stderr.strip()}")
    return f"python qrcode ({python})"


def _versioned(path: Path) -> Path:
    """Never clobber an existing artifact; fall back to a versioned sibling."""
    if not path.exists():
        return path
    for number in range(2, 1000):
        candidate = path.with_name(f"{path.stem}-{number}{path.suffix}")
        if not candidate.exists():
            return candidate
    raise SystemExit(f"too many existing files beside {path}")


def _write_artifact(destination: Path, payload_text: str) -> tuple[Path, str]:
    """Atomically create a 0600 artifact, refusing to overwrite anything."""
    destination = _versioned(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=".tern-qr-", suffix=destination.suffix,
                                     dir=destination.parent)
    os.close(fd)
    temp = Path(temp_name)
    try:
        encoder = encode_png(payload_text, temp)
        os.chmod(temp, 0o600)
        os.link(temp, destination)
    finally:
        try:
            temp.unlink()
        except OSError:
            pass
    return destination, encoder


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--name", default=socket.gethostname(),
                        help="display name for this PC (default: hostname)")
    parser.add_argument("--address", default=None,
                        help="private host or https:// Access host, no port/path "
                             "(default: this host's Tailscale IPv4)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help=f"bridge port (default: {DEFAULT_PORT})")
    parser.add_argument("--token-file", type=Path, default=TOKEN_FILE,
                        help=f"existing bridge token file (default: {TOKEN_FILE})")
    parser.add_argument("--access-creds", type=Path, default=None,
                        help="existing JSON or KEY=VALUE file with Cloudflare Access "
                             "credentials (enables the HTTPS route)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT,
                        help=f"QR PNG path (default: {DEFAULT_OUTPUT})")
    opts = parser.parse_args()

    address = opts.address
    if not address:
        address = tailscale_ip()
        if not address:
            raise SystemExit("Tailscale IPv4 not found; pass --address explicitly")

    access_id = access_secret = ""
    if opts.access_creds is not None:
        access_id, access_secret = read_access_creds(opts.access_creds)

    payload = validate_payload(build_payload(
        name=opts.name,
        address=address,
        port=opts.port,
        token=load_token(opts.token_file),
        access_id=access_id,
        access_secret=access_secret,
    ))
    payload_text = json.dumps(payload, separators=(",", ":"), ensure_ascii=True)

    destination, encoder = _write_artifact(opts.output, payload_text)

    scheme_host = payload["address"].split("://", 1)
    print(f"name: {payload['name']}")
    print(f"address: {scheme_host[0]}://{scheme_host[1]} port {payload['port']}")
    print(f"access: {'yes' if payload.get('accessId') else 'no'}   "
          f"token: {len(payload['token'])} hex chars")
    print(f"encoder: {encoder}")
    print(f"output: {destination} (mode 600)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
