# Voitation and Vocading

Two independent apps share the native capture/transcription code and Daybook interface. Voitation uses the approved Sand & Cocoa colors; Vocading retains the original forest, ivory and copper palette.

| App | Features | Android preview |
| --- | --- | --- |
| **Voitation** | Dictation, Quick Thoughts, full-text 30-day Clipboard | [0.1.20-preview — download APK](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.20-preview) |
| **Vocading** | Voice Notes, Call Recordings | [0.1.0-preview — download APK](https://github.com/junior-rahman/Vonotation/releases/tag/vocading-v0.1.0-preview) |

[iPhone/iPad Simulator previews and source](https://github.com/junior-rahman/Vonotation/releases/tag/split-ios-v0.1.18-preview) · [Implementation and data preservation](APP-SPLIT.md) · [Verification and remaining device checks](RELEASE-STATUS.md)

## Install on Android

Download the APK from the appropriate GitHub release and confirm Android's installer. Both require Android 8 or later. Voitation also appears through **Settings → Update** in the existing main app.

**Install Voitation over the existing app; do not uninstall it.** Its package remains `app.voiceflow`, version code increases from 91 to 92, and its signature matches the previous 0.1.19 and 0.1.18 releases. Its original private key was recovered and verified before signing. Android update compatibility is established from package, version and certificate checks; installation on the owner's physical phone remains a separate check.

Vocading installs separately as `app.vonotation.notes`, version code 1, with its own retained signing identity. Its provider credentials and private data are independent. It does not use Voitation's update feed.

Voitation preserves previous voice/call recordings under **Settings → Previous recordings**. To copy recordings into Vocading, first update Voitation, export a local recording archive, then select it in Vocading and confirm additive import. Originals remain in place. The exported file contains unencrypted recording text/audio; select its destination accordingly. Thoughts, dictation history, Clipboard, credentials and preferences are excluded. The real file picker and playback require device testing.

## Use the iOS preview

The iOS ZIPs are for **iOS Simulator on a Mac with Xcode**, not installable iPhone/iPad IPAs. Open `ios/VonoTation.xcodeproj`; select `VonoTation` for Voitation or `VonoNotes` for Vocading. Simulator use needs no Apple development team. Physical-device installation requires the owner's Apple signing and provisioning. No App Store/TestFlight distribution is included.

The iOS deployment target is 17 or later; native CI tested iPhone SE (3rd generation) and iPad mini (A17 Pro) simulators. iOS provides in-app dictation, explicit Clipboard Paste, and importing call audio from Files. It cannot reproduce Android's cross-app floating overlay or intercept other apps' calls.

## Verified source and builds

Voitation Android 0.1.20 source is [`8fdf788`](https://github.com/junior-rahman/Vonotation/commit/8fdf788617a8c67ba6b1ec9bb6828ed2aec62601), tracked in [PR #4](https://github.com/junior-rahman/Vonotation/pull/4). The release source and native-tested tree match. It adds the approved V-wave and selected Thought Loop, polished Settings/search/recovery/Restore cards, and a clipboard fan preview only for successfully copied dictation after input loss. The fan never pastes automatically. Subsequent shared-source merges preserve separate Vocading updates.

| Verification | Result |
| --- | --- |
| [Voitation 0.1.20 Android native run](https://github.com/junior-rahman/Vonotation/actions/runs/40165099233) | Both builds and lints, all 43 Java suites, recovery/input-origin regressions, native clipboard privacy and Undo checks, light/dark, 160% text and unfolded layouts passed |
| [Previous split iOS native run](https://github.com/junior-rahman/Vonotation/actions/runs/40154153323) | Applies to the existing 0.1.18 Simulator release only; no new iOS testing or release for 0.1.20 |

The iOS audit changes are preserved on `deferred/voitation-ios-audit`. Vocading has a separate improvement branch and release process. [0.1.20 release assets](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.20-preview) include the signed APK, source, signature, checksums and verification evidence.

These are native-verified previews for owner device testing. Physical microphone quality, live providers/calls, cross-app keyboard behavior, real recording transfer/playback and Apple signing/distribution are not established by simulator checks. **Backup/Google OAuth remains paused.**

For Android development use JDK 17, Android SDK 35 and Build Tools 35.0.0. `bash android/scripts/build-split.sh --unsigned` builds and lints both products. Release signing requires the correct private key supplied separately through `VOICEFLOW_KEYSTORE` or `VONO_NOTES_KEYSTORE`. Disposable QA keys are only for isolated emulators. For iOS run `bash ios/scripts/run-ios-ci.sh` on a compatible Mac.

Public source, releases, checksums and evidence are delivered through GitHub. Private signing keys, API credentials, personal recordings and machine configuration must remain outside the repository and release assets. Historical documentation and releases describe earlier combined-app versions; this root README identifies the current split preview.
