#!/usr/bin/env bash
# Android's SDK provides compile-time org.json types only. This suite executes
# production crypto and its shared JSON guard; native schema tests are separate.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk_dir" && -f "$sdk_dir/platforms/android-35/android.jar" ]] || { echo 'Set ANDROID_SDK_ROOT to an SDK containing android-35.' >&2; exit 1; }
out="${1:-$root/app/build/backup-archive-jvm}"
mkdir -p "$out"
compiler=(javac)
if ! command -v javac >/dev/null 2>&1; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
src="$root/app/src/main/java/app/voiceflow"
"${compiler[@]}" -encoding UTF-8 --release 8 -classpath "$sdk_dir/platforms/android-35/android.jar" -d "$out" \
    "$src/BackupJsonBounds.java" "$src/BackupArchive.java" \
    "$root/tests/BackupJsonBoundsTest.java" "$root/tests/jvm/BackupCryptoRoundTripTest.java"
java -ea -cp "$out:$sdk_dir/platforms/android-35/android.jar" app.voiceflow.BackupCryptoRoundTripTest
