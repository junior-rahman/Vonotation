#!/usr/bin/env python3
"""Validate the in-app update feeds that installed apps read from main.

Offline (no network): schema checks that mirror UpdatePolicy.java, and a source check that
main's build.gradle is never behind the version a feed offers (an APK built from main must not
be a downgrade of what users run).

    python3 .github/scripts/validate_update_feeds.py

With --remote (needs the gh CLI and GH_TOKEN, as in GitHub Actions) each feed's APK must be an
uploaded asset of a published release with exactly the advertised size and SHA-256 digest.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
FEEDS = (
    # feed file, Android package, Gradle product flavor
    ("latest.json", "app.voiceflow", "vonotation"),
    ("vocading-latest.json", "app.vonotation.notes", "notes"),
)
KEYS = {"versionCode", "versionName", "apk", "sha256", "size", "package", "notes"}
HOSTS = {"github.com", "raw.githubusercontent.com", "objects.githubusercontent.com",
         "release-assets.githubusercontent.com", "github-releases.githubusercontent.com"}
MAX_FEED = 16 * 1024
MAX_APK = 40 * 1024 * 1024
DOWNLOAD = re.compile(r"/([^/]+)/([^/]+)/releases/download/([^/]+)/([^/]+)\Z")


class Invalid(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Invalid(message)


def flavor_versions(gradle_text):
    """versionCode/versionName for each product flavor in android/app/build.gradle."""
    block = gradle_text.split("productFlavors", 1)
    require(len(block) == 2, "build.gradle has no productFlavors block")
    found = {}
    for match in re.finditer(r"(\w+)\s*\{([^{}]*)\}", block[1]):
        code = re.search(r"\bversionCode\s+(\d+)", match.group(2))
        name = re.search(r"\bversionName\s+'([^']+)'", match.group(2))
        if code and name:
            found[match.group(1)] = (int(code.group(1)), name.group(1))
    return found


def secure_url(address):
    if not isinstance(address, str) or len(address) > 8192:
        return False
    parts = urlsplit(address)
    return (parts.scheme == "https" and parts.hostname in HOSTS and parts.username is None
            and parts.password is None and not parts.fragment and parts.port in (None, 443))


def check_feed(path, package, source):
    raw = path.read_bytes()
    require(len(raw) <= MAX_FEED, f"{path.name}: larger than the app's {MAX_FEED}-byte feed limit")
    data = json.loads(raw.decode("utf-8"))
    require(isinstance(data, dict) and set(data) == KEYS, f"{path.name}: expected exactly the keys {sorted(KEYS)}")
    code, name, url, digest, size = (data[k] for k in ("versionCode", "versionName", "apk", "sha256", "size"))
    require(data["package"] == package, f"{path.name}: package must be {package}")
    require(type(code) is int and 0 < code <= 2**31 - 1, f"{path.name}: invalid versionCode")
    require(isinstance(name, str) and name.strip() and len(name) <= 80 and
            not any(ord(c) < 32 or 127 <= ord(c) < 160 for c in name), f"{path.name}: invalid versionName")
    require(secure_url(url), f"{path.name}: apk must be an https GitHub URL the app accepts")
    require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest), f"{path.name}: sha256 must be 64 lowercase hex digits")
    require(type(size) is int and 0 < size <= MAX_APK, f"{path.name}: invalid size")
    require(isinstance(data["notes"], str) and data["notes"].strip(), f"{path.name}: notes must not be empty")
    source_code, source_name = source
    require(code <= source_code,
            f"{path.name}: offers version code {code} ({name}) but main's source is {source_code} ({source_name}). "
            "Merge the released source into main before advancing the feed.")
    require(code < source_code or name == source_name,
            f"{path.name}: version code {code} matches main's source but the name differs ({name} vs {source_name})")
    return data


def gh_json(endpoint):
    result = subprocess.run(["gh", "api", "-H", "Accept: application/vnd.github+json", endpoint],
                            capture_output=True, check=False)
    require(result.returncode == 0, "GitHub API request failed: " + result.stderr.decode(errors="replace").strip())
    return json.loads(result.stdout)


def check_release(name, data):
    match = DOWNLOAD.search(urlsplit(data["apk"]).path)
    require(urlsplit(data["apk"]).hostname == "github.com" and match,
            f"{name}: apk must be a github.com/OWNER/REPO/releases/download/TAG/FILE URL to verify it")
    owner, repo, tag, filename = (unquote(part) for part in match.groups())
    release = gh_json(f"repos/{owner}/{repo}/releases/tags/{tag}")
    require(release.get("draft") is False, f"{name}: release {tag} is not published")
    assets = [a for a in release.get("assets", []) if a.get("name") == filename]
    require(len(assets) == 1, f"{name}: release {tag} has no single asset named {filename}")
    asset = assets[0]
    require(asset.get("state") == "uploaded" and asset.get("size") == data["size"],
            f"{name}: release asset size {asset.get('size')} does not match the feed's {data['size']}")
    require(asset.get("digest") == "sha256:" + data["sha256"],
            f"{name}: release asset digest {asset.get('digest')} does not match the feed")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--remote", action="store_true", help="also verify each APK against its GitHub release asset")
    args = parser.parse_args()
    versions = flavor_versions((ROOT / "android/app/build.gradle").read_text(encoding="utf-8"))
    for feed, package, flavor in FEEDS:
        require(flavor in versions, f"build.gradle has no versionCode/versionName for flavor {flavor}")
        data = check_feed(ROOT / feed, package, versions[flavor])
        if args.remote:
            check_release(feed, data)
        print(f"{feed}: {package} {data['versionName']} ({data['versionCode']}); main source "
              f"{versions[flavor][1]} ({versions[flavor][0]})" + ("; release asset verified" if args.remote else ""))


if __name__ == "__main__":
    try:
        main()
    except (Invalid, OSError, ValueError, KeyError) as error:
        print("Update feed check failed: " + str(error), file=sys.stderr)
        sys.exit(1)
