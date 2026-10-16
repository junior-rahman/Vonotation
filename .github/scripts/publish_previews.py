#!/usr/bin/env python3
"""Validate prepared signed previews; optionally publish them without overwrites.

Offline validation (no gh, network, token, or Android SDK required):
    python3 .github/scripts/publish_previews.py
Publishing is restricted to Actions on this repository's main branch. Signing is
performed before committing artifacts; private signing keys never belong here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import subprocess
import sys
import tempfile
from urllib.parse import quote
import zipfile

REPOSITORY = "junior-rahman/Vonotation"
REPOSITORY_ID = "1416658953"
PACKAGE = "app.voiceflow"
TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-preview(?:\.[1-9][0-9]*)?\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\Z")
MARKER = "vonotation-release-publisher:v1"
# These already-published archives carry review text outside VoiceFlow/. Bind
# compatibility to their exact bytes, not a caller-supplied tag or filename.
# Current archives keep the strict VoiceFlow/ root policy below.
LEGACY_SOURCE_EXTRAS = {
    "12bcd53f7e7cda231bcf38f0ad38d4d8bfc8cf0f9cd6e10bdf2712cad310ce52": {
        "PREVIEW-README.txt", "Vonotation-Quick-Thoughts-Feature-Plan.txt",
        "Vonotation-0.1.8-preview.patch", "changed-files.txt", "validation/pure-test-results.txt",
    },
    "3cbdec7bd1c53a4a556f63d7d8b89d6fdbe6020cc8e5ae338af7b60d8782a453": {
        "PREVIEW-README.txt", "Vonotation-0.1.9-preview.patch", "changed-files.txt",
    },
    "e6130fa31cf6a3defdd3f021f05a55caffa2e894ddcb76f01e56cc2e4335a399": {
        "PREVIEW-README.txt", "Vonotation-0.1.10-preview.patch", "changed-files.txt",
    },
}


class InvalidRelease(Exception):
    pass


def require(condition, message):
    if not condition:
        raise InvalidRelease(message)


def sha256_file(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(data)
    return h.hexdigest()


def checked_path(root, value, prefix):
    require(isinstance(value, str) and "\\" not in value, "Invalid artifact path")
    relative = PurePosixPath(value)
    require(not relative.is_absolute() and all(p not in (".", "..") for p in relative.parts),
            "Artifact paths must be relative and contain no traversal")
    require(value == relative.as_posix() and relative.parts[:len(prefix)] == tuple(prefix),
            "Artifact path must be inside " + "/".join(prefix))
    current = root
    for part in relative.parts:
        current /= part
        require(not current.is_symlink(), "Symlink artifact paths are not accepted")
    require(current.is_file() and current.resolve().is_relative_to(root), "Missing artifact: " + value)
    return current


def string_pool(blob, offset, header_size, chunk_size):
    require(header_size >= 28 and chunk_size >= header_size, "Invalid APK string pool")
    count, _, flags, start = struct.unpack_from("<IIII", blob, offset + 8)
    require(count <= 100000 and header_size + count * 4 <= chunk_size, "Invalid APK string count")
    end = offset + chunk_size

    def length(pos, utf8):
        require(pos < end, "Truncated APK string")
        first = blob[pos] if utf8 else struct.unpack_from("<H", blob, pos)[0]
        pos += 1 if utf8 else 2
        high = 0x80 if utf8 else 0x8000
        if first & high:
            require(pos + (1 if utf8 else 2) <= end, "Truncated APK string length")
            second = blob[pos] if utf8 else struct.unpack_from("<H", blob, pos)[0]
            pos += 1 if utf8 else 2
            first = ((first & (high - 1)) << (8 if utf8 else 16)) | second
        return first, pos

    strings = []
    for index in range(count):
        relative = struct.unpack_from("<I", blob, offset + header_size + index * 4)[0]
        pos = offset + start + relative
        require(offset + header_size <= pos < end, "Invalid APK string offset")
        utf8 = bool(flags & 0x100)
        size, pos = length(pos, utf8)
        if utf8:
            size, pos = length(pos, True)
        else:
            size *= 2
        require(pos + size <= end, "Truncated APK string data")
        strings.append(blob[pos:pos + size].decode("utf-8" if utf8 else "utf-16le"))
    return strings


def apk_identity(path):
    """Read only manifest root attributes from Android's binary XML format."""
    with zipfile.ZipFile(path) as archive:
        item = archive.getinfo("AndroidManifest.xml")
        require(item.file_size <= 4 * 1024 * 1024, "APK manifest is unexpectedly large")
        blob = archive.read(item)
    require(len(blob) >= 8, "Truncated APK manifest")
    kind, header_size, total = struct.unpack_from("<HHI", blob)
    require(kind == 0x0003 and total == len(blob), "APK needs a valid binary Android manifest")
    offset = header_size
    strings = None
    while offset + 8 <= total:
        kind, header_size, size = struct.unpack_from("<HHI", blob, offset)
        require(8 <= header_size <= size and offset + size <= total, "Invalid APK XML chunk")
        if kind == 0x0001:
            strings = string_pool(blob, offset, header_size, size)
        elif kind == 0x0102:
            require(strings is not None and size >= 36, "Invalid APK element")
            _, name = struct.unpack_from("<II", blob, offset + 16)
            require(name < len(strings), "Invalid APK element name")
            if strings[name] == "manifest":
                start, stride, count = struct.unpack_from("<HHH", blob, offset + 24)
                require(stride >= 20 and 16 + start + stride * count <= size, "Invalid APK attributes")
                attributes = {}
                for index in range(count):
                    pos = offset + 16 + start + stride * index
                    _, key, raw = struct.unpack_from("<III", blob, pos)
                    value_type = blob[pos + 15]
                    value = struct.unpack_from("<I", blob, pos + 16)[0]
                    require(key < len(strings), "Invalid APK attribute name")
                    if raw != 0xFFFFFFFF:
                        require(raw < len(strings), "Invalid APK attribute value")
                        value = strings[raw]
                    elif value_type == 0x03:
                        require(value < len(strings), "Invalid APK string attribute")
                        value = strings[value]
                    elif value_type not in (0x10, 0x11):
                        continue
                    attributes[strings[key]] = value
                return attributes
        offset += size
    raise InvalidRelease("APK manifest root was not found")


