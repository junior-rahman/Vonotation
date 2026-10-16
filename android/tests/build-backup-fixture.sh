#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_dir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_dir" ]]; then
    echo 'Set ANDROID_SDK_ROOT or ANDROID_HOME to your Android SDK directory.' >&2
    exit 1
fi
sdk_dir="$(cd "$sdk_dir" && pwd)"
build_tools="$sdk_dir/build-tools/35.0.0"
android_jar="$sdk_dir/platforms/android-35/android.jar"
app_classes="$project_dir/app/build/intermediates/javac/preview/compilePreviewJavaWithJavac/classes"
fixture_dir="$project_dir/build/tests/backup"
signing_file="${VOICEFLOW_KEYSTORE:-$project_dir/preview-signing/voiceflow.keystore}"

for required in "$android_jar" "$app_classes/app/voiceflow/BackupData.class" \
    "$app_classes/app/voiceflow/BackupArchive.class" "$signing_file"; do
    if [[ ! -f "$required" ]]; then
        echo "Missing required input: $required. Build the preview app first." >&2
        exit 1
    fi
done
for tool in aapt2 d8 zipalign apksigner; do
    if [[ ! -x "$build_tools/$tool" ]]; then
        echo "Missing Android build tool: $build_tools/$tool" >&2
        exit 1
    fi
done
command -v java >/dev/null || { echo 'Java 17 is required.' >&2; exit 1; }
command -v zip >/dev/null || { echo 'The zip command is required.' >&2; exit 1; }

if [[ -n "${ECJ_JAR:-}" ]]; then
    [[ -f "$ECJ_JAR" ]] || { echo 'ECJ_JAR must name an existing compiler JAR.' >&2; exit 1; }
    compiler=(java -jar "$ECJ_JAR")
elif command -v javac >/dev/null; then
    compiler=(javac)
elif java --describe-module jdk.compiler >/dev/null 2>&1; then
    # Some Java 17 installations include javac's module without a javac launcher.
    compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
else
    echo 'Install JDK 17, or explicitly set ECJ_JAR to your Eclipse compiler JAR.' >&2
    exit 1
fi

rm -rf "$fixture_dir/classes" "$fixture_dir/dex"
rm -f "$fixture_dir/classes.jar" "$fixture_dir/app-classpath.jar"
mkdir -p "$fixture_dir/classes" "$fixture_dir/dex"
"$build_tools/aapt2" link --manifest "$project_dir/tests/backup/AndroidManifest.xml" \
    -I "$android_jar" -o "$fixture_dir/resources.apk" --min-sdk-version 26 --target-sdk-version 35
"${compiler[@]}" -source 8 -target 8 -encoding UTF-8 \
    -classpath "$android_jar:$app_classes" -d "$fixture_dir/classes" \
    "$project_dir/tests/fixtures/BackupCredentialPolicyTest.java" \
    "$project_dir/tests/fixtures/BackupArchiveTest.java" \
    "$project_dir/tests/fixtures/BackupHistoryDeletionTest.java" \
    "$project_dir/tests/fixtures/BackupRestoreBusyTest.java" \
    "$project_dir/tests/fixtures/ThoughtStorageTest.java"
(cd "$fixture_dir/classes" && zip -q -r "$fixture_dir/classes.jar" . -i '*.class')
(cd "$app_classes" && zip -q -r "$fixture_dir/app-classpath.jar" . -i '*.class')

# Production code is classpath-only: the fixture APK contains only the tests.
"$build_tools/d8" --lib "$android_jar" --classpath "$fixture_dir/app-classpath.jar" \
    --min-api 26 --output "$fixture_dir/dex" "$fixture_dir/classes.jar"
cp "$fixture_dir/resources.apk" "$fixture_dir/unsigned.apk"
(cd "$fixture_dir/dex" && zip -q "$fixture_dir/unsigned.apk" classes*.dex)
"$build_tools/zipalign" -f -p 4 "$fixture_dir/unsigned.apk" "$fixture_dir/aligned.apk"
"$build_tools/apksigner" sign --ks "$signing_file" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias voiceflow \
    --out "$fixture_dir/VoiceFlow-backup-fixture.apk" "$fixture_dir/aligned.apk"
"$build_tools/apksigner" verify "$fixture_dir/VoiceFlow-backup-fixture.apk"
printf '%s\n' "$fixture_dir/VoiceFlow-backup-fixture.apk"
