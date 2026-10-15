# Floating Daybook feedback component fixture

This fixture renders the actual native `FloatingIcon` and `Glass` components and runs their animation and shared `Daybook.feedback` code. It covers warm light/dark surfaces, distinct keyboard badges, the coral flow artwork, native button role, optional haptic API dispatch, stable target geometry, and interrupted press/state animations.

It first validates the actual global animator scale, then opens production `MainActivity` with synthetic offline state, empty history, recaps/bubble/haptics disabled and automatic backup Off. A focused, attached, laid-out native window initializes the process's Android animator configuration. The fixture waits up to 15 seconds for both that window and the requested public `ValueAnimator.areAnimatorsEnabled()` state. It never sets animator state, uses reflection/hidden APIs, or relaxes the requested mode assertion.

It does not record audio or contact a provider; HTTP/HTTPS is blocked and counted. The haptic test substitutes a counting `View.performHapticFeedback` implementation, so it proves optional API invocation, not a physical motor or system vibration. `SidePreview`, `KeyboardStateTest`, and credential/insertion fixtures remain required for actual accessibility overlays, physical tap/hold/drag, saved anchors, IME behavior and insertion. These component renders are not screenshots of an overlay in another app.

Build the production app first, then run:

```bash
ANDROID_SDK_ROOT=/absolute/path/to/android-sdk bash tests/build-floating-feedback-fixture.sh
```

Use only the assigned disposable AOSP emulator with no personal data. Install the exact production candidate APK and the fixture signed with its matching certificate. Preserve the original animator scale before changing the emulator setting; run the fixture once per mode, and restore the original setting afterward. Never run setup against a personal phone.

```bash
adb -s emulator-5580 install --no-incremental -r build/tests/floating-feedback/VoiceFlow-floating-feedback-fixture.apk
adb -s emulator-5580 shell settings get global animator_duration_scale
adb -s emulator-5580 shell settings put global animator_duration_scale 1
adb -s emulator-5580 shell am force-stop app.voiceflow
adb -s emulator-5580 shell am instrument -w -e motion enabled app.voiceflow.floating.fixture/app.voiceflow.FloatingFeedbackTest
adb -s emulator-5580 shell settings put global animator_duration_scale 0
adb -s emulator-5580 shell am force-stop app.voiceflow
adb -s emulator-5580 shell am instrument -w -e motion disabled app.voiceflow.floating.fixture/app.voiceflow.FloatingFeedbackTest
```

Require a PASS result in both runs, no failure result, and no app/fixture crash or ANR in the candidate interval. The report includes actual system scale, public animator state, real-window initialization and HTTP/HTTPS attempt count. In `finally`, the synthetic Activity is finished, component animations are canceled, and the complete app/private-Activity/backup-secret preferences and retained draft are restored and verified. Interrupted instrumentation cannot guarantee cleanup; use only a disposable installation. System animator settings are managed by the test runner. If the initial system value was `null`, restore it by deleting the setting; otherwise restore its exact value. The fixture does not silently change the animator scale itself and rejects a mismatched mode.
