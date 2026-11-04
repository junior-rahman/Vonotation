# Splitting Voitation and Vocading into their own repositories

`split_android.py` writes a standalone, single-app repository from the current commit:

```sh
python3 tools/split-repos/split_android.py voitation ../Voitation --init
python3 tools/split-repos/split_android.py vocading  ../Vocading  --init
```

Only tracked files are copied, so signing keys, local properties and build output never move.

## What each repository gets

- The shared Android source, with **only its own** Gradle product flavor and signing config. Package IDs,
  certificates, storage paths and the internal Java package `app.voiceflow` are unchanged, so its builds install
  over the existing app.
- Its own `latest.json` (a copy of this repository's current feed for that app) and `UpdatePolicy.feed()` pointing
  at `raw.githubusercontent.com/junior-rahman/<Repo>/main/latest.json`.
- `checks.yml` (pure Java suites plus the feed check) and `android-native-ci.yml` (the emulator suite, with
  `VONO_PRODUCTS` set to that one app).
- Its design assets and a README covering provenance, signing and the feed transition.

These stay behind: `downloads/` (68 MB of old APKs), historical release notes, patches and validation records,
the iOS project (paused; it builds both apps from one Xcode project), and the stale fixed-graph CLI toolchain in
`android/tools/`. History before the split stays in this repository.

## Verification done before the first push

For each app, the generated repository passed its pure suites and feed check (against the live release asset),
then Gradle `assemble` + `lint` on its own. Its unsigned APK matched this repository's APK of the same app in every
one of 179 ZIP entries except `classes.dex`, which carries the new feed URL, and `assets/dexopt/baseline.prof`,
derived from it. Manifest and resources are identical.

## Transition of the in-app update feeds

Installed apps up to Voitation 0.1.23 and Vocading 0.1.2 read the feeds in **this** repository. For each app's
first release from its new repository:

1. In the new repository: bump `versionCode`, publish the signed APK as a release, and update its `latest.json`.
2. Here: point `latest.json` (Voitation) or `vocading-latest.json` (Vocading) at that same release asset. Existing
   installs then update to a build that reads the new repository's feed from then on.
3. Here: add `--released-elsewhere latest.json` (or `vocading-latest.json`) to the feed step in
   `.github/workflows/checks.yml`, because this repository's frozen source is now older than the offered version.
   The schema and release-asset checks still apply.

Keep this repository and both feed files in place indefinitely; any phone that has not updated still reads them.

## Phase 2: prune the other app's code in each repository

Done in each new repository, where its own emulator CI can verify the change.

**Voitation:** remove the call-recording stack (`CallWatch`, `CallEvents`, `CallService`, `CallShell`,
`CallShellLaunch`, `LineLink.kt`, `LineWire`, `LineWatch`, `ShellEar`, `Calls`, `ICallCapture.aidl`, and the
`CallAudioSegments`/`CallSession*`/`LineConnectionState`/`LineNoticePolicy` policies). Shared code reaches it only
through `LineWire.running()`, `LineWire.updateBusy()`, `LineWatch.start()`/`detach()` and `Calls.show()`, which are
all inert in Voitation today (`LineWatch.start` returns on `!isNotes()`, no call session can start, `Calls.show` is
in the Vocading-only branch), so a small stub is behaviour-identical. Then drop `kadb`, Shizuku, the Kotlin plugin
(`LineLink.kt` is the only Kotlin file) and `aidl`. Tests to adjust: `CallShellLaunchTest`, `CallsReliabilityTest`,
`LineConnectionStateTest`, `ProductProfileTest`, and the fixtures `VisualPaletteTest`, `NotesReliabilityTest`,
`ThoughtStorageTest`, `ThoughtMinimumLengthTest`, `BackupRestoreBusyTest` (plus `VocadingChecks` and
`VocadingAudioChecks` from the visual fixture build).

**Vocading:** remove the dictation bubble, keyboard, Quick Thoughts and Clipboard (`FlowAccessibility`,
`FlowKeyboard`, `CaptureActivity`, `ClipPeek`, `ClipboardExpiryJob`, `ThoughtJob`, the `Clip*`/`Clipboard*` and
`QuickThought*`/`Thought*` classes). These are more entangled with `MainActivity` and `Notes`, so map the call sites
the same way before removing them.
