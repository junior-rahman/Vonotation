#!/usr/bin/env bash
# Real Android SDK/emulator screenshots, never reference renders. No release credentials.
set -euo pipefail
cd "$(dirname "$0")/.."
task_root="$PWD"
task_output="$task_root/build/visual-ci"
mkdir -p "$task_output/logs" "$task_output/screenshots"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?An Android SDK is required}}"
export JAVA_HOME="${JAVA_HOME_17_X64:-${JAVA_HOME:?JDK 17 is required}}"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"
task_temp="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/vonotation-visual.XXXXXX")"
export VOICEFLOW_KEYSTORE="$task_temp/disposable-test.keystore"
task_emulator_pid=""
finish_visual() {
  task_status=$?
  trap - EXIT
  set +e
  timeout 10 adb logcat -d -v threadtime > "$task_output/logs/logcat-final.txt" 2>&1
  if [[ "$task_status" != 0 ]]; then timeout 10 adb exec-out screencap -p > "$task_output/failure-current.png"; fi
  if [[ -n "$task_emulator_pid" ]]; then timeout 10 adb emu kill >/dev/null 2>&1; kill "$task_emulator_pid" 2>/dev/null; fi
  rm -rf "$task_temp"
  exit "$task_status"
}
trap finish_visual EXIT
command -v sdkmanager >/dev/null
java -version 2> "$task_output/java-version.txt"
sdkmanager --version > "$task_output/sdkmanager-version.txt"
# Hosted runner supplies the SDK licenses. Only official Google SDK components are installed.
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'emulator' 'system-images;android-35;default;x86_64' 2>&1 | tee "$task_output/logs/sdk-install.log"
keytool -genkeypair -keystore "$VOICEFLOW_KEYSTORE" -storepass android -keypass android -alias voiceflow -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Disposable native palette QA' > "$task_output/logs/disposable-signing.log" 2>&1
bash gradlew --no-daemon assemblePreview lintPreview 2>&1 | tee "$task_output/logs/build-lint.log"
bash tests/run-pure-tests.sh 2>&1 | tee "$task_output/logs/pure-tests.log"
bash tests/build-visual-fixture.sh 2>&1 | tee "$task_output/logs/fixture-build.log"
"$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/preview/app-preview.apk > "$task_output/test-apk-certificate.txt"
sha256sum app/build/outputs/apk/preview/app-preview.apk build/tests/visual/VonoTation-visual-fixture.apk > "$task_output/test-apk-sha256.txt"
[[ -e /dev/kvm ]] || { printf '%s\n' 'Native Android verification requires KVM on the hosted runner.' >&2; exit 2; }
[[ -r /dev/kvm && -w /dev/kvm ]] || sudo chmod 666 /dev/kvm
# avdmanager and emulator may inherit different default user-directory conventions.
# Give both tools the same explicit disposable registry and device directory.
export ANDROID_USER_HOME="$task_temp/android-user"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
# Palette checks do not need Google services. The stock AOSP image avoids
# first-boot Google launcher/account work obscuring the target Activity.
printf 'no\n' | avdmanager create avd --name vonotation_visual --path "$ANDROID_AVD_HOME/vonotation_visual.avd" --package 'system-images;android-35;default;x86_64' --device pixel_6 --force > "$task_output/logs/avd-create.log" 2>&1
emulator -list-avds > "$task_output/available-avds.txt"
[[ -s "$ANDROID_AVD_HOME/vonotation_visual.ini" ]] || { cat "$task_output/logs/avd-create.log" "$task_output/available-avds.txt"; printf '%s\n' 'The native emulator AVD registry was not created.' >&2; exit 1; }
emulator -avd vonotation_visual -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on -camera-back none -camera-front none > "$task_output/logs/emulator.log" 2>&1 &
task_emulator_pid=$!
task_connected=false
for task_attempt in $(seq 1 90); do
  if [[ "$(adb get-state 2>/dev/null)" == device ]]; then task_connected=true; break; fi
  kill -0 "$task_emulator_pid" || { cat "$task_output/logs/emulator.log"; exit 1; }
  sleep 2
