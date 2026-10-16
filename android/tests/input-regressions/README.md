# Native input regression fixture

The current production engine is cloud-only. Saving `engine=system` or selecting
FakeSpeech never changes that. Use `app.voiceflow.CloudPersistenceTest` for the
current input/app-switch recording contract; its actual microphone captures use
intercepted HTTPS and disposable text, without real provider traffic. The legacy
InputRecoveryTest live-partial/FakeSpeech contract is dormant and declines to run
by default. Its source is historical coverage, not evidence for current cloud UX.

This focused APK makes existing native input regressions runnable against the current
VoiceFlow app. It compiles the current sources from `tests/fixtures`; it does not
ship production classes. The build verifies that the fixture and production APK have
the same signing certificate. Do not interpret a successful build as a runtime pass.

## Build

From the project root, with the existing Android SDK and a freshly built app:

```bash
source /workspace/scratch/201e51819cdc/voiceflow_toolchain/env.sh
bash tests/build-input-regressions-fixture.sh
```

The helper accepts `ANDROID_SDK_ROOT` or `ANDROID_HOME`, optional `ECJ_JAR`, and
optional `VOICEFLOW_KEYSTORE`. The default production APK for signer comparison is
`app/build/outputs/apk/preview/VoiceFlow-1.9.10.apk`; set `VOICEFLOW_APP_APK` if the
fresh app is elsewhere. Production classes must be in the normal preview javac
output directory. A JDK that supplies `jdk.compiler` but no `javac` executable works.

Output: `build/tests/input-regressions/VoiceFlow-input-regressions-fixture.apk`.
`fixture-signature.txt`, `app-signature.txt`, and `inputs-sha256.txt` are beside it.

## Isolation and fixture compatibility

- Use only the owner's disposable API 35 AOSP emulator. Never install on a real
  device or an installation containing personal data. Do not run concurrent suites.
- This APK uses `app.voiceflow.fixture`, because the historical tests hard-code that
  package. Installing it with `-r` replaces the side fixture. Finish SidePreview
  first, or reinstall the side APK later. Other separately named fixtures coexist.
- Keep the ordinary AOSP HOME launcher and an ordinary typing IME enabled. This
  fixture intentionally contains no HomeFinder/HOME activity. KeyboardStateTest
  refuses a fixture HOME launcher, because it would test different overlay behavior.
- The passwords, PINs, dictionary entries, API key, and speech results are synthetic
  constants. No account login, Google consent, real provider call, or Drive operation
  is needed. Never supply a real credential to these tests.
- The legacy synthetic-speech fixtures select `app.voiceflow.fixture/.FakeSpeech`;
  the current cloud engine does not call that service. ApiRequestTest, RecoveryTest
  and CloudPersistenceTest intercept HTTPS in the app process and use
  the fake key `fixture-only-key`; their outgoing request bodies stay in memory.
- The fixture package has no INTERNET permission. App-target instrumentation still
  runs with the production app's INTERNET permission, so the fixture manifest alone
  is not an emulator-wide network sandbox. Keep the owner's verified emulator egress
  block enabled. As additional defense, disable the emulator's Wi-Fi and cellular
  data and enable airplane mode, then confirm no active default network before tests.
  Do not make a live HTTP request to test this isolation.
- RecoveryTest records actual emulator microphone files before serving mock HTTPS
  results. It does not establish speech accuracy, real provider behavior, or physical
  audio hardware quality. Use the emulator's synthetic/no-audio input only.
- Setup changes app preferences, keyboard/recognizer settings, permissions, and
  clipboard. InputRecoveryTest temporarily removes/re-enables the accessibility
  service. Use an emulator without other accessibility services or personal data.
  These settings are not fully restored; discard the disposable session afterward.

## Installation and setup

The owner alone runs ADB. Set `SERIAL` to the already verified disposable emulator;
do not use an implicit/default device or launch another emulator for this fixture.

```bash
export SERIAL=emulator-5580 # Replace with the owner's verified serial.
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
"$ADB" -s "$SERIAL" install --no-incremental -r \
  build/tests/input-regressions/VoiceFlow-input-regressions-fixture.apk
"$ADB" -s "$SERIAL" shell cmd connectivity airplane-mode enable
"$ADB" -s "$SERIAL" shell svc wifi disable
"$ADB" -s "$SERIAL" shell svc data disable
"$ADB" -s "$SERIAL" shell settings get global airplane_mode_on
"$ADB" -s "$SERIAL" shell dumpsys connectivity
"$ADB" -s "$SERIAL" shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME
"$ADB" -s "$SERIAL" shell ime list -s
"$ADB" -s "$SERIAL" shell cmd appops set app.voiceflow SYSTEM_ALERT_WINDOW allow
"$ADB" -s "$SERIAL" shell pm grant app.voiceflow android.permission.RECORD_AUDIO
"$ADB" -s "$SERIAL" shell pm grant app.voiceflow.fixture android.permission.RECORD_AUDIO
"$ADB" -s "$SERIAL" shell ime enable app.voiceflow/.FlowKeyboard
"$ADB" -s "$SERIAL" shell settings put secure voice_recognition_service app.voiceflow.fixture/.FakeSpeech
"$ADB" -s "$SERIAL" shell input keyevent 224
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard
"$ADB" -s "$SERIAL" shell settings put system screen_off_timeout 1800000
```

