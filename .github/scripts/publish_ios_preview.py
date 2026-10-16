#!/usr/bin/env python3
"""Pin native CI evidence before publishing an explicitly Simulator-only prerelease.

Normal validation and --inspect-run are read-only. --publish is restricted to
this repository's main-branch Actions environment. No external packages needed.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import plistlib
import re
import stat
import struct
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zlib

REPO = "junior-rahman/Vonotation"
REPO_ID = "1416658953"
TAG = "v0.1.17-ios-preview"
NATIVE_WORKFLOW = ".github/workflows/ios-ci.yml"
SHA = re.compile(r"[0-9a-f]{64}\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
MARKER = "vonotation-ios-release-publisher:v1"
MAX_DOWNLOAD = 512 * 1024 * 1024
MAX_UNPACKED = 2 * 1024 * 1024 * 1024
ANDROID = {
    "apk": ("VonoTation-0.1.17-preview.apk", 6212950,
            "7ba7ecd9423d98dd7c1feb478123f76fd119454194323c02522273a24bd83c29"),
    "source": ("Vonotation-0.1.17-preview-source.zip", 1162649,
               "f832ac268b0870fca188aa93db6d9ab2d632fbf2d002a250cac7e81fd8352061"),
}


class InvalidRelease(Exception):
    pass


def require(value, message):
    if not value:
        raise InvalidRelease(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def integer(value):
    return type(value) is int and value > 0


def path_parts(name):
    path = PurePosixPath(name)
    require(isinstance(name, str) and name and "\\" not in name and "\x00" not in name and
            not path.is_absolute() and ".." not in path.parts and
            name.rstrip("/") == path.as_posix() and not re.match(r"^[A-Za-z]:", name),
            "Unsafe or noncanonical ZIP/path member")
    return path


class Bundle:
    def __init__(self, source):
        self.zip = zipfile.ZipFile(source)
        infos = self.zip.infolist()
        require(0 < len(infos) <= 100000, "Invalid ZIP entry count")
        seen, total = set(), 0
        for item in infos:
            path_parts(item.filename)
            normalized = item.filename.rstrip("/")
            require(normalized not in seen, "Duplicate ZIP member")
            seen.add(normalized)
            require(not item.flag_bits & 1 and not stat.S_ISLNK(item.external_attr >> 16),
                    "Encrypted ZIP members and symlinks are not accepted")
            require(0 <= item.file_size <= MAX_DOWNLOAD, "ZIP member exceeds size limit")
            total += item.file_size
            require(total <= MAX_UNPACKED, "ZIP expansion exceeds size limit")
        self.files = {item.filename: item for item in infos if not item.is_dir()}

    def read(self, name, limit=32 * 1024 * 1024):
        require(name in self.files and self.files[name].file_size <= limit,
                "Required ZIP member is missing or too large: " + name)
        return self.zip.read(name)

    def json(self, name):
        value = json.loads(self.read(name).decode("utf-8"))
        require(isinstance(value, dict), "Expected JSON object: " + name)
        return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def storage_url(url):
    parsed = urllib.parse.urlsplit(url)
    host = (parsed.hostname or "").lower()
    require(parsed.scheme == "https" and parsed.port in (None, 443) and
            not parsed.username and not parsed.password and not parsed.fragment and
            any(host.endswith(suffix) for suffix in
                (".blob.core.windows.net", ".actions.githubusercontent.com", ".githubusercontent.com")),
            "Artifact redirect must use an approved HTTPS storage host")
    return url


class GitHub:
    def __init__(self, token=None):
        self.token = token if token is not None else os.environ.get("GH_TOKEN", "")
        self.opener = urllib.request.build_opener(NoRedirect)

    def request(self, url, *, authenticated, method="GET", data=None, accept="application/vnd.github+json"):
        parsed = urllib.parse.urlsplit(url)
        if authenticated:
            require(parsed.scheme == "https" and parsed.hostname in ("api.github.com", "uploads.github.com")
                    and parsed.port in (None, 443) and not parsed.username and not parsed.password,
                    "Authenticated requests are restricted to GitHub API hosts")
        headers = {"User-Agent": "VonoTation-ios-release-verification/1.0", "Accept": accept,
                   "X-GitHub-Api-Version": "2022-11-28"}
        if authenticated and self.token:
            headers["Authorization"] = "Bearer " + self.token
        if data is not None:
            headers["Content-Type"] = "application/json" if accept != "application/octet-stream" else "application/octet-stream"
        return self.opener.open(urllib.request.Request(url, headers=headers, method=method, data=data), timeout=60)

    def api(self, endpoint, *, method="GET", data=None, allow_404=False):
        try:
            with self.request("https://api.github.com/repos/" + REPO + "/" + endpoint,
                              authenticated=True, method=method,
                              data=canonical(data) if data is not None else None) as response:
                payload = response.read(32 * 1024 * 1024 + 1)
                require(len(payload) <= 32 * 1024 * 1024, "GitHub API response is too large")
                return json.loads(payload)
        except urllib.error.HTTPError as error:
            if allow_404 and error.code == 404:
                return None
            raise InvalidRelease("GitHub API request failed with HTTP " + str(error.code)) from None

    def pages(self, endpoint, key=None):
        output = []
        for page in range(1, 101):
            separator = "&" if "?" in endpoint else "?"
            result = self.api(endpoint + separator + f"per_page=100&page={page}")
            batch = result[key] if key else result
            require(isinstance(batch, list), "Unexpected GitHub list response")
            output.extend(batch)
            if len(batch) < 100:
                return output
        raise InvalidRelease("GitHub result pagination exceeded its bound")

    def download(self, endpoint, destination, size, expected_sha):
        """Never forward an API bearer token to an artifact's signed storage URL."""
        require(integer(size) and size <= MAX_DOWNLOAD and SHA.fullmatch(expected_sha), "Invalid download pin")
        url = "https://api.github.com/repos/" + REPO + "/" + endpoint
        try:
            response = self.request(url, authenticated=True, accept="application/octet-stream")
        except urllib.error.HTTPError as error:
            require(error.code in (301, 302, 303, 307, 308), "Artifact API download failed")
            location = storage_url(error.headers.get("Location", ""))
            # Every storage request is new and explicitly unauthenticated.
            response = None
            for _ in range(5):
                try:
                    response = self.request(location, authenticated=False, accept="application/octet-stream")
                    break
                except urllib.error.HTTPError as redirect:
                    require(redirect.code in (301, 302, 303, 307, 308), "Signed artifact download failed")
                    location = storage_url(redirect.headers.get("Location", ""))
            require(response is not None, "Too many signed artifact redirects")
        actual, count = hashlib.sha256(), 0
        with response, destination.open("wb") as stream:
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                count += len(chunk)
                require(count <= size, "Downloaded artifact exceeds its pinned size")
                stream.write(chunk)
                actual.update(chunk)
        require(count == size and actual.hexdigest() == expected_sha, "Downloaded artifact differs from its pinned digest/size")

    def upload(self, release_id, asset):
        url = "https://uploads.github.com/repos/" + REPO + f"/releases/{release_id}/assets?" + urllib.parse.urlencode({"name": asset["name"]})
        with self.request(url, authenticated=True, method="POST", data=asset["path"].read_bytes(),
                          accept="application/octet-stream") as response:
            return json.loads(response.read())


