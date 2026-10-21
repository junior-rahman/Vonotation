#!/usr/bin/env python3
"""Called only after all native CI steps succeed; records exact source and artifact hashes."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1]).resolve()
scheme = os.environ.get("VONO_SCHEME", "VonoTation")
version, build = ("0.1.0", 1) if scheme == "VonoNotes" else ("0.1.18", 90)
simulator_zip = out / f"{scheme}-iOS-Simulator.zip"
required = [out / "iPhone.xcresult", out / "iPad.xcresult", out / "logs/device-sdk-build.log", out / "logs/simulator-archive.log", simulator_zip]
for path in required:
    if not path.exists():
        raise SystemExit(f"Required native verification output missing: {path}")
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
source_paths = [p for p in sorted(root.rglob("*")) if p.is_file() and not {"build", "__pycache__", "xcuserdata", "DerivedData"}.intersection(p.relative_to(root).parts) and not any(part.startswith(".") and part != ".gitignore" for part in p.relative_to(root).parts) and p.suffix not in {".pyc", ".ipa", ".p12", ".mobileprovision"}]
sources = {str(p.relative_to(root)): sha(p) for p in source_paths}
test_summaries = {}
expected_tests = sum(len(re.findall(r"func\s+test\w+\s*\(", p.read_text())) for folder in (scheme + "Tests", scheme + "UITests") for p in (root / folder).rglob("*.swift"))
for family in ("iPhone", "iPad"):
    summary = json.loads((out / f"{family}-test-summary.json").read_text())
    counts = {key: summary.get(key) for key in ("totalTestCount", "passedTests", "failedTests", "skippedTests")}
    if any(not isinstance(value, int) for value in counts.values()):
        raise SystemExit(f"Unrecognized xcresult test count schema for {family}: {counts}")
    if counts["failedTests"] != 0 or counts["skippedTests"] != 0 or counts["passedTests"] != expected_tests or counts["totalTestCount"] != expected_tests:
        raise SystemExit(f"Native tests incomplete for {family}: expected {expected_tests}, actual {counts}")
    test_summaries[family] = {"counts": counts, "summary_sha256": sha(out / f"{family}-test-summary.json"), "tree_sha256": sha(out / f"{family}-test-tree.json")}
source_zip = out / f"{scheme}-iOS-{version}-source.zip"
intent_report_path = out / "intent-metadata-validation.json"
intent_report = json.loads(intent_report_path.read_text())
if not intent_report.get("checks") or not all(intent_report["checks"].values()):
    raise SystemExit("Generated App Intent metadata validation is incomplete")
with zipfile.ZipFile(source_zip, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
    for path in source_paths:
        bundle.write(path, "VonoTation-iOS/" + path.relative_to(root).as_posix())
manifest = {
    "product": scheme, "bundle_identifier": "app.vonotation.notes.ios" if scheme == "VonoNotes" else "app.vonotation.ios",
    "version": version, "build": build, "commit": os.environ.get("GITHUB_SHA", "local"),
    "xcode": (out / "xcode-version.txt").read_text().strip(),
    "simulators": json.loads((out / "simulators.json").read_text()),
    "native_test_results": test_summaries,
    "source_payload_sha256": hashlib.sha256(json.dumps(sources, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
    "device_sdk_build_results": json.loads((out / "device-sdk-build.json").read_text()),
    "device_sdk_build_log_sha256": sha(out / "logs/device-sdk-build.log"),
    "intent_metadata_validation": {"sha256": sha(intent_report_path), "checks": intent_report["checks"]},
    "checks": {"iphone_xctest_xcuitest": "passed", "ipad_xctest_xcuitest": "passed", "iphoneos_sdk_compile_unsigned": "passed", "simulator_archive": "passed"},
    "not_tested": ["Physical iPhone/iPad hardware", "Real microphone quality and long background recording on hardware", "User Apple Account signing and provisioning", "Live paid transcription provider credentials", "App Store/TestFlight distribution"],
    "artifact": {"name": simulator_zip.name, "size": simulator_zip.stat().st_size, "sha256": sha(simulator_zip), "install_target": "iOS Simulator on a Mac; not an installable iPhone IPA"},
    "source_archive": {"name": source_zip.name, "size": source_zip.stat().st_size, "sha256": sha(source_zip)},
    "sources_sha256": sources,
}
(out / "VERIFICATION.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps({"artifact": manifest["artifact"], "checks": manifest["checks"]}, indent=2))
