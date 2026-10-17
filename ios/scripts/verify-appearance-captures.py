#!/usr/bin/env python3
"""Record real Simulator appearance and screenshot bytes for visual review."""
import hashlib
import json
from pathlib import Path
import struct
import sys

output = Path(sys.argv[1])
captures = []
for family in ("iPhone", "iPad"):
    for appearance, directory in (("light", "screenshots"), ("dark", "screenshots-dark")):
        observed = (output / f"{family}-{appearance}-appearance.txt").read_text().strip().lower()
        if observed != appearance:
            raise SystemExit(f"Expected {appearance} appearance for {family}, observed {observed!r}")
        for screen in ("home", "dictations", "thoughts", "clipboard", "settings"):
            path = output / directory / f"{family}-{screen}.png"
            data = path.read_bytes()
            if not data.startswith(b"\x89PNG\r\n\x1a\n") or data[12:16] != b"IHDR":
                raise SystemExit(f"Expected native PNG: {path}")
            width, height = struct.unpack(">II", data[16:24])
            if min(width, height) < 200:
                raise SystemExit(f"Invalid native screenshot dimensions: {path}")
            captures.append({"device": family, "appearance": appearance, "screen": screen,
                             "path": path.relative_to(output).as_posix(), "size": len(data),
                             "width": width, "height": height, "sha256": hashlib.sha256(data).hexdigest()})
(output / "appearance-captures.json").write_text(json.dumps({"captures": captures,
    "note": "Native Simulator captures with isolated synthetic fixtures; visual review is separate."}, indent=2) + "\n")
print(f"Recorded {len(captures)} native light/dark captures for visual review.")
