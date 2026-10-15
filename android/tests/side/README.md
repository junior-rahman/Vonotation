# Floating control and IME fixture

`SidePreview` checks the current single-bubble controls, tap/hold/drag, keyboard persistence, synthetic speech insertion, ordinary typing keys, password keyboard behavior, and hiding after input focus ends. `ControlSetup` configures the isolated app; `EditorActivity` provides editable fields; `FakeSpeech` produces local recognition callbacks. This verifies neither real microphone audio nor transcription accuracy, Google sign-in, or Drive access.

Use Java 17, Android SDK platform/build-tools 35.0.0, and `zip`. Build the preview app and fixture from the project root:

```bash
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
bash build-apk.sh
bash tests/build-side-fixture.sh
```

`ANDROID_HOME` is also accepted; an explicit `ECJ_JAR=/absolute/path/to/ecj.jar` selects Eclipse's compiler. The helper derives its project paths and uses the included preview signer. Only the four fixture sources are dex inputs; Gradle's production classes are classpath-only.

Use a **disposable API 35 AOSP AVD** named `VoiceFlowSideTest`, with its ordinary keyboard still enabled. Start it and wait for boot on an explicit serial:

```bash
"$ANDROID_SDK_ROOT/emulator/emulator" -avd VoiceFlowSideTest -port 5580 \
    -read-only -no-snapshot -no-window -no-audio -gpu swiftshader_indirect &
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 wait-for-device
until [[ "$("$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do sleep 1; done
```

Then install and configure only that emulator:

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 install --no-incremental -r build/VoiceFlow.apk
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 install --no-incremental -r build/tests/side/VoiceFlow-side-fixture.apk
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell cmd appops set app.voiceflow SYSTEM_ALERT_WINDOW allow
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell pm grant app.voiceflow android.permission.RECORD_AUDIO
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell pm grant app.voiceflow.fixture android.permission.RECORD_AUDIO
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell ime enable app.voiceflow/.FlowKeyboard
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell settings put secure voice_recognition_service app.voiceflow.fixture/.FakeSpeech
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell am instrument -w app.voiceflow.fixture/app.voiceflow.ControlSetup
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell settings delete secure enabled_accessibility_services
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell settings put secure enabled_accessibility_services app.voiceflow/.FlowAccessibility
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell settings put secure accessibility_enabled 1
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell ime set app.voiceflow/.FlowKeyboard
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell input keyevent 224
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell wm dismiss-keyguard
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell settings put system screen_off_timeout 1800000
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 logcat -c
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell am instrument -w app.voiceflow.fixture/.SidePreview
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 logcat -d -b crash
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 logcat -d -b main -b system
```

Require `INSTRUMENTATION_RESULT: result=PASS`, no failure result, and no VoiceFlow/fixture crash or ANR. Screenshots are in the debuggable fixture's internal files, for example:

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 exec-out run-as app.voiceflow.fixture cat files/side-preview.png > side-preview.png
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 emu kill
```

If unrelated system ANR dialogs cover the editor, preserve the failure, screenshots and complete logs before changing this disposable AVD. Do not globally suppress error dialogs. The earlier Phone-disable experiment caused provider restart failures and was reversed; do not repeat that isolation as routine setup. Any targeted Bluetooth isolation must be recorded and limits call/headset coverage. A passing UI assertion is not a clean runtime pass if VoiceFlow also reports an ANR.

Setup replaces VoiceFlow preferences, the selected recognition service/IME, accessibility settings, and fixture text. It does not restore those settings. Use a disposable emulator, never an installation containing personal data; its read-only session is discarded when stopped.
