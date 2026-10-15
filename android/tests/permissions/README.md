# Native Permissions interaction fixture

This isolated instrumentation APK exercises the installed production `MainActivity` through physical touchscreen events. It does not contain or modify production app classes, grant permissions, enable services/keyboards, select a keyboard, use ADB internally, or complete any external Android Settings screen.

## Build and run

Build the matching preview app first, then:

```sh
source /workspace/scratch/201e51819cdc/voiceflow_toolchain/env.sh
tests/build-permissions-fixture.sh
```

Output: `build/tests/permissions/VoiceFlow-permissions-fixture.apk`.

Install the fixture beside the preview app on a **disposable emulator/test installation**, signed with the same preview certificate. The builder packages only `PermissionsInteractionTest` and its inner classes. Production classes are compile/D8 classpath inputs only. No extra dependencies or downloads are needed.

The emulator owner runs:

```sh
adb shell am instrument -w -e disposable true \
  app.voiceflow.permissions.fixture/app.voiceflow.PermissionsInteractionTest
```

The explicit disposable argument is required. Unlock the device and allow the app to become foreground. Do not interact during the run. Use only synthetic app/device data. This fixture does not call ADB or modify Android secure settings. It adapts to the current microphone and keyboard state; do not silently count unselected branches as covered.

Existing Accessibility services remain enabled during instrumentation (`FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`). Device policy or overlays that prevent physical input or system-window inspection should be reported as a runtime blocker, not as a pass.

## Checks

- One physical first tap opens Home → Settings and one opens Settings → Permissions.
- Each Permissions action row is enabled/clickable and cannot consume its first touch for touch-mode focus. Every app action uses one DOWN/UP pair, without `performClick`, accessibility clicks, or retry taps.
- Microphone:
  - If already granted, or previously requested with no rationale, one first tap must emit `ACTION_APPLICATION_DETAILS_SETTINGS` with `package:app.voiceflow`.
  - Otherwise it must emit exactly one Android runtime request for `RECORD_AUDIO`. The expected private `microphone_requested` marker is checked; unrelated private preferences cannot change.
  - The Activity request is intercepted and canceled. This verifies app routing, not Android permission-dialog completion or a real grant.
- Floating microphone:
  - First tap opens the actual app disclosure.
  - Physical Cancel, identified by the framework button resource ID instead of a localized label, closes it without an outgoing Activity.
  - The panel remains intact and its row views are rebuilt on the real window-focus return; labels and accessibility state descriptions match current Android state.
  - Reopening the disclosure and physically tapping its positive button emits exactly one intercepted `ACTION_ACCESSIBILITY_SETTINGS` intent. No service is enabled.
- Optional keyboard:
  - If VoiceFlow is disabled, the first tap emits one intercepted `ACTION_INPUT_METHOD_SETTINGS` intent. No enable screen is opened or completed.
  - If enabled, it opens the real Android input-method picker. The fixture recognizes a new focused/active external window with a selection list and a currently installed IME label loaded from Android, rather than hard-coded localized Android text. Physical Back dismisses it without a selection. Real window-focus return must rebuild the row.
- Real `Activity.recreate()` preserves the Permissions panel and rebuilds all three statuses from current OS state. This does not simulate changing any OS grant or selection.
- Returning to Settings restores focus to Permissions.
- The entire seeded app-preference map and retained draft remain unchanged by permission interactions. Microphone permission, selected/enabled keyboards, and enabled Accessibility services must equal their initial values. HTTP/HTTPS attempts must remain zero.

All external Activity starts during the checks pass through an `Instrumentation.ActivityMonitor` that records the real outgoing Intent and returns `RESULT_CANCELED`. The enabled-keyboard picker is a system window, not an Activity, so it is the one actual Android system UI flow observed and dismissed. A successful intercepted Intent check is **not** proof that Android's Settings Activity, permission controller, restricted-settings flow, or permission dialog works end to end.

## Synthetic data and cleanup

The fixture temporarily seeds empty history, offline mode, fake model IDs, a clearly synthetic dictionary/draft, no connected account/provider credential, disabled recaps/bubble/haptics and automatic backup Off. It blocks HTTP/HTTPS in the instrumented process. In `finally`, it restores the original `voiceflow` preferences, `MainActivity` private preferences and retained draft, and verifies exact equality. Cleanup failures fail the run. As with other native fixtures, forced termination cannot execute cleanup; never use an installation containing real user data. Ordinary production lifecycle cleanup/scheduling can execute, so this is not suitable for a live user's installation even though original preferences are restored.

## Evidence and limits

Instrumentation returns runtime assertion/tap counts, intercepted Activity count, microphone and keyboard branch descriptions, zero-network count, failure phase/control tree when needed, and an explicit coverage limitation. No expected assertion total is hard-coded. A successful compile/signature check is not a native runtime pass.

Actual Android screenshots are exported to the target app's external media directory, usually `/sdcard/Android/media/app.voiceflow/`:

- `permissions-initial.png`
- `permissions-floating-disclosure.png`
- `permissions-android-keyboard-picker.png` (enabled-keyboard branch only)
- `permissions-returned.png`
- `permissions-failure-<elapsed-time>.png` (best-effort capture before failure cleanup)

Failure reports also include the pre-tap window IDs, expected installed-IME labels,
last picker-candidate rejection reasons, and bounded accessibility node diagnostics
for current external windows (package, class, resource ID, text/description,
visibility, bounds and selection-list structure). These diagnostics do not retry
taps or relax the new-window, installed-label or selection-list requirements.

Screenshots show the installed native app/system UI with synthetic state. Record the independently verified production APK version/hash and a clean crash/ANR log interval with the instrumentation output. A run covers only the device's initial microphone and keyboard branches. To cover the other branches, the emulator owner must independently arrange permitted synthetic OS preconditions and run again; the fixture deliberately does not grant privileges or alter real OS state.
