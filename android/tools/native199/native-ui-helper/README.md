# Read-only native modal guard

`ReadOnlyUiHierarchy.jar` is a shell/app_process test helper. It is not an APK,
is not installed as an Android app, and is never bundled in VoiceFlow or a
fixture. Its sole DEX class is `voiceflow.testtools.ReadOnlyUiHierarchy`.

The runner pins the saved JAR locally and verifies its hash after copying it to
`/data/local/tmp`. The helper connects to UiAutomation with
`FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, reads the active native hierarchy,
and disconnects. There is no fallback to suppressing accessibility services,
no `performAction`, no input injection, and no dialog dismissal. A missing root,
reflection error, transport error or malformed XML prevents a clear-environment
pass.

The runner combines this hierarchy with **global** `dumpsys window` focus.
`dumpsys window windows` alone omitted current focus on the API35 emulator and
missed a visible pre-existing SystemUI ANR. The original failed receipts remain
unchanged. SystemUI modal evidence is recorded as an environment blocker,
separately from app/fixture log findings.

Build with `source toolchain/env.sh` and `bash toolchain/native-ui-helper/build.sh`.
Do not rebuild frozen bytes during a native interval. Only the root-controlled
saved-artifact queue may copy/execute the helper in the existing ADB namespace.
Current source/build proof is not native runtime proof; the shell framework
reflection still requires the authorized run.
