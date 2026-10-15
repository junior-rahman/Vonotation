#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")" && pwd)"
android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$android_sdk" ]]; then echo 'Set ANDROID_SDK_ROOT to your Android SDK path.' >&2; exit 1; fi
signing_file="${VOICEFLOW_KEYSTORE:-$project_dir/preview-signing/voiceflow.keystore}"
if [[ ! -f "$signing_file" ]]; then
    echo 'The VoiceFlow preview signing certificate is missing. Restore it before building.' >&2
    exit 1
fi
ANDROID_SDK_ROOT="$android_sdk" "$project_dir/gradlew" -p "$project_dir" assemblePreview
mkdir -p "$project_dir/build"
cp "$project_dir/app/build/outputs/apk/preview/app-preview.apk" "$project_dir/build/VoiceFlow.apk"
"$android_sdk/build-tools/35.0.0/apksigner" verify "$project_dir/build/VoiceFlow.apk"
echo "$project_dir/build/VoiceFlow.apk"