Inspect network/HOME/IME output before proceeding. Require the ordinary HOME and at
least one ordinary IME alongside FlowKeyboard. Preserve the owner's modal/health
guard and egress sandbox. Do not disable Phone/Bluetooth, hide error dialogs, or
change the system to mask a failing test.

Before **each** UI suite below, prepare the known hidden-keyboard starting state and
restart the accessibility binding. First confirm `enabled_accessibility_services`
contains no unrelated service. This reset is for this isolated emulator only:

```bash
"$ADB" -s "$SERIAL" shell settings get secure enabled_accessibility_services
"$ADB" -s "$SERIAL" shell settings delete secure enabled_accessibility_services
"$ADB" -s "$SERIAL" shell am instrument -w app.voiceflow.fixture/app.voiceflow.ControlSetup
"$ADB" -s "$SERIAL" shell settings put secure enabled_accessibility_services app.voiceflow/.FlowAccessibility
"$ADB" -s "$SERIAL" shell settings put secure accessibility_enabled 1
"$ADB" -s "$SERIAL" shell ime set app.voiceflow/.FlowKeyboard
```

ControlSetup must report `Prepared synthetic speech controls`. Do not run several
fixtures concurrently. Tests contain bounded waits, including 45-second UI waits;
allow approximately ten minutes per UI suite on a software-rendered emulator, while
stopping to inspect a surfaced app/system ANR instead of retrying around it.

## Components and coverage

Current cloud persistence component:

`app.voiceflow.fixture/app.voiceflow.CloudPersistenceTest` checks keyboard hide,
field changes, app switching and returning while recording. It requires recording
and the manual Finish control to stay active, no upload before Finish, full ordinary
clipboard/History recovery without foreign insertion, and no conversion to Thoughts
even with Quick thoughts enabled. A private input session must keep its final only
in memory until explicit sensitive copy and leave History/Thoughts unchanged.
Each run uses a random sentinel. Cleanup removes only newly created clipboard
archive rows whose full text exactly matches that run's sentinels; it never clears
the archive or removes preexisting rows. A current system clip is cleared only if
it still contains one of that run's sentinels.

`app.voiceflow.fixture/app.voiceflow.RecordingAwakeNativeTest` checks actual native
display/CPU leases in the helper, display release on screen-off, close cleanup and
late-callback refusal. It does not by itself prove physical device screen timeout.

The `RecoveryTest` component exercises real cloud recorder files with mock HTTPS,
legacy Activity background/Close finalization, worker cancellation and private
explicit-copy recovery. Dormant Android speech timeout/empty-result cases were
removed from its active run because production cannot select that engine.

The following older UI components require individual contract review. In particular,
AuthFieldTest, InputRecoveryTest and ComposeDeleteTest contain legacy synthetic
speech/live-partial expectations and are not current cloud runtime evidence.

Run the following components individually with `adb -s "$SERIAL" shell am instrument
-w COMPONENT`, preserving all output and runtime logs per suite:

1. `app.voiceflow.fixture/.KeyboardStateTest`
   Checks real IME window visibility, bubble accessibility state and hold action,
   Back-to-hide/reopen, and choice persistence across field/activity changes.
2. `app.voiceflow.fixture/.AuthFieldTest`
   Checks email/password/PIN auto-typing without automatic recording, explicit fake
   password/PIN dictation, native single-final commits while private context getters
   refuse data, prefix/selection/suffix and case/leading-zero preservation, mismatched
   credential metadata refusal without private-context reads or changes to the
   preexisting secret, and sensitive clipboard recovery after selection changes.
   Finish must return the idle main mic without floating editing/menu controls.
   All credential strings are disposable fake data.
   On failure it captures `auth-fields-failure-<elapsed-time>.png` before cleanup
   and reports the last focus request/result, actual focused field/input types,
   selected/enabled IME identities, last observed IME window bounds, and bounded
   native/accessibility window metadata. It does not log field text, read private
   InputConnection context, retry focus, change the 45-second visibility deadline,
   or relax the expected keyboard visibility.
   The auth-fields fixture screen handles system-bar/IME insets on an outer
   FrameLayout on API 30+, leaving the inner ScrollView padding zero and measuring
   it to the actual usable viewport height. Its focused input can then scroll above
   the keyboard; older devices retain adjustResize.
   Focus checks scroll the target into view, perform the existing focus request,
   and require actual accessibility-visible/focused input before testing keyboard
   visibility. This does not hide the keyboard, change display size or lower SDK.