def validate_source(path):
    legacy_extras = LEGACY_SOURCE_EXTRAS.get(sha256_file(path), set())
    forbidden = {".git", ".gradle", ".kotlin", "build", "preview-signing", "baseline", "__pycache__",
                 ".idea", ".venv", "node_modules", "cache", "caches", ".env", "local.properties"}
    suffixes = {".keystore", ".jks", ".p12", ".pfx", ".pem", ".key", ".der", ".apk", ".aab", ".apks",
                ".zip", ".7z", ".tar", ".gz", ".tgz", ".class", ".dex", ".so", ".aar", ".jar", ".pyc",
                ".pyo", ".log", ".hprof", ".tmp", ".bak", ".lock", ".db", ".sqlite", ".sqlite3",
                ".m4a", ".wav", ".mp3", ".ogg", ".aac", ".opus", ".mp4"}
    with zipfile.ZipFile(path) as archive:
        require(len(archive.infolist()) <= 100000, "Source archive is unexpectedly large")
        seen = set()
        for item in archive.infolist():
            relative = PurePosixPath(item.filename)
            require(not relative.is_absolute() and ".." not in relative.parts and "\\" not in item.filename,
                    "Unsafe source archive member")
            require((relative.parts and relative.parts[0] == "VoiceFlow") or item.filename in legacy_extras,
                    "Unexpected source archive root")
            require(item.filename not in seen, "Duplicate source archive member")
            seen.add(item.filename)
            require(not any(p in forbidden for p in relative.parts), "Private/build-only source archive member: " + item.filename)
            require(not relative.name.startswith(".env."), "Private environment file found in source archive")
            require(not stat.S_ISLNK(item.external_attr >> 16), "Source archive symlink is not accepted")
            require(relative.as_posix() == "VoiceFlow/gradle/wrapper/gradle-wrapper.jar" or
                    relative.suffix.lower() not in suffixes,
                    "Private, recording or compiled file found in source archive: " + item.filename)
        require(archive.testzip() is None, "Corrupt source archive")