def checked_run(gh, run_id, commit=None):
    require(integer(run_id), "Native run ID must be positive")
    run = gh.api(f"actions/runs/{run_id}")
    require(run.get("id") == run_id and run.get("status") == "completed" and run.get("conclusion") == "success",
            "Native workflow must have completed successfully")
    require(run.get("repository", {}).get("id") == int(REPO_ID) and
            run.get("head_repository", {}).get("id") == int(REPO_ID) and
            run.get("head_branch") == "main" and run.get("event") in ("push", "workflow_dispatch") and
            run.get("path") == NATIVE_WORKFLOW and run.get("name") == "iOS native verification",
            "Native run does not belong to this repository's trusted main-branch workflow")
    require(COMMIT.fullmatch(run.get("head_sha", "")) and (commit is None or run["head_sha"] == commit),
            "Native run does not match the tested source commit")
    jobs = gh.pages(f"actions/runs/{run_id}/attempts/{run['run_attempt']}/jobs", "jobs")
    require(len(jobs) == 1 and jobs[0].get("name") == "native-ios" and jobs[0].get("conclusion") == "success" and
            any(label.startswith("macos-") for label in jobs[0].get("labels", [])),
            "Native verification must be a successful macOS job")
    return run


def source_allowed(name):
    path = path_parts(name)
    require(not {".git", "build", "__pycache__", "xcuserdata", "DerivedData", "node_modules", ".env"}.intersection(path.parts)
            and not any(p.startswith(".") and p != ".gitignore" for p in path.parts), "Private/generated iOS source member")
    require(path.suffix.lower() not in {".pyc", ".ipa", ".p12", ".mobileprovision", ".key", ".pem", ".keystore", ".jks", ".log"},
            "Private/compiled iOS source member")
    return path