3. `app.voiceflow.fixture/.InputRecoveryTest`
   Checks that field changes and switching to Settings keep dictation active
   until an explicit Finish, including returning to the original input. Requires
   the original clipboard to remain unchanged before Finish and the full final
   voice-IME result to be recovered when the old editor session cannot be proven.
   Also checks original-field ownership, manual-edit preservation, direct-insertion
   clipboard behavior, and ordinary/private final recovery exactly once when the
   accessibility service stops during a withheld editor acknowledgement. Private
   recovery must leave the clipboard unchanged until explicit Copy privately.
4. `app.voiceflow.fixture/.ComposeDeleteTest`
   Retains its historical component name. Checks real accessibility InputConnection
   composing spans and native dictation revision while preserving surrounding
   prefix/suffix text. Finish must return the idle main mic without floating
   editing/menu controls.
5. `app.voiceflow.fixture/app.voiceflow.RecordingAwakeNativeTest`
   Checks the real display/CPU wake-lock ownership in the recording helper,
   screen-off release of its display lease, close cleanup, and late-callback refusal.
   Building the fixture does not execute this device check.

### Explicitly retired floating-editing cases

The minimal bubble intentionally removed floating Delete/Enter controls. The
following obsolete cases are not executed or counted as passing:

- AuthFieldTest: blind native Delete through Floating Backspace on a mismatched
  credential editor. Its mismatch-refusal, unchanged-secret, context-read and
  native-write privacy guards remain active.
- ComposeDeleteTest: floating deletion of a composing word, a complete Unicode
  character, and a selected composing range. Its composing-span setup, partial/final
  dictation text and native InputConnection revision checks remain active.

Both affected suites report `retired_subcases` even on failure and explicitly
exclude these cases from their PASS summaries. Ordinary typing-keyboard Delete
and Return behavior belongs to SidePreview, which is a separate native suite.
InputRecoveryTest retains all ownership, manual-edit, clipboard, sensitive final
and exactly-once teardown assertions. Its field-change and app-switch checks now
require recording to remain active until manual Finish; private recovery requires
an explicit Copy privately action. ApiRequestTest and RecoveryTest contain no obsolete floating-control
strings and remain unchanged.

Additional app-target suites are packaged for private-request/recovery coverage:

5. `app.voiceflow.fixture/app.voiceflow.ApiRequestTest`
   Checks in-memory HTTPS request encoding, private dictionary exclusion with raw
   whitespace preserved, selected models/language, offline recap blocking, and IDs.
   Does not need an editor or microphone recording. HTTPS is intercepted in-process.
6. `app.voiceflow.fixture/app.voiceflow.RecoveryTest`
   Checks actual emulator recorder lifecycle with mock HTTPS, interruption/lock
   recovery for the unused legacy capture Activity, private History/draft exclusion
   and explicit sensitive copy, and bounded worker startup/cancellation cleanup.
   Requires RECORD_AUDIO and the same isolation described above. The current cloud
   run does not select FakeSpeech or claim Android speech timeout coverage.
   This suite temporarily locks and wakes the emulator and restores app preferences.
   Automatic daily recaps are explicitly Off while the suite opens MainActivity to
   read the clipboard, preventing unrelated recap requests from contaminating
   recording counts. Strict total and private-phase one-transcription/zero-cleanup
   assertions remain. Reports include exact mock counters and per-phase request
   categories (transcription, private transcription, daily recap, dictation cleanup,
   other chat), with request IDs/stages but no raw payload text.

App-target instrumentation may stop/recreate the app process. Re-run UI setup before
returning to a UI suite. Each app-target suite gets its own instrumentation invocation,
so its one-per-process URL handler factory does not conflict with the other suite.

Require `INSTRUMENTATION_RESULT: result=PASS`, no `failure` entry, and no VoiceFlow or
fixture crash/ANR in the corresponding interval. `am instrument` exit status by itself
does not prove assertions passed. Capture full logs/screen/state before changing a
failed emulator. Do not overwrite initial failure evidence with retry results.

## Current verification

The original 1.9.10 preparation compiled all nine then-unchanged fixture sources (26 Java class
files) using the installed compiler module, linked/DEXed/aligned the APK, verified
its signature against the freshly built app, and inspected the manifest. Compiler
output contains only fixture-owned classes; app classes are D8 classpath-only.

These preparation checks did not run ADB, start an emulator, or execute any native
instrumentation. Runtime outcomes must be recorded by the owner separately.

The later minimal-bubble compatibility repair updates AuthFieldTest,
InputRecoveryTest and ComposeDeleteTest as described above, without changing
production code or the manifest. Its source diff, retired-case scope, compiler and
signature receipts are under `evidence/1.9.10-fixture-repairs/input-minimal-bubble/`.
Those build receipts do not establish new native runtime passes.