def load_manifest(root, path):
    require(path.parent == root / "release-manifests" and not path.is_symlink(), "Invalid manifest location")
    require(path.stat().st_size <= 65536, "Manifest is too large")
    data = json.loads(path.read_text(encoding="utf-8"))
    keys = {"schemaVersion", "tag", "versionName", "versionCode", "package", "prerelease", "apk", "source", "notes"}
    require(isinstance(data, dict) and set(data) == keys, "Unexpected manifest schema: " + path.name)
    tag = data["tag"]
    require(isinstance(tag, str) and TAG.fullmatch(tag), "Only versioned preview tags are accepted")
    require(path.name == tag + ".json", "Manifest filename must match its tag")
    require(data["schemaVersion"] == 1 and type(data["schemaVersion"]) is int, "Unsupported manifest schema")
    require(data["versionName"] == tag[1:] and data["package"] == PACKAGE and data["prerelease"] is True,
            "Manifest tag, Android version, package, or preview status is inconsistent")
    require(type(data["versionCode"]) is int and 0 < data["versionCode"] < 2100000000, "Invalid Android versionCode")
    prefix = ["downloads", tag]
    assets = []
    for kind, suffix in (("apk", ".apk"), ("source", ".zip")):
        entry = data[kind]
        require(isinstance(entry, dict) and set(entry) == {"path", "sha256", "size"}, "Invalid " + kind + " entry")
        require(isinstance(entry["sha256"], str) and SHA256.fullmatch(entry["sha256"]), "Invalid SHA256")
        require(type(entry["size"]) is int and 0 < entry["size"] < 100 * 1024 * 1024, "Invalid artifact size")
        artifact = checked_path(root, entry["path"], prefix)
        require(artifact.suffix == suffix and NAME.fullmatch(artifact.name), "Unexpected artifact filename")
        require(artifact.stat().st_size == entry["size"], "Size mismatch: " + artifact.name)
        require(sha256_file(artifact) == entry["sha256"], "SHA256 mismatch: " + artifact.name)
        assets.append({"name": artifact.name, "path": artifact, "size": entry["size"], "sha256": entry["sha256"]})
    attributes = apk_identity(assets[0]["path"])
    require(attributes.get("package") == data["package"] and
            str(attributes.get("versionCode")) == str(data["versionCode"]) and
            attributes.get("versionName") == data["versionName"], "APK identity does not match the release manifest")
    validate_source(assets[1]["path"])
    require(data["notes"] in {"release-notes/" + tag + ".txt", "release-notes/" + tag + ".md"},
            "Release notes filename must match the release tag")
    notes_path = checked_path(root, data["notes"], ["release-notes"])
    require(notes_path.stat().st_size <= 100000, "Release notes are too large")
    notes = notes_path.read_bytes()
    text = notes.decode("utf-8")
    require(text.strip() and "\x00" not in text and MARKER not in text, "Invalid release notes")
    canonical = json.dumps(data, sort_keys=True, separators=(",", ":")).encode()
    fingerprint = hashlib.sha256(canonical + b"\n" + notes).hexdigest()
    body = text.rstrip() + "\n\n<!-- " + MARKER + " sha256=" + fingerprint + " -->"
    require(len(body.encode()) <= 120000, "Release body is too large")
    data.update({"assets": assets, "notesBytes": notes, "body": body})
    return data


