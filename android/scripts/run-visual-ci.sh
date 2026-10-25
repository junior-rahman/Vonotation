#!/usr/bin/env bash
# Both split applications: real Android SDK/emulator checks. No release credentials.
set -euo pipefail
cd "$(dirname "$0")/.."
task_root="$PWD"
task_output="$task_root/build/visual-ci"
rm -rf "$task_output"
mkdir -p "$task_output/logs" "$task_output/screenshots"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?An Android SDK is required}}"
export JAVA_HOME="${JAVA_HOME_17_X64:-${JAVA_HOME:?JDK 17 is required}}"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"
task_temp="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/vonotation-visual.XXXXXX")"
export VOICEFLOW_KEYSTORE="$task_temp/disposable-test.keystore"
export VONO_NOTES_KEYSTORE="$task_temp/disposable-notes-test.keystore"
export VONO_NOTES_KEYSTORE_PASSWORD=android VONO_NOTES_KEY_PASSWORD=android
task_emulator_pid=""
task_serial=emulator-5588
export ANDROID_SERIAL="$task_serial"
# Never let a developer's connected phone become an implicit adb target.
adb() { command "$ANDROID_SDK_ROOT/platform-tools/adb" -s "$task_serial" "$@"; }
finish_visual() {
  task_status=$?
  trap - EXIT
  set +e
  if [[ -n "$task_emulator_pid" ]] && kill -0 "$task_emulator_pid" 2>/dev/null; then
    timeout 10 "$ANDROID_SDK_ROOT/platform-tools/adb" -s "$task_serial" logcat -d -v threadtime > "$task_output/logs/logcat-final.txt" 2>&1
    if [[ "$task_status" != 0 ]]; then timeout 10 "$ANDROID_SDK_ROOT/platform-tools/adb" -s "$task_serial" exec-out screencap -p > "$task_output/failure-current.png"; fi
    timeout 10 "$ANDROID_SDK_ROOT/platform-tools/adb" -s "$task_serial" emu kill >/dev/null 2>&1
    kill "$task_emulator_pid" 2>/dev/null
  fi
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
keytool -genkeypair -keystore "$VONO_NOTES_KEYSTORE" -storepass android -keypass android -alias voiceflow -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Disposable Vocading native QA' >> "$task_output/logs/disposable-signing.log" 2>&1
bash gradlew --no-daemon assembleVonotationPreview lintVonotationPreview assembleNotesPreview lintNotesPreview 2>&1 | tee "$task_output/logs/build-lint.log"
bash tests/run-pure-tests.sh 2>&1 | tee "$task_output/logs/pure-tests.log"
for task_product in vonotation notes; do
  mkdir -p "$task_output/$task_product/logs" "$task_output/$task_product/screenshots"
  bash tests/build-visual-fixture.sh "$task_product" 2>&1 | tee "$task_output/$task_product/logs/fixture-build.log"
  task_apk="app/build/outputs/apk/$task_product/preview/app-$task_product-preview.apk"
  "$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" verify --print-certs "$task_apk" > "$task_output/$task_product/test-apk-certificate.txt"
  sha256sum "$task_apk" "build/tests/visual/$task_product/$task_product-visual-fixture.apk" > "$task_output/$task_product/test-apk-sha256.txt"
done
python3 - "$task_output" <<'PY'
from pathlib import Path
import re,sys
root=Path(sys.argv[1]); certificates=[]
for product in ('vonotation','notes'):
    text=(root/product/'test-apk-certificate.txt').read_text()
    digest=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',text)
    if not digest: raise SystemExit('Missing QA signer identity for '+product)
    certificates.append(digest.group(1))
if certificates[0]==certificates[1]: raise SystemExit('Split apps must have independent QA signer fixtures')
PY
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
if adb get-state >/dev/null 2>&1; then printf '%s\n' 'Reserved disposable emulator port is already in use; refusing to touch that installation.' >&2; exit 2; fi
emulator -avd vonotation_visual -port 5588 -no-window -noaudio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on -camera-back none -camera-front none > "$task_output/logs/emulator.log" 2>&1 &
task_emulator_pid=$!
task_connected=false
for task_attempt in $(seq 1 90); do
  kill -0 "$task_emulator_pid" || { cat "$task_output/logs/emulator.log"; exit 1; }
  if [[ "$(adb get-state 2>/dev/null)" == device ]]; then task_connected=true; break; fi
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
for task_product in vonotation notes; do
  if [[ "$task_product" == notes ]]; then task_package=app.vonotation.notes; else task_package=app.voiceflow; fi
  adb install --no-incremental "app/build/outputs/apk/$task_product/preview/app-$task_product-preview.apk"
  adb install --no-incremental "build/tests/visual/$task_product/$task_product-visual-fixture.apk"
  if [[ "$task_product" == notes ]]; then
    adb shell pm grant "$task_package" android.permission.RECORD_AUDIO
    adb shell pm grant "$task_package" android.permission.POST_NOTIFICATIONS
  fi
  adb shell dumpsys package "$task_package" > "$task_output/$task_product/installed-package.txt"
done
adb logcat -c
for task_product in vonotation notes; do
  if [[ "$task_product" == notes ]]; then task_package=app.vonotation.notes; else task_package=app.voiceflow; fi
  for task_appearance in light dark; do
  # A brand-new target app data directory each time; no user data or credentials exist.
  adb shell am force-stop "$task_package"
  adb shell pm clear "$task_package"
  if [[ "$task_product" == notes ]]; then
    adb shell pm grant "$task_package" android.permission.RECORD_AUDIO
    adb shell pm grant "$task_package" android.permission.POST_NOTIFICATIONS
  fi
  if [[ "$task_appearance" == dark ]]; then adb shell cmd uimode night yes; else adb shell cmd uimode night no; fi
  adb shell cmd uimode night > "$task_output/$task_product/uimode-$task_appearance.txt"
  timeout 120 "$ANDROID_SDK_ROOT/platform-tools/adb" -s "$task_serial" shell am instrument -w -e product "$task_product" -e appearance "$task_appearance" "$task_package.visual.fixture/app.voiceflow.VisualPaletteTest" > "$task_output/$task_product/logs/instrumentation-$task_appearance.txt" 2>&1
  python3 - "$task_output/$task_product/logs/instrumentation-$task_appearance.txt" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text()
if 'INSTRUMENTATION_RESULT: result=PASS:' not in text or 'INSTRUMENTATION_RESULT: failure=' in text or 'INSTRUMENTATION_CODE: -1' not in text:
    print(text)
    raise SystemExit('Native visual fixture did not pass')
print(text)
PY
  adb pull "/sdcard/Android/media/$task_package/visual-$task_appearance" "$task_output/$task_product/screenshots/"
  done
done
adb logcat -d -v threadtime > "$task_output/logs/logcat-verified-interval.txt"
python3 - "$task_output" <<'PY'
import hashlib, json, os, struct, sys, zlib
from pathlib import Path
root = Path(sys.argv[1]); screenshots = {}
products = {'vonotation': ('dictations', 'thoughts', 'clipboard', 'settings', 'settings-size', 'settings-cleanup', 'settings-dictionary', 'settings-permissions'), 'notes': ('voice-notes', 'calls', 'settings', 'recording-active', 'recording-paused')}
for product, screens in products.items():
  for appearance in ('light', 'dark'):
    for screen in screens:
        path = root / product / 'screenshots' / ('visual-' + appearance) / (screen + '.png')
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
if 'FATAL EXCEPTION' in logs and any('Process: ' + package in logs for package in ('app.voiceflow', 'app.vonotation.notes')): raise SystemExit('Target process crash found; inspect logcat')
receipt = {'commit': os.environ.get('GITHUB_SHA', 'local'), 'checks': {'both_preview_builds': 'passed', 'both_full_lints': 'passed', 'pure_java_suites': 'passed', 'both_products_native_light_dark': 'passed', 'manifest_and_card_isolation': 'passed', 'native_resource_and_rendered_capture_contrast': 'passed', 'legacy_recording_retention': 'passed', 'synthetic_recording_archive_roundtrips': 'passed', 'vocading_edit_pin_trash_restore_share': 'passed', 'vocading_native_audio_seek_speed_pause_resume': 'passed'}, 'applications': {'vonotation': 'app.voiceflow', 'notes': 'app.vonotation.notes'}, 'screenshots': screenshots, 'qualification': 'Actual split-product native emulator screenshots with synthetic QA data. Cards selected through production saved-state restore. One physical Settings tap per product/theme. Native synthetic PCM playback and emulator microphone pause/resume were exercised; these do not establish real document-picker behavior, microphone quality or call capture. Independent disposable QA certificates are unrelated to release signing. Exported application artifacts are UNSIGNED and are not installable updates.', 'not_tested': ['Physical Android hardware', 'Live provider calls', 'Google sign-in or backup restore', 'Full recording and cross-app dictation flows', 'Live telephony', 'User recording transfer through SAF picker and real-source playback']}
(root / 'VERIFICATION.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps({'native_screenshots': len(screenshots), 'checks': receipt['checks']}, indent=2))
PY
# Export application payloads only after every native check above succeeded.
for task_product in vonotation notes; do
  python3 scripts/prepare-unsigned.py "app/build/outputs/apk/$task_product/preview/app-$task_product-preview.apk" "$task_output/unsigned/$task_product-UNSIGNED.apk" --zipalign "$ANDROID_SDK_ROOT/build-tools/35.0.0/zipalign"
done
printf '%s\n' 'These APKs are UNSIGNED and cannot be installed. They contain the exact verified DEX/resource payload but require each app owner’s production signing key. Disposable QA signatures are never promoted as updates. Voitation must retain its old signer to update the installed app. Do not uninstall an existing app.' > "$task_output/unsigned/README.txt"

# Public Android SDK utilities only; no signing keys or credentials leave the runner.
mkdir -p "$task_output/sdk-tools"
cp "$ANDROID_SDK_ROOT/build-tools/35.0.0/lib/apksigner.jar" "$task_output/sdk-tools/"
cp "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2" "$task_output/sdk-tools/"
