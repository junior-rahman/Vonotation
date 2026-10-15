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
fixture_dir="$project_dir/build/tests/input-regressions"
signing_file="${VOICEFLOW_KEYSTORE:-$project_dir/preview-signing/voiceflow.keystore}"
app_apk="${VOICEFLOW_APP_APK:-$project_dir/app/build/outputs/apk/preview/VoiceFlow-1.9.10.apk}"
output="$fixture_dir/VoiceFlow-input-regressions-fixture.apk"

for required in "$android_jar" "$app_classes/app/voiceflow/FlowKeyboard.class" \
    "$app_classes/app/voiceflow/FlowAccessibility.class" "$app_classes/app/voiceflow/Store.class" \
    "$app_classes/app/voiceflow/Dictation.class" "$signing_file" "$app_apk"; do
    [[ -f "$required" ]] || { echo "Missing input: $required. Build the current preview app first." >&2; exit 1; }
done
for tool in aapt2 d8 zipalign apksigner; do
    [[ -x "$build_tools/$tool" ]] || { echo "Missing Android build tool: $build_tools/$tool" >&2; exit 1; }
done
command -v java >/dev/null || { echo 'A Java compiler runtime is required.' >&2; exit 1; }
command -v zip >/dev/null || { echo 'The zip command is required.' >&2; exit 1; }

if [[ -n "${ECJ_JAR:-}" ]]; then
    [[ -f "$ECJ_JAR" ]] || { echo 'ECJ_JAR must name an existing compiler JAR.' >&2; exit 1; }
    compiler=(java -jar "$ECJ_JAR")
elif command -v javac >/dev/null; then
    compiler=(javac)
elif java --describe-module jdk.compiler >/dev/null 2>&1; then
    compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
else
    echo 'Install a JDK, or explicitly set ECJ_JAR to your Eclipse compiler JAR.' >&2
    exit 1
fi

# Use current regression sources. Production classes are classpath-only.
sources=(ControlSetup EditorActivity FakeSpeech KeyboardStateTest AuthFieldTest \
    InputRecoveryTest ComposeDeleteTest ApiRequestTest RecoveryTest)
source_files=()
for source in "${sources[@]}"; do source_files+=("$project_dir/tests/fixtures/$source.java"); done
rm -rf "$fixture_dir/classes" "$fixture_dir/dex"
rm -f "$fixture_dir/classes.jar" "$fixture_dir/app-classpath.jar"
mkdir -p "$fixture_dir/classes" "$fixture_dir/dex"
"$build_tools/aapt2" link --manifest "$project_dir/tests/input-regressions/AndroidManifest.xml" \
    -I "$android_jar" -o "$fixture_dir/resources.apk" --min-sdk-version 26 --target-sdk-version 35
"${compiler[@]}" -source 8 -target 8 -encoding UTF-8 \
    -classpath "$android_jar:$app_classes" -d "$fixture_dir/classes" "${source_files[@]}"

# Fail if any application implementation accidentally becomes a program input.
while IFS= read -r -d '' generated; do
    relative="${generated#"$fixture_dir/classes/"}"
    case "$relative" in
        app/voiceflow/ControlSetup.class|app/voiceflow/ApiRequestTest.class|app/voiceflow/ApiRequestTest\$*.class|\
        app/voiceflow/RecoveryTest.class|app/voiceflow/RecoveryTest\$*.class|\
        app/voiceflow/fixture/EditorActivity.class|app/voiceflow/fixture/EditorActivity\$*.class|\
        app/voiceflow/fixture/FakeSpeech.class|app/voiceflow/fixture/FakeSpeech\$*.class|\
        app/voiceflow/fixture/KeyboardStateTest.class|app/voiceflow/fixture/KeyboardStateTest\$*.class|\
        app/voiceflow/fixture/AuthFieldTest.class|app/voiceflow/fixture/AuthFieldTest\$*.class|\
        app/voiceflow/fixture/InputRecoveryTest.class|app/voiceflow/fixture/InputRecoveryTest\$*.class|\
        app/voiceflow/fixture/ComposeDeleteTest.class|app/voiceflow/fixture/ComposeDeleteTest\$*.class) ;;
        *) echo "Unexpected class in fixture output: $relative" >&2; exit 1 ;;
    esac
done < <(find "$fixture_dir/classes" -type f -name '*.class' -print0)
(cd "$fixture_dir/classes" && zip -q -r "$fixture_dir/classes.jar" . -i '*.class')
(cd "$app_classes" && zip -q -r "$fixture_dir/app-classpath.jar" . -i '*.class')
"$build_tools/d8" --lib "$android_jar" --classpath "$fixture_dir/app-classpath.jar" \
    --min-api 26 --output "$fixture_dir/dex" "$fixture_dir/classes.jar"
cp "$fixture_dir/resources.apk" "$fixture_dir/unsigned.apk"
(cd "$fixture_dir/dex" && zip -q "$fixture_dir/unsigned.apk" classes*.dex)
"$build_tools/zipalign" -f -p 4 "$fixture_dir/unsigned.apk" "$fixture_dir/aligned.apk"
"$build_tools/apksigner" sign --ks "$signing_file" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias voiceflow --out "$output" "$fixture_dir/aligned.apk"
"$build_tools/apksigner" verify --print-certs "$output" > "$fixture_dir/fixture-signature.txt"
"$build_tools/apksigner" verify --print-certs "$app_apk" > "$fixture_dir/app-signature.txt"
fixture_certificate="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$fixture_dir/fixture-signature.txt")"
app_certificate="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$fixture_dir/app-signature.txt")"
[[ -n "$fixture_certificate" && "$fixture_certificate" == "$app_certificate" ]] || {
    echo 'Fixture signer does not match the current preview app.' >&2; exit 1;
}
"$build_tools/zipalign" -c -p 4 "$output"
sha256sum "$output" "$app_apk" "${source_files[@]}" > "$fixture_dir/inputs-sha256.txt"
printf 'Compiled %s fixture sources; signer matches current preview app.\n%s\n' "${#sources[@]}" "$output"
