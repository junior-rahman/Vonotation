#!/usr/bin/env python3
"""Publish only the already signed, native-verified Vocading preview."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

REPO = "junior-rahman/Vonotation"
SOURCE = "aa09ee75fd127b7c64adfcd0cd7c7a82d8c8aa78"
TREE = "beef217168b53749f3f4ec7b93fe02c9021d5838"
MERGE = "7051f0df5ee530e10ef3affc5d5c1f1c4614d1e8"
RUN = 40164942633
TAG = "vocading-v0.1.2-preview"
APK_SHA = "5cb55e5ad97df92181767301c20843cdc8fdca683debe2f68291b45e6d8f7559"
SIGNER = "b92734798f7d2642f336290beada2497ccc65c1f253511d9893499091777d317"
ROOT = Path(__file__).resolve().parent / "vocading-0.1.2-preview"
FILES = ["Vocading-0.1.2-preview.apk", "Vocading-APK-manifest.json", "Vocading-APK-signature.txt", "Vocading-0.1.2-Android-source.zip", "Android-VERIFICATION.json", "Android-native-screenshots.zip", "RELEASE-NOTES.md", "SHA256SUMS"]
MARKER = "<!-- vocading-verified-preview:aa09ee75fd127b7c64adfcd0cd7c7a82d8c8aa78 -->"

def command(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout

def api(path, method="GET", data=None, optional=False):
    args = ["gh", "api", "repos/" + REPO + path, "--method", method]
    if data is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=None if data is None else json.dumps(data), capture_output=True, text=True)
    if optional and result.returncode and "HTTP 404" in result.stderr:
        return None
    result.check_returncode()
    return json.loads(result.stdout)

def main():
    if os.environ.get("GITHUB_REPOSITORY") != REPO or os.environ.get("GITHUB_REF") != "refs/heads/release/vocading-0.1.2":
        raise RuntimeError("Unexpected publication repository or branch")
    apk = ROOT / FILES[0]
    if hashlib.sha256(apk.read_bytes()).hexdigest() != APK_SHA:
        raise RuntimeError("Prepared APK hash differs from verified signed build")
    run = api("/actions/runs/" + str(RUN))
    if (run["head_sha"], run["status"], run["conclusion"]) != (SOURCE, "completed", "success"):
        raise RuntimeError("Exact source did not pass native Android verification")
    for commit in [SOURCE, MERGE]:
        if api("/git/commits/" + commit)["tree"]["sha"] != TREE:
            raise RuntimeError("Source and native-tested tree mismatch")
    target_tree = api("/git/commits/" + SOURCE)["tree"]["sha"]
    if target_tree != TREE:
        raise RuntimeError("Release source tree mismatch")
    with tempfile.TemporaryDirectory() as directory:
        command("gh", "run", "download", str(RUN), "--repo", REPO, "--name", "VonoTation-Android-split-native-" + MERGE, "--dir", directory)
        receipts = list(Path(directory).rglob("VERIFICATION.json"))
        if len(receipts) != 1:
            raise RuntimeError("Expected one Android verification receipt")
        native = receipts[0].parent
        receipt = json.loads(receipts[0].read_text())
        if receipt["commit"] != MERGE or any(v != "passed" for v in receipt["checks"].values()) or len(receipt["screenshots"]) != 28:
            raise RuntimeError("Native verification receipt is incomplete")
        recovery_log = (native / "logs/recovery-tests.log").read_text()
        if "PASS: unlimited unexpired queue" not in recovery_log or "explicit durable acknowledgment" not in recovery_log:
            raise RuntimeError("Recovery regression evidence is missing")
        for layout in ["large-text", "unfolded"]:
            if "INSTRUMENTATION_RESULT: result=PASS:" not in (native / "vonotation/logs" / ("instrumentation-" + layout + ".txt")).read_text():
                raise RuntimeError("Expanded-layout native verification is missing")
        (ROOT / "Android-VERIFICATION.json").write_bytes(receipts[0].read_bytes())
        with zipfile.ZipFile(ROOT / "Android-native-screenshots.zip", "w", zipfile.ZIP_DEFLATED) as screenshots:
            images = sorted(native.rglob("*.png"))
            if len(images) != 44:
                raise RuntimeError("Expected 44 native Android screenshots")
            for picture in images:
                screenshots.write(picture, picture.relative_to(native))
        certificate = command("java", "-jar", str(native / "sdk-tools/apksigner.jar"), "verify", "--verbose", "--print-certs", str(apk))
        if "Signer #1 certificate SHA-256 digest: " + SIGNER not in certificate or "Number of signers: 1" not in certificate:
            raise RuntimeError("Vocading signer differs from its retained signing identity")
        aapt = native / "sdk-tools/aapt2"
        aapt.chmod(0o755)
        badging = command(str(aapt), "dump", "badging", str(apk))
        for required in ["package: name='app.vonotation.notes' versionCode='3' versionName='0.1.2-preview'", "application-label:'Vocading'", "minSdkVersion:'26'"]:
            if required not in badging:
                raise RuntimeError("Unexpected Vocading APK metadata")
        with zipfile.ZipFile(native / "unsigned/notes-UNSIGNED.apk") as original, zipfile.ZipFile(apk) as signed:
            if not set(original.namelist()).issubset(signed.namelist()) or any(original.read(n) != signed.read(n) for n in original.namelist()):
                raise RuntimeError("Signed APK payload differs from native-tested build")
            if set(signed.namelist()) - set(original.namelist()) != set():
                raise RuntimeError("Unexpected additions to signed APK")
    command("git", "fetch", "--no-tags", "origin", SOURCE)
    command("git", "archive", "--format=zip", "--prefix=Vocading-Android/", "--output=" + str(ROOT / "Vocading-0.1.2-Android-source.zip"), SOURCE, "android", "design/vocading", "docs/VOCADING-IMPROVEMENTS.md", "README.md", "APP-SPLIT.md", "vocading-latest.json", "latest.json")
    with zipfile.ZipFile(ROOT / "Vocading-0.1.2-Android-source.zip") as source:
        if any(n.lower().endswith((".keystore", ".jks", ".p12", ".pfx", ".apk")) for n in source.namelist()):
            raise RuntimeError("Private or compiled file in source archive")
        if source.testzip() is not None:
            raise RuntimeError("Invalid source archive")
    (ROOT / "SHA256SUMS").write_text("".join(hashlib.sha256((ROOT / name).read_bytes()).hexdigest() + "  " + name + "\n" for name in FILES if name != "SHA256SUMS"))
    for line in (ROOT / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split("  ", 1)
        if name not in FILES or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError("Release asset checksum mismatch")
    body = (ROOT / "RELEASE-NOTES.md").read_text() + "\n" + MARKER
    release = api("/releases/tags/" + TAG, optional=True)
    if release is None:
        release = api("/releases", "POST", {"tag_name": TAG, "target_commitish": SOURCE, "name": "Vocading 0.1.2-preview", "body": body, "draft": True, "prerelease": True, "make_latest": "false"})
    elif MARKER not in release.get("body", "") or release.get("target_commitish") != SOURCE:
        raise RuntimeError("Existing release does not belong to this verified candidate")
    existing = {a["name"]: a for a in release["assets"]}
    if set(existing) - set(FILES):
        raise RuntimeError("Unexpected assets already attached to release")
    for name in FILES:
        path = ROOT / name
        if name in existing:
            if existing[name].get("digest") != "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest():
                raise RuntimeError("Refusing to replace a mismatched release asset")
            continue
        if not release["draft"]:
            raise RuntimeError("Refusing to alter an already published release")
        command("gh", "release", "upload", TAG, str(path), "--repo", REPO)
    verified = api("/releases/" + str(release["id"]))
    assets = {a["name"]: a for a in verified["assets"]}
    if set(assets) != set(FILES):
        raise RuntimeError("Published asset set is incomplete")
    for name in FILES:
        path = ROOT / name
        if assets[name]["size"] != path.stat().st_size or assets[name].get("digest") != "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest():
            raise RuntimeError("Uploaded asset digest or length mismatch")
    release = api("/releases/" + str(release["id"]), "PATCH", {"draft": False, "prerelease": True, "make_latest": "false"})
    print(json.dumps({"release": release["html_url"], "apk": assets[FILES[0]]["browser_download_url"], "sha256": APK_SHA}))

if __name__ == "__main__":
    main()
