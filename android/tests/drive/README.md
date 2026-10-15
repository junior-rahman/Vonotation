# Offline Drive fixtures

`DriveCloudTest` exercises the production Drive transport and encrypted backup lifecycle against an in-process fake server. `DriveBackupScreenTest` checks the native disconnected screen and missing-Google-Play-services feedback. `DriveListingTest` checks recovery of valid older archives alongside rejected generations, immutable listing results, and strict protocol failures using an injected fake transport without changing local app data. These fixtures do not verify Google Console configuration, real account consent, sign-in, or live Drive backup/restore.

Use Java 17, Android SDK platform/build-tools 35.0.0, and `zip`. First build the preview app so its Java classes and APK agree, then build the fixture:

```bash
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
bash build-apk.sh
bash tests/build-drive-fixture.sh
```

`ANDROID_HOME` is also accepted. An explicit `ECJ_JAR=/absolute/path/to/ecj.jar` selects Eclipse's compiler when needed. The helper derives project paths itself, uses the existing preview signer, and packages only fixture classes; production classes are compile/D8 classpath inputs.

Create a **disposable API 35 default/AOSP AVD without Google Play services** named `VoiceFlowDriveTest`. From the project root, run these commands against its explicit serial:

```bash
"$ANDROID_SDK_ROOT/emulator/emulator" -avd VoiceFlowDriveTest -port 5580 \
    -read-only -no-snapshot -no-window -no-audio -gpu swiftshader_indirect &
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 wait-for-device
until [[ "$("$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do sleep 1; done
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 install --no-incremental -r build/VoiceFlow.apk
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 install --no-incremental -r build/tests/drive/VoiceFlow-drive-fixture.apk
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell am force-stop app.voiceflow
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell am instrument -w app.voiceflow.drive.fixture/app.voiceflow.DriveCloudTest
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell am force-stop app.voiceflow
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell am instrument -w app.voiceflow.drive.fixture/app.voiceflow.DriveBackupScreenTest
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell am force-stop app.voiceflow
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell am instrument -w app.voiceflow.drive.fixture/app.voiceflow.DriveListingTest
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 emu kill
```

Each run must report `INSTRUMENTATION_RESULT: result=PASS` and no `failure` result. Run the fixtures individually; they install process-local fake URL handlers. Do not run the UI fixture on a Google-enabled device—it deliberately expects Play services to be unavailable.

The full cloud fixture checks actual pending JobInfo while temporarily disabling the disposable device's Wi-Fi/mobile data to prevent live authorization jobs from racing its fake HTTPS assertions. Connectivity is restored after test jobs are cancelled. The optional `-e follow_up true` instrumentation argument runs a focused continuation covering remote failure preservation, busy/interrupted work, reconnect, account isolation and older-generation restore; it reports that narrower coverage explicitly and does not replace the full suite by itself.

For the focused encrypted recovery and paused-backup checks beside rejected Drive generations, run `DriveCloudTest` with `-e partial_listing true` on the same disposable AVD before shutting it down:

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell am force-stop app.voiceflow
"$ANDROID_SDK_ROOT/platform-tools/adb" -s emulator-5580 shell am instrument -w -e partial_listing true app.voiceflow.drive.fixture/app.voiceflow.DriveCloudTest
```

This mode checks account isolation, recovery without uploads/deletes, automation remaining Off, and readiness after remote history is repaired. It reports narrower coverage and does not replace the full suite or `DriveListingTest`.

The cloud and UI fixtures temporarily replace local history, settings, credentials, and schedules. They attempt to restore saved preferences in `finally`, but interruption/crashes can leave modified state, screenshots, or Keystore entries. Use only the disposable AVD, never an installation containing personal app data.
