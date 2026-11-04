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
- `checks.yml` (pure Java suites plus a check of that app's public update feed against the new repository's source
  and its release asset) and `android-native-ci.yml` (the emulator suite, with `VONO_PRODUCTS` set to that one app).
- Its design assets and a README covering provenance, signing and the feed transition.

These stay behind: `downloads/` (68 MB of old APKs), historical release notes, patches and validation records,
the iOS project (paused; it builds both apps from one Xcode project), and the stale fixed-graph CLI toolchain in
`android/tools/`. History before the split stays in this repository.

## Verification done before the first push

For each app, the generated repository passes its pure suites and feed check (against the live release asset),
then Gradle `assemble` + `lint` on its own, and its unsigned APK is compared with this repository's APK of the same
app entry by entry. With the default (updates stay in Vonotation) the app code is unchanged; with `--own-feed` only
`classes.dex` (the feed URL) and `assets/dexopt/baseline.prof` (derived from it) differ.

## In-app updates

The updater downloads `latest.json` and the APK without signing in, so it cannot read a private repository.

**Default (private repositories):** both apps keep reading `latest.json` / `vocading-latest.json` in this public
repository, exactly as every installed copy does today, so no phone needs a transition. Each release is built in its
app's repository, published as a release asset here, and advertised by updating the feed here. Because this
repository's own source is then frozen, add `--released-elsewhere latest.json --released-elsewhere vocading-latest.json`
to the feed step in `.github/workflows/checks.yml` at the first such release; each new repository's own `checks.yml`
reads the public feed and enforces "never newer than this source" and the release-asset match instead.

**`--own-feed` (public repositories):** the new repository serves its own `latest.json`. For each app's first release
from it: bump `versionCode`, publish the APK as a release there and update its `latest.json`; then point this
repository's feed at the same asset so existing installs move to a build that reads the new feed.

Either way, keep this repository and both feed files in place indefinitely; any phone that has not updated reads them.

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
