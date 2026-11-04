#!/usr/bin/env python3
"""Generate a standalone single-app repository (Voitation or Vocading) from this monorepo.

    python3 tools/split-repos/split_android.py voitation /path/to/Voitation [--init]
    python3 tools/split-repos/split_android.py vocading  /path/to/Vocading  [--init]

Phase 1 of the split: each repository keeps the shared Android source and its own Gradle
product flavor only, its own signing configuration, update feed, CI and documentation.
Package IDs, signing identities, storage paths and the internal Java package stay unchanged,
so builds from the new repository install over the existing app. Code used only by the other
app is pruned later inside each repository.

Only files tracked by git (or staged) are copied, so private keys and build output never move.
The monorepo's downloads/, historical release records, iOS project and stale CLI toolchain stay
behind; history remains in this repository.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
OWNER = "junior-rahman"

PRODUCTS = {
    "voitation": {
        "name": "Voitation", "repo": "Voitation", "flavor": "vonotation", "other": "notes",
        "package": "app.voiceflow", "signing": "preview", "gradle_variant": "VonotationPreview",
        "feed_source": "latest.json", "design": "design/voitation",
        "summary": "Dictation into any app through a floating bubble or keyboard, Quick Thoughts, and a full-text 30-day Clipboard.",
        "keystore_env": "VOICEFLOW_KEYSTORE", "password_env": "VOICEFLOW_KEYSTORE_PASSWORD / VOICEFLOW_KEY_PASSWORD",
        "certificate": "5dca2219fbe89fb885b24c904fd4c57d3b58930922d21d455fc10d7a35f7568a",
        "extra_docs": [],
    },
    "vocading": {
        "name": "Vocading", "repo": "Vocading", "flavor": "notes", "other": "vonotation",
        "package": "app.vonotation.notes", "signing": "notesPreview", "gradle_variant": "NotesPreview",
        "feed_source": "vocading-latest.json", "design": "design/vocading",
        "summary": "Voice notes and call recordings with playback, search, pins and recoverable deletion.",
        "keystore_env": "VONO_NOTES_KEYSTORE", "password_env": "VONO_NOTES_KEYSTORE_PASSWORD / VONO_NOTES_KEY_PASSWORD",
        "certificate": "b92734798f7d2642f336290beada2497ccc65c1f253511d9893499091777d317",
        "extra_docs": ["docs/VOCADING-IMPROVEMENTS.md"],
    },
}

# Historical/monorepo-only material under android/ that does not move.
ANDROID_EXCLUDE = [
    re.compile(p) for p in (
        r"android/(RELEASE_|CHANGES-FROM-|CHANGED-FILES-|RELEASE_REPORT-|VALIDATION)[^/]*\Z",
        r"android/(HANDOFF|RESUME_CHECKPOINT|RECOVERY|TOOLCHAIN_RECOVERY|VERIFICATION|FEATURE_QUEUE|README)\.md\Z",
        r"android/(CHECKPOINT_SHA256|SOURCE_PACKAGE)\.txt\Z",
        r"android/build-apk\.sh\Z",
        r"android/reference/",
        r"android/tools/",
    )
]


class SplitError(Exception):
    pass


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True).stdout
    return [p for p in out.decode().split("\0") if p]


def selected(path, spec):
    if path.startswith("android/"):
        if path.startswith(f"android/app/src/{spec['other']}/"):
            return False
        return not any(rule.search(path) for rule in ANDROID_EXCLUDE)
    if path.startswith(spec["design"] + "/"):
        return True
    return path in spec["extra_docs"]


def replace_once(text, old, new, label):
    if text.count(old) != 1:
        raise SplitError(f"{label}: expected exactly one occurrence of {old!r}")
    return text.replace(old, new)


def strip_block(text, header, label, within):
    """Remove the `name { ... }` block (balanced braces) starting at `header` inside the `within { ... }` section."""
    section = text.find(within)
    if section < 0 or text.find(within, section + 1) >= 0:
        raise SplitError(f"{label}: expected one {within!r} section")
    section_end = section + 1
    depth = 0
    for section_end in range(text.index("{", section), len(text)):
        depth += {"{": 1, "}": -1}.get(text[section_end], 0)
        if depth == 0:
            break
    start = text.find(header, section, section_end)
    if start < 0 or text.find(header, start + 1, section_end) >= 0:
        raise SplitError(f"{label}: expected one {header!r} block")
    line_start = text.rfind("\n", 0, start) + 1
    depth = 0
    for index in range(text.index("{", start), len(text)):
        depth += {"{": 1, "}": -1}.get(text[index], 0)
        if depth == 0:
            end = text.index("\n", index) + 1
            return text[:line_start] + text[end:]
    raise SplitError(f"{label}: unbalanced {header!r} block")


def transform(out, spec):
    gradle = out / "android/app/build.gradle"
    text = gradle.read_text()
    text = strip_block(text, f"        {spec['other']} {{", "build.gradle flavor", "    productFlavors {")
    other_signing = "notesPreview" if spec["signing"] == "preview" else "preview"
    text = strip_block(text, f"        {other_signing} {{", "build.gradle signing", "    signingConfigs {")
    gradle.write_text(text)

    settings = out / "android/settings.gradle"
    settings.write_text(replace_once(settings.read_text(), "rootProject.name = 'VoiceFlow'",
                                     f"rootProject.name = '{spec['name']}'", "settings.gradle"))

    feed_url = f"https://raw.githubusercontent.com/{OWNER}/{spec['repo']}/main/latest.json"
    policy = out / "android/app/src/main/java/app/voiceflow/UpdatePolicy.java"
    policy.write_text(replace_once(
        policy.read_text(),
        'static String feed(boolean notes){return "https://raw.githubusercontent.com/junior-rahman/Vonotation/main/"+(notes?"vocading-latest.json":"latest.json");}',
        f'static String feed(boolean notes){{return "{feed_url}";}} // This repository ships one app.',
        "UpdatePolicy.feed"))
    test = out / "android/tests/UpdatePolicyTest.java"
    text = test.read_text()
    for notes in (True, False):
        old = 'https://raw.githubusercontent.com/junior-rahman/Vonotation/main/' + ("vocading-latest.json" if notes else "latest.json")
        text = replace_once(text, f'equal("{old}",UpdatePolicy.feed({str(notes).lower()}));',
                            f'equal("{feed_url}",UpdatePolicy.feed({str(notes).lower()}));', "UpdatePolicyTest")
    test.write_text(text)

    ci = out / "android/scripts/run-visual-ci.sh"
    ci.write_text(replace_once(ci.read_text(), '"${VONO_PRODUCTS:-vonotation notes}"',
                               f'"${{VONO_PRODUCTS:-{spec["flavor"]}}}"', "run-visual-ci.sh"))
    build = out / "android/scripts/build-split.sh"
    text = build.read_text()
    text = replace_once(text, """case "$task_product" in both) task_products=(vonotation notes) ;; vonotation|notes) task_products=("$task_product") ;; *) echo 'Unknown product' >&2; exit 2 ;; esac""",
                        f"""case "$task_product" in both|{spec['flavor']}) task_products=({spec['flavor']}) ;; *) echo 'This repository builds only {spec['flavor']}' >&2; exit 2 ;; esac""",
                        "build-split.sh products")
    build.write_text(text)


def write_root_files(out, spec, source_commit):
    feed = json.loads((ROOT / spec["feed_source"]).read_text())
    (out / "latest.json").write_text(json.dumps(feed, indent=2, ensure_ascii=False) + "\n")
    (out / ".gitignore").write_text("__pycache__/\n*.pyc\n.DS_Store\n")

    validator = (ROOT / ".github/scripts/validate_update_feeds.py").read_text()
    validator = re.sub(r"FEEDS = \(\n.*?\n\)\n", "FEEDS = (\n    # feed file, Android package, Gradle product flavor\n"
                       f"    (\"latest.json\", \"{spec['package']}\", \"{spec['flavor']}\"),\n)\n", validator, count=1, flags=re.S)
    if '"vocading-latest.json"' in validator or f'"{spec["package"]}", "{spec["flavor"]}"' not in validator:
        raise SplitError("validate_update_feeds.py FEEDS block was not rewritten")
    scripts = out / ".github/scripts"
    scripts.mkdir(parents=True, exist_ok=True)
    (scripts / "validate_update_feeds.py").write_text(validator)

    workflows = out / ".github/workflows"
    workflows.mkdir(parents=True, exist_ok=True)
    checks = (ROOT / ".github/workflows/checks.yml").read_text()
    checks = checks.split("      - name: Legacy release manifests (offline)")[0].rstrip() + "\n"
    (workflows / "checks.yml").write_text(checks)
    native = (ROOT / ".github/workflows/android-visual-ci.yml").read_text()
    native = native.replace("name: Android native split-app verification", f"name: {spec['name']} Android native verification")
    native = native.replace("VonoTation-Android-split-native-", f"{spec['name']}-Android-native-")
    other_variant = "NotesPreview" if spec["flavor"] == "vonotation" else "VonotationPreview"
    native = "\n".join(line for line in native.split("\n") if f"lint-results-{other_variant[0].lower() + other_variant[1:]}" not in line)
    native = native.replace("Build both apps, lint, isolation and light/dark native verification",
                            f"Build {spec['name']}, lint and light/dark native verification")
    (workflows / "android-native-ci.yml").write_text(native)

    readme = f"""# {spec['name']}

