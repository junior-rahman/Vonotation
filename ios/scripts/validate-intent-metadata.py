#!/usr/bin/env python3
"""Inspect the actual Apple-generated metadata, not the Swift source declarations."""
import hashlib
import json
from pathlib import Path
import plistlib
import shutil
import sys

application = Path(sys.argv[1])
output = Path(sys.argv[2])
metadata = application / "Metadata.appintents"
if not metadata.is_dir():
    raise SystemExit("The built application has no Metadata.appintents directory")
destination = output / "intent-metadata"
shutil.copytree(metadata, destination, dirs_exist_ok=True)
documents = {}
readable = []
for path in sorted(metadata.rglob("*")):
    if not path.is_file():
        continue
    data = path.read_bytes()
    relative = path.relative_to(metadata).as_posix()
    documents[relative] = {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    try:
        parsed = json.loads(data)
        readable.append(json.dumps(parsed, ensure_ascii=False))
        documents[relative]["format"] = "json"
    except (ValueError, UnicodeError):
        try:
            parsed = plistlib.loads(data)
            readable.append(str(parsed))
            documents[relative]["format"] = "plist"
        except (ValueError, TypeError, plistlib.InvalidFileException):
            try:
                readable.append(data.decode("utf-8"))
                documents[relative]["format"] = "utf8"
            except UnicodeError:
                documents[relative]["format"] = "binary"
text = "\n".join(readable)
is_notes = application.name == "VonoNotes.app"
checks = {
    "record_intent": ("RecordVoiceNoteIntent" if is_notes else "RecordQuickThoughtIntent") in text,
    "finish_intent": "FinishVonoRecordingIntent" in text,
    "record_shortcut_phrase": ("Record a voice note with" if is_notes else "Record a thought with") in text,
    "other_product_record_intent_absent": ("RecordQuickThoughtIntent" if is_notes else "RecordVoiceNoteIntent") not in text,
    "finish_shortcut_phrase": "Finish recording in" in text,
}
report = {"bundle": application.name, "checks": checks, "files": documents,
          "scope": "Generated metadata presence; Siri voice recognition and physical-device discovery are not tested."}
(output / "intent-metadata-validation.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
if not documents or not all(checks.values()):
    raise SystemExit("The generated metadata does not contain both recording intents and shortcut phrases; inspect intent-metadata evidence")
