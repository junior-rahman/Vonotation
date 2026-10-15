#!/usr/bin/env python3
"""Package source and the exact signed preview; verify every entry and APK hash."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import stat
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


parser = argparse.ArgumentParser()
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("--apk", required=True, type=Path)
parser.add_argument("--stage", required=True)
parser.add_argument("--snapshot-in-progress-evidence", action="store_true",
                    help="Archive each current evidence file as a labelled partial snapshot while a run is active")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
gradle = (root / "app/build.gradle").read_text()
version_name = re.search(r"\bversionName\s+['\"]([^'\"]+)['\"]", gradle)
version_code = re.search(r"\bversionCode\s+(\d+)\b", gradle)
assert version_name and version_code, "Missing app version metadata in app/build.gradle"
apk_hash = sha(args.apk.read_bytes())
assert sha((root / "prebuilt/VoiceFlow.apk").read_bytes()) == apk_hash
excluded = {"build", ".gradle", ".git", "__pycache__", "preview-signing", ".idea", ".codex", ".agents"}
entries = {}
for path in sorted(root.rglob("*")):
    rel = path.relative_to(root)
    if not path.is_file() or any(part in excluded for part in rel.parts):
        continue
    if (path.is_symlink() or path.name in {"local.properties", ".DS_Store"}
            or path.name.startswith(".env")
            or path.suffix.lower() in {".keystore", ".jks", ".p12", ".pfx", ".pem", ".key", ".pyc"}):
        continue
    entries[rel.as_posix()] = (path, path.read_bytes(), path.stat().st_mode)
manifest = {
    "version": version_name.group(1), "version_code": int(version_code.group(1)), "stage": args.stage,
    "apk_sha256": apk_hash,
    "test_limits": "See VALIDATION-" + version_name.group(1) + ".md and VALIDATION.md. Historical results retain their original version.",
    "evidence_consistency": ("Per-file snapshot of an active run; partial logs/results are not final passes"
                             if args.snapshot_in_progress_evidence else "Stable complete-file snapshot"),
    "sha256": {name: sha(data) for name, (_, data, _) in entries.items()},
}
args.output.parent.mkdir(parents=True, exist_ok=True)
temporary = args.output.with_suffix(".zip.tmp")
with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for name, (path, data, mode) in entries.items():
        if not (args.snapshot_in_progress_evidence and name.startswith("evidence/")):
            assert sha(path.read_bytes()) == sha(data), f"Concurrent source change: {path}"
        info = zipfile.ZipInfo("VoiceFlow/" + name)
        info.external_attr = (stat.S_IFREG | stat.S_IMODE(mode)) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, data)
    z.writestr("CHECKPOINT-MANIFEST.json", json.dumps(manifest, indent=2))
with zipfile.ZipFile(temporary) as z:
    assert z.testzip() is None
    for name, digest in manifest["sha256"].items():
        assert sha(z.read("VoiceFlow/" + name)) == digest
    assert sha(z.read("VoiceFlow/prebuilt/VoiceFlow.apk")) == apk_hash
temporary.replace(args.output)
print(json.dumps({"path": str(args.output.resolve()), "bytes": args.output.stat().st_size,
                  "sha256": sha(args.output.read_bytes()), "source_files": len(entries),
                  "apk_sha256": apk_hash, "crc_and_entry_hashes": "PASS"}))
