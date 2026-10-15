#!/usr/bin/env bash
set -euo pipefail
helper_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk_dir="${ANDROID_SDK_ROOT:?Source toolchain/env.sh first}"
android_jar="$sdk_dir/platforms/android-35/android.jar"
build_tools="$sdk_dir/build-tools/35.0.0"
mkdir -p "$helper_dir/build/classes" "$helper_dir/build/dex"
javac -source 8 -target 8 -encoding UTF-8 -classpath "$android_jar" -d "$helper_dir/build/classes" "$helper_dir/ReadOnlyUiHierarchy.java"
(cd "$helper_dir/build/classes" && jar cf "$helper_dir/build/classes.jar" .)
"$build_tools/d8" --lib "$android_jar" --min-api 26 --output "$helper_dir/build/dex" "$helper_dir/build/classes.jar"
(cd "$helper_dir/build/dex" && jar cf "$helper_dir/ReadOnlyUiHierarchy.jar" classes.dex)
printf '%s\n' "$helper_dir/ReadOnlyUiHierarchy.jar"
