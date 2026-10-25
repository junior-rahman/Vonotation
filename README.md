# Voitation and Vocading

Two independent apps share the native capture/transcription code and Daybook interface. Voitation uses the approved Sand & Cocoa colors; Vocading retains the original forest, ivory and copper palette.

| App | Features | Android preview |
| --- | --- | --- |
| **Voitation** | Dictation, Quick Thoughts, full-text 30-day Clipboard | [0.1.18-split-preview — download APK](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.18-split-preview) |
| **Vocading** | Voice Notes, Call Recordings | [0.1.0-preview — download APK](https://github.com/junior-rahman/Vonotation/releases/tag/vocading-v0.1.0-preview) |

[iPhone/iPad Simulator previews and source](https://github.com/junior-rahman/Vonotation/releases/tag/split-ios-v0.1.18-preview) · [Implementation and data preservation](APP-SPLIT.md) · [Verification and remaining device checks](RELEASE-STATUS.md)

## Install on Android

Download the APK from the appropriate GitHub release and confirm Android's installer. Both require Android 8 or later. Voitation also appears through **Settings → Update** in the existing main app.

**Install Voitation over the existing app; do not uninstall it.** Its package remains `app.voiceflow`, version code increases from 89 to 90, and its signature matches the previous 0.1.17 release. Its original private key was recovered and verified before signing. Android update compatibility is established from package, version and certificate checks; installation on the owner's physical phone remains a separate check.

Vocading installs separately as `app.vonotation.notes`, version code 1, with its own retained signing identity. Its provider credentials and private data are independent. It does not use Voitation's update feed.

Voitation preserves previous voice/call recordings under **Settings → Previous recordings**. To copy recordings into Vocading, first update Voitation, export a local recording archive, then select it in Vocading and confirm additive import. Originals remain in place. The exported file contains unencrypted recording text/audio; select its destination accordingly. Thoughts, dictation history, Clipboard, credentials and preferences are excluded. The real file picker and playback require device testing.

## Use the iOS preview

The iOS ZIPs are for **iOS Simulator on a Mac with Xcode**, not installable iPhone/iPad IPAs. Open `ios/VonoTation.xcodeproj`; select `VonoTation` for Voitation or `VonoNotes` for Vocading. Simulator use needs no Apple development team. Physical-device installation requires the owner's Apple signing and provisioning. No App Store/TestFlight distribution is included.

The iOS deployment target is 17 or later; native CI tested iPhone SE (3rd generation) and iPad mini (A17 Pro) simulators. iOS provides in-app dictation, explicit Clipboard Paste, and importing call audio from Files. It cannot reproduce Android's cross-app floating overlay or intercept other apps' calls.

## Verified source and builds

The release application source is [`7d2c26a`](https://github.com/junior-rahman/Vonotation/commit/7d2c26aa8ce8d72b370138f5b1632093754e29c9), merged through [PR #1](https://github.com/junior-rahman/Vonotation/pull/1). The tested CI merge tree and final source merge tree are identical. Later documentation/update-feed commits do not change app source.

| Verification | Result |
| --- | --- |
| [Android native run](https://github.com/junior-rahman/Vonotation/actions/runs/40154153363) | Both builds, full lint without errors, all 40 Java suites, product isolation, retained-data/transfer fixtures and contrast checks passed; 22 native screenshots reviewed |
| [iOS native run](https://github.com/junior-rahman/Vonotation/actions/runs/40154153323) | Voitation 46 tests per simulator and Vocading 6 per simulator: 104 passed, zero failed/skipped; both unsigned device-SDK builds and Simulator archives passed; 32 screenshots reviewed |

These are native-verified previews for owner device testing. Physical microphone quality, live providers/calls, cross-app keyboard behavior, real recording transfer/playback and Apple signing/distribution are not established by simulator checks. **Backup/Google OAuth remains paused.**

For Android development use JDK 17, Android SDK 35 and Build Tools 35.0.0. `bash android/scripts/build-split.sh --unsigned` builds and lints both products. Release signing requires the correct private key supplied separately through `VOICEFLOW_KEYSTORE` or `VONO_NOTES_KEYSTORE`. Disposable QA keys are only for isolated emulators. For iOS run `bash ios/scripts/run-ios-ci.sh` on a compatible Mac.

Public source, releases, checksums and evidence are delivered through GitHub. Private signing keys, API credentials, personal recordings and machine configuration must remain outside the repository and release assets. Historical documentation and releases describe earlier combined-app versions; this root README identifies the current split preview.