{spec['summary']}

Android 8.0 or later. Package `{spec['package']}`, current release {feed['versionName']} (version code {feed['versionCode']}).

## Origin

Split from [`{OWNER}/Vonotation`](https://github.com/{OWNER}/Vonotation) at
[`{source_commit[:7]}`](https://github.com/{OWNER}/Vonotation/commit/{source_commit}); earlier history and every
release up to {feed['versionName']} live there. The package ID, signing certificate, storage paths and the
internal Java package `app.voiceflow` are unchanged, so builds from this repository install over the existing app.
Never uninstall the existing app to work around a signing problem.

## Build

JDK 17, Android SDK 35 and Build Tools 35.0.0.

```sh
cd android
bash scripts/build-split.sh --unsigned    # build + lint, no signing key needed
bash tests/run-pure-tests.sh              # Android-free Java suites
```

Release signing needs the existing private key through `{spec['keystore_env']}` (passwords via
`{spec['password_env']}`). Certificate SHA-256: `{spec['certificate']}`. The legacy default password is public:
re-protect the keystore with `keytool -storepasswd` and `-keypasswd` (this changes only the file's protection,
not the certificate). Keys, API credentials and recordings never belong in this repository.

## Updates and the transition from Vonotation

Settings → Update reads `latest.json` from this repository's `main`. Installed builds up to {feed['versionName']}
still read the feed in `{OWNER}/Vonotation`. For the first release from this repository:

1. Bump `versionCode` above {feed['versionCode']}, publish the signed APK as a release here and update this `latest.json`.
2. Point the matching feed in `{OWNER}/Vonotation` at the same release asset, so existing installs pick up a build
   that reads this repository's feed from then on.

`.github/workflows/checks.yml` keeps `latest.json` honest: it may never offer a version newer than `main`'s source,
and its APK must match the published release asset's size and SHA-256.

## Status

Phase 1 of the split: this repository still contains shared source used only by the other app. It is unreachable
in this app (its components are removed from the manifest and its screens are not offered) and will be pruned here.
"""
    (out / "README.md").write_text(readme)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("product", choices=sorted(PRODUCTS))
    parser.add_argument("out", type=Path)
    parser.add_argument("--init", action="store_true", help="git init the output and create the initial commit")
    parser.add_argument("--trailer", action="append", default=[], help="line appended to the initial commit message (repeatable)")
    args = parser.parse_args()
    spec = PRODUCTS[args.product]
    out = args.out.resolve()
    if out.exists() and any(out.iterdir()):
        raise SplitError(f"{out} must be empty or absent")
    if ROOT == out or ROOT in out.parents:
        raise SplitError("Write the new repository outside this checkout")
    source_commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    if dirty.strip():
        raise SplitError("Commit tracked changes first so the new repository matches a real commit")
    copied = 0
    for path in tracked_files():
        if not selected(path, spec):
            continue
        source, target = ROOT / path, out / PurePosixPath(path)
        if source.is_symlink() or not source.is_file():
            raise SplitError(f"Refusing non-regular file {path}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        copied += 1
    transform(out, spec)
    write_root_files(out, spec, source_commit)
    print(f"{spec['name']}: {copied} files from Vonotation@{source_commit[:12]} -> {out}")
    if args.init:
        subprocess.run(["git", "init", "-q", "-b", "main"], cwd=out, check=True)
        subprocess.run(["git", "add", "-A"], cwd=out, check=True)
        message = (f"Import {spec['name']} Android from Vonotation@{source_commit[:12]}\n\n"
                   f"Generated by tools/split-repos/split_android.py from {OWNER}/Vonotation commit\n"
                   f"{source_commit}. History before the split remains in that repository.\n")
        if args.trailer:
            message += "\n" + "\n".join(args.trailer) + "\n"
        subprocess.run(["git", "commit", "-q", "-m", message], cwd=out, check=True)
        print("Initial commit created on main")


if __name__ == "__main__":
    try:
        main()
    except (SplitError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print("Split stopped: " + str(error), file=sys.stderr)
        sys.exit(1)