class GitHub:
    def run(self, args, *, input_data=None, output=None, allow_404=False):
        result = subprocess.run(["gh", *args], input=input_data, stdout=output or subprocess.PIPE,
                                stderr=subprocess.PIPE, check=False)
        if result.returncode:
            if allow_404 and b"(HTTP 404)" in result.stderr:
                return None
            raise InvalidRelease("GitHub CLI failed: " + result.stderr.decode(errors="replace").strip())
        return result.stdout if output is None else b""

    def api(self, endpoint, *, method="GET", data=None, allow_404=False):
        args = ["api", "--method", method, "-H", "Accept: application/vnd.github+json",
                "-H", "X-GitHub-Api-Version: 2022-11-28", endpoint]
        if data is not None:
            args.extend(["--input", "-"])
        raw = self.run(args, input_data=json.dumps(data).encode() if data is not None else None,
                       allow_404=allow_404)
        return json.loads(raw) if raw is not None else None

    def releases(self):
        releases = []
        page = 1
        while True:
            batch = self.api(f"repos/{REPOSITORY}/releases?per_page=100&page={page}")
            require(isinstance(batch, list), "Invalid release list response")
            releases.extend(batch)
            if len(batch) < 100:
                return releases
            page += 1

    def assets(self, release_id):
        result = []
        page = 1
        while True:
            batch = self.api(f"repos/{REPOSITORY}/releases/{release_id}/assets?per_page=100&page={page}")
            result.extend(batch)
            if len(batch) < 100:
                return result
            page += 1

    def verified_asset(self, actual, expected, temp):
        require(actual.get("state") == "uploaded" and actual.get("size") == expected["size"],
                "Existing asset is incomplete or has a different size: " + expected["name"])
        digest = actual.get("digest")
        if digest is not None:
            require(digest == "sha256:" + expected["sha256"], "Existing asset SHA256 mismatch: " + expected["name"])
        else:
            download = temp / ("verify-" + str(actual["id"]))
            with download.open("wb") as stream:
                self.run(["api", f"repos/{REPOSITORY}/releases/assets/{actual['id']}",
                          "-H", "Accept: application/octet-stream"], output=stream)
            require(download.stat().st_size == expected["size"] and sha256_file(download) == expected["sha256"],
                    "Downloaded asset SHA256 mismatch: " + expected["name"])


