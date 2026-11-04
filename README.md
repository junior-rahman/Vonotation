# Voitation and Vocading

Two independent apps share the native capture/transcription code and Daybook interface. Voitation uses the approved Sand & Cocoa colors; Vocading retains the original forest, ivory and copper palette.

| App | Features | Android preview |
| --- | --- | --- |
| **Voitation** | Dictation, Quick Thoughts, full-text 30-day Clipboard | [0.1.23-preview — download APK](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.23-preview) |
| **Vocading** | Voice Notes, Call Recordings | [0.1.2-preview — download APK](https://github.com/junior-rahman/Vonotation/releases/tag/vocading-v0.1.2-preview) |

[iPhone/iPad Simulator previews and source](https://github.com/junior-rahman/Vonotation/releases/tag/split-ios-v0.1.18-preview) · [Implementation and data preservation](APP-SPLIT.md) · [Verification and remaining device checks](RELEASE-STATUS.md)

## Install on Android

Download the APK from the appropriate GitHub release and confirm Android's installer. Both require Android 8 or later. Voitation also appears through **Settings → Update** in the existing main app.

**Install Voitation over the existing app; do not uninstall it.** Its package remains `app.voiceflow` (version code 95 for 0.1.23), and every release since 0.1.17 keeps the same certificate (SHA-256 `5dca2219…7568a`). Android update compatibility is established from package, version and certificate checks; installation on the owner's physical phone remains a separate check.

Vocading installs separately as `app.vonotation.notes` (version code 3 for 0.1.2) with its own retained signing identity. Its provider credentials and private data are independent. Vocading 0.1.2 adds **Settings → Update** with its own `vocading-latest.json` feed; install 0.1.2 manually once if you are on 0.1.0 or 0.1.1.

Voitation preserves previous voice/call recordings under **Settings → Previous recordings**. To copy recordings into Vocading, first update Voitation, export a local recording archive, then select it in Vocading and confirm additive import. Originals remain in place. The exported file contains unencrypted recording text/audio; select its destination accordingly. Thoughts, dictation history, Clipboard, credentials and preferences are excluded. The real file picker and playback require device testing.

## Use the iOS preview

The iOS ZIPs are for **iOS Simulator on a Mac with Xcode**, not installable iPhone/iPad IPAs. Open `ios/VonoTation.xcodeproj`; select `VonoTation` for Voitation or `VonoNotes` for Vocading. Simulator use needs no Apple development team. Physical-device installation requires the owner's Apple signing and provisioning. No App Store/TestFlight distribution is included.

The iOS deployment target is 17 or later; native CI tested iPhone SE (3rd generation) and iPad mini (A17 Pro) simulators. iOS provides in-app dictation, explicit Clipboard Paste, and importing call audio from Files. It cannot reproduce Android's cross-app floating overlay or intercept other apps' calls.

## Verified source and builds

`main` contains the exact published Voitation 0.1.23 source, [`97c9c7f`](https://github.com/junior-rahman/Vonotation/commit/97c9c7fa61169ef72bd964477ab5b49fcda38e11), including the 0.1.21 clipboard/numeric-keyboard and 0.1.22 persistent-clipboard changes. 0.1.23 adds **Share audio** for saved Quick Thoughts (original M4A, or an ordered ZIP for multipart recordings). Vocading 0.1.2 was published from [`aa09ee7`](https://github.com/junior-rahman/Vonotation/commit/aa09ee75fd127b7c64adfcd0cd7c7a82d8c8aa78); `main` also carries later shared-source changes, so its next release needs version code 4.

| Verification | Result |
| --- | --- |
| [Voitation 0.1.23 Android native run](https://github.com/junior-rahman/Vonotation/actions/runs/40279485068) | Both builds and lints, Java suites, recovery checks, native light/dark, enlarged-text and unfolded layouts, and real Quick Thoughts Share audio checks passed |
| [Vocading 0.1.2 Android native run](https://github.com/junior-rahman/Vonotation/actions/runs/40164942633) | Both builds and lints, Java suites, recovery regressions, recording/playback, product isolation, light/dark and the real Update button with synthetic feeds passed |
| [Previous split iOS native run](https://github.com/junior-rahman/Vonotation/actions/runs/40154153323) | Applies to the existing 0.1.18 Simulator release only; no new iOS testing or release since |

`.github/workflows/checks.yml` runs the pure Java suites on every push and pull request, and checks that `latest.json` and `vocading-latest.json` never offer a version newer than `main`'s source and match their published release assets byte for byte. The iOS audit changes are preserved on `deferred/voitation-ios-audit`.

These are native-verified previews for owner device testing. Physical microphone quality, live providers/calls, cross-app keyboard behavior, real recording transfer/playback and Apple signing/distribution are not established by simulator checks. **Backup/Google OAuth remains paused.**

For Android development use JDK 17, Android SDK 35 and Build Tools 35.0.0. `bash android/scripts/build-split.sh --unsigned` builds and lints both products. Release signing requires the correct private key supplied separately through `VOICEFLOW_KEYSTORE` (passwords via `VOICEFLOW_KEYSTORE_PASSWORD` / `VOICEFLOW_KEY_PASSWORD`) or `VONO_NOTES_KEYSTORE` (`VONO_NOTES_KEYSTORE_PASSWORD` / `VONO_NOTES_KEY_PASSWORD`). The legacy default password is public, so re-protect both keystores with `keytool -storepasswd` and `-keypasswd`; that changes only the file's protection, not the certificate, so updates stay compatible. Disposable QA keys are only for isolated emulators. For iOS run `bash ios/scripts/run-ios-ci.sh` on a compatible Mac.

Public source, releases, checksums and evidence are delivered through GitHub. Private signing keys, API credentials, personal recordings and machine configuration must remain outside the repository and release assets. Historical documentation and releases describe earlier combined-app versions; this root README identifies the current split preview.
