#!/usr/bin/env python3
"""Export an unsigned, payload-equivalent APK from a disposable QA-signed build.

The output is deliberately not installable and is never an in-app update. Its
DEX/resources are verified byte-for-byte so a real signing owner may sign it.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


def signature_file(name):
    return name.upper() == "META-INF/MANIFEST.MF" or bool(
        re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, re.IGNORECASE)
    )


def payload(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entries")
        return {name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in names if not signature_file(name)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--zipalign", required=True, type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    expected = payload(args.source)
    with tempfile.TemporaryDirectory(prefix="vonotation-unsigned-") as directory:
        unsigned = Path(directory) / "payload.apk"
        with zipfile.ZipFile(args.source) as source, zipfile.ZipFile(unsigned, "w") as target:
            for entry in source.infolist():
                if not signature_file(entry.filename):
                    target.writestr(entry, source.read(entry.filename))
        subprocess.run([str(args.zipalign), "-f", "-p", "4", str(unsigned), str(args.output)], check=True)
    if expected != payload(args.output):
        args.output.unlink(missing_ok=True)
        raise ValueError("Unsigned output does not match verified application payload")
    data = args.output.read_bytes()
    args.output.with_suffix(".json").write_text(json.dumps({
        "artifact": args.output.name, "qualification": "UNSIGNED; not installable or an update",
        "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
        "payload_entries": expected,
    }, indent=2) + "\n")
    print(args.output)


if __name__ == "__main__":
    main()