done
[[ "$task_connected" == true ]] || { cat "$task_output/logs/emulator.log"; printf '%s\n' 'Native emulator connection timed out.' >&2; exit 1; }
task_booted=false
for task_attempt in $(seq 1 90); do
  if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then task_booted=true; break; fi
  kill -0 "$task_emulator_pid" || { cat "$task_output/logs/emulator.log"; exit 1; }
  sleep 2
done
[[ "$task_booted" == true ]] || { printf '%s\n' 'Native emulator boot timed out.' >&2; exit 1; }
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell getprop > "$task_output/emulator-properties.txt"
adb shell wm size > "$task_output/emulator-display.txt"
adb shell wm density >> "$task_output/emulator-display.txt"
adb install --no-incremental app/build/outputs/apk/preview/app-preview.apk
adb install --no-incremental build/tests/visual/VonoTation-visual-fixture.apk
adb shell dumpsys package app.voiceflow > "$task_output/installed-package.txt"
adb logcat -c
for task_appearance in light dark; do
  # A brand-new target app data directory each time; no user data or credentials exist.
  adb shell am force-stop app.voiceflow
  adb shell pm clear app.voiceflow
  if [[ "$task_appearance" == dark ]]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  adb shell cmd uimode night > "$task_output/uimode-$task_appearance.txt"
  timeout 120 adb shell am instrument -w -e appearance "$task_appearance" app.voiceflow.visual.fixture/app.voiceflow.VisualPaletteTest > "$task_output/logs/instrumentation-$task_appearance.txt" 2>&1
  python3 - "$task_output/logs/instrumentation-$task_appearance.txt" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text()
if 'INSTRUMENTATION_RESULT: result=PASS:' not in text or 'INSTRUMENTATION_RESULT: failure=' in text or 'INSTRUMENTATION_CODE: -1' not in text:
    print(text)
    raise SystemExit('Native visual fixture did not pass')
print(text)
PY
  adb pull "/sdcard/Android/media/app.voiceflow/visual-$task_appearance" "$task_output/screenshots/"
done
adb logcat -d -v threadtime > "$task_output/logs/logcat-verified-interval.txt"
python3 - "$task_output" <<'PY'
import hashlib, json, os, struct, sys, zlib
from pathlib import Path
root = Path(sys.argv[1]); screenshots = {}
for appearance in ('light', 'dark'):
    for screen in ('home', 'dictations', 'thoughts', 'clipboard', 'settings'):
        path = root / 'screenshots' / ('visual-' + appearance) / (screen + '.png')
        data = path.read_bytes()
        if data[:8] != b'\x89PNG\r\n\x1a\n': raise SystemExit('Invalid native screenshot: ' + str(path))
        position = 8; found_end = False
        while position < len(data):
            size = struct.unpack('>I', data[position:position+4])[0]
            chunk = data[position+4:position+8]; body = data[position+8:position+8+size]
            crc = struct.unpack('>I', data[position+8+size:position+12+size])[0]
            if zlib.crc32(chunk + body) & 0xffffffff != crc: raise SystemExit('Screenshot CRC mismatch')
            position += 12 + size
            if chunk == b'IEND': found_end = True; break
        if not found_end or position != len(data): raise SystemExit('Truncated native screenshot')
        width, height = struct.unpack('>II', data[16:24])
        if width < 300 or height < 500: raise SystemExit('Native screenshot dimensions too small')
        screenshots[str(path.relative_to(root))] = {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'width': width, 'height': height}
logs = (root / 'logs/logcat-verified-interval.txt').read_text(errors='replace')
if 'Process: app.voiceflow' in logs and 'FATAL EXCEPTION' in logs: raise SystemExit('Target process crash found; inspect logcat')
receipt = {'commit': os.environ.get('GITHUB_SHA', 'local'), 'checks': {'preview_build': 'passed', 'preview_lint': 'passed', 'native_visual_fixture_light': 'passed', 'native_visual_fixture_dark': 'passed'}, 'screenshots': screenshots, 'qualification': 'Actual native emulator screenshots with synthetic QA data. Cards selected through production saved-state restore. One physical Settings tap per theme. Disposable certificate is unrelated to release signing.', 'not_tested': ['Physical Android hardware', 'Live provider calls', 'Google sign-in or backup restore', 'Full card navigation and recording flows']}
(root / 'VERIFICATION.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps({'native_screenshots': len(screenshots), 'checks': receipt['checks']}, indent=2))
PY