def publish(gh, manifest, existing, temp):
    tag = manifest["tag"]
    title = "VonoTation " + manifest["versionName"]
    assets = list(manifest["assets"])
    sums = "".join(asset["sha256"] + "  " + asset["name"] + "\n" for asset in assets).encode()
    for name, content in (("SHA256SUMS", sums), ("RELEASE-NOTES.md", manifest["notesBytes"])):
        path = temp / name
        path.write_bytes(content)
        assets.append({"name": name, "path": path, "size": len(content), "sha256": hashlib.sha256(content).hexdigest()})
    expected_names = {item["name"] for item in assets}
    require(len(expected_names) == 4, "Release asset names must be unique")
    if existing is None:
        tag_ref = gh.api(f"repos/{REPOSITORY}/git/ref/tags/{quote(tag, safe='')}", allow_404=True)
        require(tag_ref is None, "Refusing to create a release for an existing, unowned tag: " + tag)
        existing = gh.api(f"repos/{REPOSITORY}/releases", method="POST", data={
            "tag_name": tag, "target_commitish": os.environ["GITHUB_SHA"], "name": title,
            "body": manifest["body"], "draft": True, "prerelease": True, "make_latest": "false"})
        print("Created draft " + tag, flush=True)
    require(existing.get("tag_name") == tag and existing.get("name") == title and
            existing.get("prerelease") is True and existing.get("body") == manifest["body"],
            "Refusing to alter a release whose ownership or metadata differs: " + tag)
    release_id = existing["id"]
    remote = gh.assets(release_id)
    remote_names = [item["name"] for item in remote]
    require(len(set(remote_names)) == len(remote_names) and set(remote_names) <= expected_names,
            "Refusing to alter a release containing unexpected or duplicate assets: " + tag)
    actual_by_name = {item["name"]: item for item in remote}
    for expected in assets:
        actual = actual_by_name.get(expected["name"])
        if actual:
            gh.verified_asset(actual, expected, temp)
        elif existing.get("draft") is not True:
            raise InvalidRelease("Published release is missing an expected asset; no overwrite performed: " + tag)
    if existing.get("draft") is not True:
        print("Verified existing published preview " + tag + ": " + existing["html_url"], flush=True)
        return
    for expected in assets:
        if expected["name"] not in actual_by_name:
            # No --clobber: an unexpected concurrent upload must fail instead of overwrite.
            gh.run(["release", "upload", tag, str(expected["path"]), "--repo", REPOSITORY])
            print("Uploaded " + expected["name"], flush=True)
    remote = gh.assets(release_id)
    require(len(remote) == len(assets) and {a["name"] for a in remote} == expected_names,
            "Draft asset set is incomplete or changed")
    actual_by_name = {item["name"]: item for item in remote}
    for expected in assets:
        gh.verified_asset(actual_by_name[expected["name"]], expected, temp)
    current = gh.api(f"repos/{REPOSITORY}/releases/{release_id}")
    require(current.get("draft") is True and current.get("prerelease") is True and
            current.get("tag_name") == tag and current.get("name") == title and
            current.get("body") == manifest["body"], "Draft ownership or metadata changed during upload")
    released = gh.api(f"repos/{REPOSITORY}/releases/{release_id}", method="PATCH", data={
        "draft": False, "prerelease": True, "make_latest": "false"})
    require(released.get("draft") is False and released.get("prerelease") is True,
            "Release publication was not confirmed")
    print("Published preview " + tag + ": " + released["html_url"], flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true", help="Publish validated manifests in GitHub Actions")
    parser.add_argument("manifests", nargs="*", help="Optional release-manifests/v*.json paths; default is all")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    paths = [root / value for value in args.manifests] if args.manifests else sorted((root / "release-manifests").glob("v*.json"))
    require(paths, "No release manifests found")
    manifests = [load_manifest(root, path) for path in paths]
    require(len({m["tag"] for m in manifests}) == len(manifests), "Duplicate release tag")
    require(len({m["versionCode"] for m in manifests}) == len(manifests), "Duplicate Android versionCode")
    manifests.sort(key=lambda m: m["versionCode"])
    for manifest in manifests:
        print(f"Validated {manifest['tag']} (Android {manifest['package']} / {manifest['versionCode']})", flush=True)
    if not args.publish:
        print("Offline validation passed; no network requests or changes made.")
        return
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("GITHUB_REPOSITORY") == REPOSITORY and
            os.environ.get("GITHUB_REPOSITORY_ID") == REPOSITORY_ID and
            os.environ.get("GITHUB_REF") == "refs/heads/main" and
            re.fullmatch(r"[0-9a-f]{40}", os.environ.get("GITHUB_SHA", "")),
            "Publishing is allowed only in this repository's main-branch GitHub Actions workflow")
    require(os.environ.get("GH_TOKEN"), "GitHub Actions token is missing")
    gh = GitHub()
    releases = gh.releases()
    for manifest in manifests:
        matches = [release for release in releases if release.get("tag_name") == manifest["tag"]]
        require(len(matches) <= 1, "Multiple releases use this tag")
        with tempfile.TemporaryDirectory(prefix="vonotation-release-") as temporary:
            publish(gh, manifest, matches[0] if matches else None, Path(temporary))


if __name__ == "__main__":
    try:
        main()
    except (InvalidRelease, OSError, ValueError, KeyError, struct.error, zipfile.BadZipFile) as error:
        print("Release validation/publication stopped: " + str(error), file=sys.stderr)
        sys.exit(1)
