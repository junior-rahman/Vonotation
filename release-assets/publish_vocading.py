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
SOURCE = "7d2c26aa8ce8d72b370138f5b1632093754e29c9"
TREE = "dd7d297bc4f7c9c3dc03b63d61f5b13cb05598c3"
MERGE = "5cbfc42408b3ac983570d102f7fedff2d5584594"
RUN = 40154153363
TAG = "vocading-v0.1.0-preview"
APK_SHA = "a5d87eaff11931186137b5618a2840393829ab3f3af861dd91e3cadd8fdf1eb8"
SIGNER = "b92734798f7d2642f336290beada2497ccc65c1f253511d9893499091777d317"
ROOT = Path(__file__).resolve().parent / "vocading-0.1.0-preview"
FILES = ["Vocading-0.1.0-preview.apk", "Vocading-APK-manifest.json", "Vocading-APK-signature.txt", "Voitation-Sand-Cocoa-native-preview.png", "RELEASE-NOTES.md", "SHA256SUMS"]
MARKER = "<!-- vocading-verified-preview:7d2c26aa8ce8d72b370138f5b1632093754e29c9 -->"

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
    if os.environ.get("GITHUB_REPOSITORY") != REPO or os.environ.get("GITHUB_REF") != "refs/heads/release/vocading-0.1.0-preview":
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
    with tempfile.TemporaryDirectory() as directory:
        command("gh", "run", "download", str(RUN), "--repo", REPO, "--name", "VonoTation-Android-split-native-" + MERGE, "--dir", directory)
        receipts = list(Path(directory).rglob("VERIFICATION.json"))
        if len(receipts) != 1:
            raise RuntimeError("Expected one Android verification receipt")
        native = receipts[0].parent
        receipt = json.loads(receipts[0].read_text())
        if receipt["commit"] != MERGE or any(v != "passed" for v in receipt["checks"].values()) or len(receipt["screenshots"]) != 22:
            raise RuntimeError("Native verification receipt is incomplete")
        certificate = command("java", "-jar", str(native / "sdk-tools/apksigner.jar"), "verify", "--verbose", "--print-certs", str(apk))
        if "Signer #1 certificate SHA-256 digest: " + SIGNER not in certificate or "Number of signers: 1" not in certificate:
            raise RuntimeError("Vocading signer differs from its retained signing identity")
        aapt = native / "sdk-tools/aapt2"
        aapt.chmod(0o755)
        badging = command(str(aapt), "dump", "badging", str(apk))
        for required in ["package: name='app.vonotation.notes' versionCode='1' versionName='0.1.0-preview'", "application-label:'Vocading'", "minSdkVersion:'26'"]:
            if required not in badging:
                raise RuntimeError("Unexpected Vocading APK metadata")
        with zipfile.ZipFile(native / "unsigned/notes-UNSIGNED.apk") as original, zipfile.ZipFile(apk) as signed:
            if not set(original.namelist()).issubset(signed.namelist()) or any(original.read(n) != signed.read(n) for n in original.namelist()):
                raise RuntimeError("Signed APK payload differs from native-tested build")
            if set(signed.namelist()) - set(original.namelist()) != {"META-INF/MANIFEST.MF", "META-INF/VOICEFLO.RSA", "META-INF/VOICEFLO.SF"}:
                raise RuntimeError("Unexpected additions to signed APK")
    for line in (ROOT / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split("  ", 1)
        if name not in FILES or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError("Release asset checksum mismatch")
    body = (ROOT / "RELEASE-NOTES.md").read_text() + "\n" + MARKER
    release = api("/releases/tags/" + TAG, optional=True)
    if release is None:
        release = api("/releases", "POST", {"tag_name": TAG, "target_commitish": SOURCE, "name": "Vocading 0.1.0-preview", "body": body, "draft": True, "prerelease": True, "make_latest": "false"})
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