def native_inventory_member(name):
    path = PurePosixPath(name)
    return (not {"build", "__pycache__", "xcuserdata", "DerivedData"}.intersection(path.parts)
            and not any(p.startswith(".") and p != ".gitignore" for p in path.parts)
            and path.suffix not in {".pyc", ".ipa", ".p12", ".mobileprovision"})


def png_dimensions(data):
    require(data.startswith(b"\x89PNG\r\n\x1a\n"), "Invalid native screenshot PNG signature")
    offset, dimensions, image_data, ended = 8, None, False, False
    while offset + 12 <= len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        require(size <= len(data) - offset - 12, "Truncated screenshot PNG chunk")
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + size]
        crc = struct.unpack_from(">I", data, offset + 8 + size)[0]
        require(zlib.crc32(kind + payload) & 0xffffffff == crc, "Invalid screenshot PNG checksum")
        if kind == b"IHDR":
            require(offset == 8 and size == 13 and dimensions is None, "Invalid screenshot PNG header")
            dimensions = struct.unpack(">II", payload[:8])
        elif kind == b"IDAT":
            image_data = True
        elif kind == b"IEND":
            require(size == 0 and offset + 12 == len(data), "Invalid screenshot PNG ending")
            ended = True
            break
        offset += size + 12
    require(dimensions and image_data and ended and all(200 <= n <= 10000 for n in dimensions),
            "Incomplete screenshot PNG or invalid dimensions")
    return dimensions


def validate_intent_metadata(record, evidence):
    checks = {"record_intent": True, "finish_intent": True,
              "record_shortcut_phrase": True, "finish_shortcut_phrase": True}
    pin = record.get("intent_metadata_validation", {})
    report_bytes = evidence.read("intent-metadata-validation.json")
    report = json.loads(report_bytes)
    require(pin.get("sha256") == digest(report_bytes) and pin.get("checks") == checks
            and report.get("checks") == checks and
            all(type(value) is bool for entry in (pin, report) for value in entry["checks"].values())
            and report.get("bundle") == "VonoTation.app",
            "Generated App Intent report digest or required checks differ")
    documents = report.get("files", {})
    require(isinstance(documents, dict) and documents, "Generated App Intent metadata inventory is missing")
    expected_members, readable = set(), []
    for relative, expected in documents.items():
        path_parts(relative)
        name = "intent-metadata/" + relative
        expected_members.add(name)
        data = evidence.read(name)
        require(type(expected.get("size")) is int and len(data) == expected["size"] and
                digest(data) == expected.get("sha256"), "Generated App Intent metadata bytes differ")
        try:
            readable.append(json.dumps(json.loads(data), ensure_ascii=False))
        except (ValueError, UnicodeError):
            try:
                readable.append(str(plistlib.loads(data)))
            except (ValueError, TypeError, plistlib.InvalidFileException):
                try:
                    readable.append(data.decode("utf-8"))
                except UnicodeError:
                    pass
    actual_members = {name for name in evidence.files if name.startswith("intent-metadata/")}
    require(actual_members == expected_members, "Generated App Intent metadata inventory differs")
    text = "\n".join(readable)
    require(all(value in text for value in ("RecordQuickThoughtIntent", "FinishVonoRecordingIntent",
                                          "Record a thought with", "Finish recording in")),
            "Generated App Intent metadata is missing an intent or shortcut phrase")


