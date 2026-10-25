#!/usr/bin/env python3
"""Publish verified Simulator previews without rebuilding or device signing."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import zipfile
from publish_voitation import api, command, REPO, SOURCE, MERGE, TREE

RUN = 40154153323
TAG = "split-ios-v0.1.18-preview"
MARKER = "<!-- split-ios-verified-preview:" + SOURCE + " -->"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if os.environ.get("GITHUB_REPOSITORY") != REPO or os.environ.get("GITHUB_REF") != "refs/heads/release/split-verified-previews":
        raise RuntimeError("Unexpected publication repository or branch")
    run = api("/actions/runs/" + str(RUN))
    if (run["head_sha"], run["status"], run["conclusion"]) != (SOURCE, "completed", "success"):
        raise RuntimeError("Exact source did not pass native iOS verification")
    for commit in [SOURCE, MERGE]:
        if api("/git/commits/" + commit)["tree"]["sha"] != TREE:
            raise RuntimeError("Source and native-tested tree mismatch")
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        packages, evidence, output = [root / name for name in ["packages", "evidence", "output"]]
        output.mkdir()
        for destination, name in [(packages, "VonoTation-iOS-Simulator-" + MERGE), (evidence, "VonoTation-iOS-native-evidence-" + MERGE)]:
            command("gh", "run", "download", str(RUN), "--repo", REPO, "--name", name, "--dir", str(destination))
        for relative, public_name, expected_count, bundle in [("", "Voitation", 46, "app.vonotation.ios"), ("notes", "Vocading", 6, "app.vonotation.notes.ios")]:
            product = packages / relative
            receipt = json.loads((product / "VERIFICATION.json").read_text())
            if receipt["commit"] != MERGE or receipt["bundle_identifier"] != bundle or any(v != "passed" for v in receipt["checks"].values()):
                raise RuntimeError("Native iOS receipt is incomplete")
            if receipt["device_sdk_build_results"]["status"] != "succeeded" or receipt["device_sdk_build_results"]["errorCount"]:
                raise RuntimeError("Device SDK compilation failed")
            for device in ["iPhone", "iPad"]:
                result = receipt["native_test_results"][device]
                if result["counts"] != {"totalTestCount": expected_count, "passedTests": expected_count, "failedTests": 0, "skippedTests": 0}:
                    raise RuntimeError("Unexpected native test result")
                for kind in ["summary", "tree"]:
                    if digest(evidence / relative / (device + "-test-" + kind + ".json")) != result[kind + "_sha256"]:
                        raise RuntimeError("Test evidence differs from receipt")
            for field, name in [("artifact", public_name + "-iOS-Simulator.zip"), ("source_archive", public_name + "-iOS-source.zip")]:
                entry = receipt[field]
                artifact = product / entry["name"]
                if artifact.stat().st_size != entry["size"] or digest(artifact) != entry["sha256"]:
                    raise RuntimeError("Artifact differs from tested receipt")
                with zipfile.ZipFile(artifact) as archive:
                    if archive.testzip() is not None or any(n.lower().endswith((".keystore", ".jks", ".p12", ".pfx", ".mobileprovision")) for n in archive.namelist()):
                        raise RuntimeError("Invalid or private archive content")
                shutil.copyfile(artifact, output / name)
            shutil.copyfile(product / "VERIFICATION.json", output / (public_name + "-VERIFICATION.json"))
        shutil.copyfile(packages / "PRODUCT-ISOLATION.json", output / "PRODUCT-ISOLATION.json")
        images = sorted(evidence.rglob("*.png"))
        if len(images) != 32:
            raise RuntimeError("Expected 32 native iPhone/iPad screenshots")
        with zipfile.ZipFile(output / "iOS-native-screenshots.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            for picture in images:
                archive.write(picture, picture.relative_to(evidence))
        notes = """# Voitation 0.1.18 and Vocading 0.1.0 — iOS Simulator previews

These ZIPs run in **iOS Simulator on a Mac with Xcode**. They are **not installable iPhone/iPad IPAs**. Physical-device installation requires owner Apple signing and provisioning; no App Store or TestFlight release is included.

Voitation provides Dictation, Quick Thoughts and Clipboard with the approved Sand & Cocoa colors. Vocading separately provides Voice Notes and imported Call Recordings, using the original Daybook palette. Existing bundle IDs and data identities remain stable. iOS cannot provide the Android cross-app floating dictation overlay or intercept other apps' calls. Clipboard capture uses the native Paste action. Backup/OAuth stays paused.

Exact source: `7d2c26aa8ce8d72b370138f5b1632093754e29c9`. [Native verification run](https://github.com/junior-rahman/Vonotation/actions/runs/40154153323) passed 46 Voitation tests and 6 Vocading tests on each of iPhone SE (3rd generation) and iPad mini (A17 Pro): **104 passing executions, zero failures or skips**. Both unsigned iPhoneOS SDK builds and both Simulator archives succeeded. All 32 native light/dark screenshots were reviewed. One existing microphone-permission deprecation warning remains per product.

Open the source's `VonoTation.xcodeproj`. Select the `VonoTation` scheme for Voitation or `VonoNotes` for Vocading, choose a Simulator and run. The two source ZIPs contain the same shared source with both schemes. Alternatively, unzip the Simulator package and install its `.app` in the appropriate Simulator. The public download names are updated; original receipt filenames and internal Xcode names remain for traceability.

Physical hardware, real microphone quality/background recording, live provider credentials, Siri discovery, Apple signing/provisioning and App Store/TestFlight distribution remain unverified. See the attached per-product verification receipts and screenshot archive.
"""
        (output / "RELEASE-NOTES.md").write_text(notes)
        (output / "SHA256SUMS").write_text("".join(digest(path) + "  " + path.name + "\n" for path in sorted(output.iterdir())))
        files = {path.name: path for path in output.iterdir()}
        release = api("/releases/tags/" + TAG, optional=True)
        if release is None:
            release = api("/releases", "POST", {"tag_name": TAG, "target_commitish": SOURCE, "name": "Voitation and Vocading — iOS Simulator previews", "body": notes + "\n" + MARKER, "draft": True, "prerelease": True, "make_latest": "false"})
        elif MARKER not in release.get("body", "") or release.get("target_commitish") != SOURCE:
            raise RuntimeError("Existing release does not belong to this candidate")
        existing = {a["name"]: a for a in release["assets"]}
        if set(existing) - set(files):
            raise RuntimeError("Unexpected release asset")
        for name, path in files.items():
            if name in existing:
                if existing[name].get("digest") != "sha256:" + digest(path):
                    raise RuntimeError("Refusing to replace a mismatched asset")
            else:
                if not release["draft"]:
                    raise RuntimeError("Refusing to alter published release")
                command("gh", "release", "upload", TAG, str(path), "--repo", REPO)
        verified = api("/releases/" + str(release["id"]))
        assets = {a["name"]: a for a in verified["assets"]}
        if set(assets) != set(files):
            raise RuntimeError("Incomplete uploaded asset set")
        for name, path in files.items():
            if assets[name]["size"] != path.stat().st_size or assets[name].get("digest") != "sha256:" + digest(path):
                raise RuntimeError("Uploaded bytes differ from verified files")
        released = api("/releases/" + str(release["id"]), "PATCH", {"draft": False, "prerelease": True, "make_latest": "false"})
        print(released["html_url"])


if __name__ == "__main__":
    main()