def validate_native(gh, run, simulator, evidence, temp):
    sb, eb = Bundle(simulator), Bundle(evidence)
    verification_bytes = sb.read("VERIFICATION.json")
    require(verification_bytes == eb.read("VERIFICATION.json"), "CI artifacts contain different verification records")
    record = json.loads(verification_bytes)
    require(record.get("version") == "0.1.17" and record.get("build") == 89 and record.get("commit") == run["head_sha"],
            "Native verification version/build/commit differs")
    require(record.get("checks") == {"iphone_xctest_xcuitest": "passed", "ipad_xctest_xcuitest": "passed",
                                     "iphoneos_sdk_compile_unsigned": "passed", "simulator_archive": "passed"},
            "Native verification has missing or failed checks")
    validate_intent_metadata(record, eb)
    source_pin, app_pin = record["source_archive"], record["artifact"]
    require(source_pin["name"] == "VonoTation-iOS-0.1.17-source.zip" and app_pin["name"] == "VonoTation-iOS-Simulator.zip",
            "Unexpected native archive names")
    source_bytes = sb.read(source_pin["name"], MAX_DOWNLOAD)
    app_bytes = sb.read(app_pin["name"], MAX_DOWNLOAD)
    for pin, data in ((source_pin, source_bytes), (app_pin, app_bytes)):
        require(integer(pin["size"]) and len(data) == pin["size"] and digest(data) == pin["sha256"], "Native archive hash/size mismatch")
    sources = Bundle(io.BytesIO(source_bytes))
    inventory, contents = {}, {}
    for name in sources.files:
        path = path_parts(name)
        require(len(path.parts) > 1 and path.parts[0] == "VonoTation-iOS", "Unexpected iOS source ZIP root")
        relative = PurePosixPath(*path.parts[1:]).as_posix()
        source_allowed(relative)
        contents[relative] = sources.read(name)
        inventory[relative] = digest(contents[relative])
    require(inventory and inventory == record["sources_sha256"] and digest(canonical(inventory)) == record["source_payload_sha256"],
            "Native source inventory mismatch")
    tree = gh.api("git/trees/" + run["head_sha"] + "?recursive=1")
    require(tree.get("truncated") is False, "Source Git tree is truncated")
    blobs = {entry["path"]: entry for entry in tree["tree"]}
    remote_inventory = {entry["path"][4:] for entry in tree["tree"] if entry["type"] == "blob"
                        and entry["path"].startswith("ios/") and native_inventory_member(entry["path"][4:])}
    require(set(inventory) == remote_inventory, "Source ZIP is missing files from its tested commit")
    for name, data in contents.items():
        actual = blobs.get("ios/" + name, {})
        git_digest = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        require(actual.get("type") == "blob" and actual.get("mode") in ("100644", "100755") and actual.get("sha") == git_digest,
                "Source ZIP differs from tested Git commit: " + name)
    expected_count = sum(len(re.findall(rb"func\s+test\w+\s*\(", data)) for name, data in contents.items()
                         if name.endswith(".swift") and PurePosixPath(name).parts[0] in ("VonoTationTests", "VonoTationUITests"))
    require(expected_count > 0 and set(record["native_test_results"]) == {"iPhone", "iPad"}, "Incomplete native test families")
    for family in ("iPhone", "iPad"):
        tests = record["native_test_results"][family]
        summary_bytes, tree_bytes = eb.read(family + "-test-summary.json"), eb.read(family + "-test-tree.json")
        require(digest(summary_bytes) == tests["summary_sha256"] and digest(tree_bytes) == tests["tree_sha256"], "Native test result hashes differ")
        summary = json.loads(summary_bytes)
        counts = {key: summary.get(key) for key in ("totalTestCount", "passedTests", "failedTests", "skippedTests")}
        require(counts == tests["counts"] and all(type(n) is int for n in counts.values()) and
                counts == {"totalTestCount": expected_count, "passedTests": expected_count, "failedTests": 0, "skippedTests": 0},
                "Native tests are missing, failed or skipped: " + family)
    device = eb.json("device-sdk-build.json")
    require(device == record["device_sdk_build_results"] and
            digest(eb.read("logs/device-sdk-build.log")) == record["device_sdk_build_log_sha256"], "Physical SDK build evidence differs")
    require(device.get("status", "").lower() == "succeeded" and type(device.get("errorCount")) is int and device["errorCount"] == 0,
            "Unsigned physical SDK build did not succeed")
    device_log = eb.read("logs/device-sdk-build.log").decode(errors="replace")
    require("** BUILD SUCCEEDED **" in device_log and "** BUILD FAILED **" not in device_log,
            "Unsigned physical SDK build log does not confirm success")
    app = Bundle(io.BytesIO(app_bytes))
    require(all(path_parts(name).parts[0] in ("VonoTation.app", "__MACOSX") for name in app.files),
            "Unexpected simulator ZIP root")
    info = plistlib.loads(app.read("VonoTation.app/Info.plist"))
    require(info.get("CFBundleIdentifier") == "app.vonotation.ios" and info.get("CFBundleShortVersionString") == "0.1.17"
            and str(info.get("CFBundleVersion")) == "89" and info.get("CFBundleSupportedPlatforms") == ["iPhoneSimulator"]
            and info.get("DTPlatformName") == "iphonesimulator", "Archive is not the expected iOS Simulator app")
    executable = info.get("CFBundleExecutable")
    require(isinstance(executable, str) and "/" not in executable and "VonoTation.app/" + executable in app.files,
            "Simulator executable is missing")
    expected_screens = {family + "-" + screen + ".png" for family in ("iPhone", "iPad")
                        for screen in ("home", "dictations", "thoughts", "clipboard", "settings")}
    screenshot_members = {name for name in eb.files if name.startswith("screenshots/")}
    require(screenshot_members == {"screenshots/" + name for name in expected_screens}, "Expected ten native screenshots")
    screenshot_inventory = {}
    screenshots = temp / "VonoTation-iOS-native-screenshots.zip"
    with zipfile.ZipFile(screenshots, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for name in sorted(screenshot_members):
            data = eb.read(name)
            png_dimensions(data)
            screenshot_inventory[name] = digest(data)
            item = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(item, data)
    for name, data in ((app_pin["name"], app_bytes), (source_pin["name"], source_bytes), ("VERIFICATION.json", verification_bytes)):
        (temp / name).write_bytes(data)
    return {"verificationSha256": digest(verification_bytes), "sourcePayloadSha256": record["source_payload_sha256"],
            "screenshotsPayloadSha256": digest(canonical(screenshot_inventory)), "expectedTestsPerFamily": expected_count}, [
                temp / app_pin["name"], temp / source_pin["name"], temp / "VERIFICATION.json", screenshots]


def collect(gh, run_id, temp, manifest=None):
    run = checked_run(gh, run_id, manifest["sourceCommit"] if manifest else None)
    artifacts = gh.pages(f"actions/runs/{run_id}/artifacts", "artifacts")
    pins, paths = {}, {}
    for kind, prefix in (("evidence", "VonoTation-iOS-native-evidence-"), ("simulator", "VonoTation-iOS-Simulator-")):
        matches = [a for a in artifacts if a.get("name") == prefix + run["head_sha"]]
        require(len(matches) == 1, "Missing or duplicate native artifact: " + kind)
        artifact = matches[0]
        require(artifact.get("expired") is False and integer(artifact.get("id")) and
                artifact.get("workflow_run", {}).get("id") == run_id and artifact.get("workflow_run", {}).get("head_sha") == run["head_sha"]
                and artifact.get("digest", "").startswith("sha256:")
                and SHA.fullmatch(artifact.get("digest", "").removeprefix("sha256:")), "Native artifact metadata is invalid")
        pins[kind] = {"id": artifact["id"], "size": artifact["size_in_bytes"], "sha256": artifact["digest"].removeprefix("sha256:")}
        if manifest:
            require(pins[kind] == manifest["artifacts"][kind], "Native artifact ID/digest/size differs from review")
        path = temp / (kind + "-actions-artifact.zip")
        gh.download(f"actions/artifacts/{artifact['id']}/zip", path, pins[kind]["size"], pins[kind]["sha256"])
        paths[kind] = path
    observed, files = validate_native(gh, run, paths["simulator"], paths["evidence"], temp)
    observed.update({"sourceCommit": run["head_sha"], "nativeRunId": run_id, "nativeRunAttempt": run["run_attempt"],
                     "nativeWorkflowId": run["workflow_id"], "artifacts": pins})
    if manifest:
        for key, value in observed.items():
            require(manifest.get(key) == value, "Reviewed native evidence differs: " + key)
    return observed, files


def load_manifest(root, path):
    require(path.parent == root / "ios-release-manifests" and not path.is_symlink() and path.name == TAG + ".json",
            "Unexpected iOS manifest location")
    require(path.stat().st_size <= 65536, "iOS release manifest is too large")
    manifest = json.loads(path.read_text())
    fields = {"schemaVersion", "tag", "version", "build", "sourceCommit", "nativeRunId", "nativeRunAttempt", "nativeWorkflowId",
              "expectedTestsPerFamily", "artifacts", "verificationSha256", "sourcePayloadSha256", "screenshotsPayloadSha256", "screenshotsReviewed", "notes"}
    require(isinstance(manifest, dict) and set(manifest) == fields and manifest["schemaVersion"] == 1
            and type(manifest["schemaVersion"]) is int and manifest["tag"] == TAG and manifest["version"] == "0.1.17"
            and type(manifest["build"]) is int and manifest["build"] == 89, "Unexpected iOS manifest schema/version")
    require(COMMIT.fullmatch(manifest["sourceCommit"]) and manifest["screenshotsReviewed"] is True,
            "Exact native source and explicit native screenshot review are required")
    require(all(integer(manifest[key]) for key in ("nativeRunId", "nativeRunAttempt", "nativeWorkflowId", "expectedTestsPerFamily")),
            "Missing native run/test pins")
    require(all(SHA.fullmatch(manifest[key]) for key in ("verificationSha256", "sourcePayloadSha256", "screenshotsPayloadSha256")), "Invalid native digest pins")
    require(set(manifest["artifacts"]) == {"evidence", "simulator"}, "Unexpected native artifact kinds")
    for value in manifest["artifacts"].values():
        require(set(value) == {"id", "size", "sha256"} and integer(value["id"]) and integer(value["size"])
                and value["size"] <= MAX_DOWNLOAD and SHA.fullmatch(value["sha256"]), "Invalid native artifact pin")
    require(manifest["artifacts"]["evidence"]["id"] != manifest["artifacts"]["simulator"]["id"], "Native artifact IDs must differ")
    require(manifest["notes"] == "ios-release-notes/" + TAG + ".md", "Unexpected native release notes path")
    notes_path = root / manifest["notes"]
    require(not notes_path.is_symlink() and notes_path.is_file() and notes_path.stat().st_size <= 100000, "Native release notes missing")
    notes = notes_path.read_text()
    require("Release review status: draft" not in notes and MARKER not in notes and "\x00" not in notes
            and "not an installable iPhone IPA" in notes and "Simulator" in notes and "Mac" in notes,
            "Reviewed notes must explicitly describe Simulator-only distribution")
    return manifest, notes


def android_assets(root):
    path = root / "release-manifests/v0.1.17-preview.json"
    require(not path.is_symlink(), "Android manifest cannot be a symlink")
    manifest = json.loads(path.read_text())
    require(manifest.get("tag") == "v0.1.17-preview" and manifest.get("versionName") == "0.1.17-preview"
            and manifest.get("versionCode") == 89 and manifest.get("package") == "app.voiceflow", "Android release identity differs")
    files = []
    for kind, (name, size, sha) in ANDROID.items():
        relative = "downloads/v0.1.17-preview/" + name
        require(manifest[kind] == {"path": relative, "size": size, "sha256": sha}, "Android asset pin differs")
        file = root / relative
        require(file.is_file() and not file.is_symlink() and file.stat().st_size == size and file_digest(file) == sha, "Android asset bytes differ")
        files.append(file)
    return files


def asset(path):
    return {"name": path.name, "path": path, "size": path.stat().st_size, "sha256": file_digest(path)}


def verify_remote_asset(gh, actual, expected, temp):
    require(actual.get("state") == "uploaded" and actual.get("size") == expected["size"], "Remote release asset is incomplete")
    if actual.get("digest") is not None:
        require(actual["digest"] == "sha256:" + expected["sha256"], "Remote release asset digest differs")
    else:
        gh.download(f"releases/assets/{actual['id']}", temp / ("remote-" + str(actual["id"])), expected["size"], expected["sha256"])


def verify_tag(gh, commit, allow_create=False):
    reference = gh.api("git/ref/tags/" + TAG, allow_404=True)
    if reference is None and allow_create:
        reference = gh.api("git/refs", method="POST", data={"ref": "refs/tags/" + TAG, "sha": commit})
    require(reference is not None, "Owned release tag is missing")
    target = reference.get("object", {})
    for _ in range(5):
        require(COMMIT.fullmatch(target.get("sha", "")), "Release tag target is invalid")
        if target.get("type") == "commit":
            require(target["sha"] == commit, "Actual iOS release tag differs from the tested source commit")
            return
        require(target.get("type") == "tag", "Release tag must resolve to a commit")
        target = gh.api("git/tags/" + target["sha"]).get("object", {})
    raise InvalidRelease("Release tag annotation depth exceeds its bound")


def publish(gh, manifest, notes, files, temp):
    latest_run = checked_run(gh, manifest["nativeRunId"], manifest["sourceCommit"])
    require(latest_run["run_attempt"] == manifest["nativeRunAttempt"] and latest_run["workflow_id"] == manifest["nativeWorkflowId"],
            "Native verification attempt changed before publication")
    fingerprint = digest(canonical(manifest) + b"\n" + notes.encode())
    body = notes.rstrip() + "\n\nNative CI: https://github.com/" + REPO + "/actions/runs/" + str(manifest["nativeRunId"]) + "\n\n<!-- " + MARKER + " sha256=" + fingerprint + " -->"
    (temp / "IOS-RELEASE-MANIFEST.json").write_bytes(json.dumps(manifest, indent=2).encode() + b"\n")
    (temp / "RELEASE-NOTES.md").write_text(notes)
    files = files + [temp / "IOS-RELEASE-MANIFEST.json", temp / "RELEASE-NOTES.md"]
    (temp / "SHA256SUMS").write_text("".join(file_digest(p) + "  " + p.name + "\n" for p in files))
    files.append(temp / "SHA256SUMS")
    expected = {p.name: asset(p) for p in files}
    require(len(expected) == len(files), "Release asset names must be unique")
    current = gh.api("releases/tags/" + TAG, allow_404=True)
    if current is None:
        # Tag lookup can omit drafts. An authenticated listing must discover an
        # owned partial draft before an existing tag is treated as unowned.
        drafts = [release for release in gh.pages("releases") if release.get("tag_name") == TAG]
        require(len(drafts) <= 1, "Multiple iOS releases use the dedicated tag")
        current = drafts[0] if drafts else None
    title = "VonoTation 0.1.17 iOS Simulator preview"
    if current is None:
        require(gh.api("git/ref/tags/" + TAG, allow_404=True) is None, "Refusing an existing unowned iOS release tag")
        current = gh.api("releases", method="POST", data={"tag_name": TAG, "target_commitish": manifest["sourceCommit"], "name": title,
                        "body": body, "draft": True, "prerelease": True, "make_latest": "false"})
    require(current.get("tag_name") == TAG and current.get("name") == title and current.get("body") == body
            and current.get("prerelease") is True and current.get("target_commitish") == manifest["sourceCommit"],
            "Refusing to alter an iOS release with different ownership or source")
    verify_tag(gh, manifest["sourceCommit"], allow_create=current.get("draft") is True)
    release_id = current["id"]
    remote = gh.pages(f"releases/{release_id}/assets")
    actual = {item["name"]: item for item in remote}
    require(len(actual) == len(remote) and actual.keys() <= expected.keys(), "Unexpected or duplicate release assets")
    for name, item in expected.items():
        if name in actual:
            verify_remote_asset(gh, actual[name], item, temp)
        else:
            require(current.get("draft") is True, "Published release is missing an asset; no overwrite performed")
    if current.get("draft") is not True:
        verify_tag(gh, manifest["sourceCommit"])
        print("Verified existing published iOS Simulator preview: " + current["html_url"])
        return
    for name, item in expected.items():
        if name not in actual:
            uploaded = gh.upload(release_id, item)
            require(uploaded.get("name") == name, "Unexpected uploaded asset name")
            verify_remote_asset(gh, uploaded, item, temp)
    remote = gh.pages(f"releases/{release_id}/assets")
    require(len(remote) == len(expected) and {a["name"] for a in remote} == set(expected), "Draft asset set changed")
    for item in remote:
        verify_remote_asset(gh, item, expected[item["name"]], temp)
    latest = gh.api(f"releases/{release_id}")
    require(latest.get("draft") is True and latest.get("body") == body and latest.get("tag_name") == TAG
            and latest.get("target_commitish") == manifest["sourceCommit"], "Draft metadata changed during upload")
    latest_run = checked_run(gh, manifest["nativeRunId"], manifest["sourceCommit"])
    require(latest_run["run_attempt"] == manifest["nativeRunAttempt"] and latest_run["workflow_id"] == manifest["nativeWorkflowId"],
            "Native verification attempt changed during upload")
    verify_tag(gh, manifest["sourceCommit"])
    published = gh.api(f"releases/{release_id}", method="PATCH", data={"draft": False, "prerelease": True, "make_latest": "false"})
    require(published.get("draft") is False and published.get("prerelease") is True, "Publication was not confirmed")
    verify_tag(gh, manifest["sourceCommit"])
    print("Published iOS Simulator preview: " + published["html_url"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", nargs="?")
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--inspect-run", type=int)
    parser.add_argument("--root", type=Path, help="Repository root, for read-only local validation")
    args = parser.parse_args()
    require(not (args.inspect_run and (args.publish or args.manifest)), "Inspection cannot publish or use a release manifest")
    root = args.root.resolve() if args.root else Path(__file__).resolve().parents[2]
    if args.publish:
        require(args.root is None and os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("GITHUB_REPOSITORY") == REPO
                and os.environ.get("GITHUB_REPOSITORY_ID") == REPO_ID and os.environ.get("GITHUB_REF") == "refs/heads/main"
                and os.environ.get("GITHUB_EVENT_NAME") in ("push", "workflow_dispatch")
                and COMMIT.fullmatch(os.environ.get("GITHUB_SHA", "")) and os.environ.get("GH_TOKEN"),
                "Publishing requires this repository's main-branch GitHub Actions environment")
    gh = GitHub()
    with tempfile.TemporaryDirectory(prefix="vonotation-ios-release-") as temporary:
        temp = Path(temporary)
        if args.inspect_run:
            observed, _ = collect(gh, args.inspect_run, temp)
            candidate = {"schemaVersion": 1, "tag": TAG, "version": "0.1.17", "build": 89, **observed,
                         "screenshotsReviewed": False, "notes": "ios-release-notes/" + TAG + ".md"}
            print(json.dumps(candidate, indent=2))
            return
        require(args.manifest is not None, "Provide a reviewed manifest or --inspect-run")
        relative = path_parts(args.manifest)
        manifest, notes = load_manifest(root, root / relative)
        _, files = collect(gh, manifest["nativeRunId"], temp, manifest)
        files.extend(android_assets(root))
        print("Validated native CI, source inventory, iPhone/iPad test counts, unsigned SDK build, Simulator identity, reviewed screenshots and unchanged Android .17 assets.")
        if args.publish:
            publish(gh, manifest, notes, files, temp)
        else:
            print("Read-only validation passed; no release or update-feed changes made.")


if __name__ == "__main__":
    try:
        main()
    except (InvalidRelease, OSError, ValueError, KeyError, TypeError, struct.error, zipfile.BadZipFile) as error:
        print("iOS release stopped: " + str(error), file=sys.stderr)
        sys.exit(1)
