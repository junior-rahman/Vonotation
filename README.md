# Voitation and Vocading

| Current product | Features |
| --- | --- |
| **Voitation** (formerly VonoTation) | Dictation, Quick Thoughts, 30-day Clipboard |
| **Vocading** (formerly Vono Notes) | Voice Notes, Call Recordings |

The split remains in development. See [the current implementation and release plan](APP-SPLIT.md#remaining-work-plan). The public names are updated in this source; existing application identities and data locations are retained. Sand & Cocoa is approved for Voitation and remains pending implementation.

## Published combined-app preview (before the split and renaming)

The downloads and validation below describe the earlier VonoTation release.

| Platform | Version | Download |
| --- | --- | --- |
| Android | 0.1.17-preview (89) | [APK, source and checksums](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.17-preview) |
| iOS/iPadOS | 0.1.17 (89) | [Mac Simulator preview and source](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.17-ios-preview) |

## Android

Preview: **0.1.17-preview**, version code **89**, package `app.voiceflow`.

[Download APK, source and checksums](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.17-preview) · [Android source](android/) · [Changes and validation](release-notes/v0.1.17-preview.txt)

This preview focuses on separating Google connection state from backup operation status, keeping backup feedback responsive, and preserving locally recorded thoughts and incomplete transcription progress during text restore. The broader color and aesthetics refresh follows this reliability work. See the release notes for the reviewed change scope.

Start dictation from an input and use **Finish** or **Cancel** to end capture. Changing apps, closing the keyboard or temporarily losing the input does not convert the session into Quick thoughts. Text inserts while the original editor remains verified. Losing that editor's authority permanently suspends insertion for the rest of the capture, including after returning to the field. Normal Finish keeps the full dictation in clipboard recovery and adds history only when Save history is enabled; private capture stays in memory for explicit Copy only. Recording keeps the screen awake while capture is active, with manual screen locking respected.

The separate **Clipboard** card uses the existing Daybook daily expandable interface. It keeps the full text of platform-accessible ordinary copies locally for **30 days**, without earlier count or character limits discarding unexpired copies. Expired entries disappear immediately and are cleaned up in the background. The compact floating latest-copy pill and scrolling tray remain available; copies do not open automatic popups.

Android permits clipboard reads while VonoTation has focus or is the selected keyboard. Copies unavailable under those rules cannot be recovered retroactively. Sensitive, password and system-private copies are excluded, and clipboard history is excluded from Drive text backups.

Enable **Settings → Quick thoughts** and connect **Floating microphone** under Permissions. Outside VonoTation, tap the faint thought icon when no input is active to record in place, hold it to open saved thoughts, or drag it to reposition it. Saved thoughts use the daily timeline, concise summaries and full transcripts, with source audio hidden until requested. Turning Quick thoughts off hides the card and idle control while keeping saved thoughts.

Thought source recordings stay local even without an API key or connection. Transcription and optional daily recaps wait for the configured Notes provider and a connection; Android may defer background retries. Transcription sends audio to that provider. Text backups exclude recordings.

Backup status separates the Google connection from the current backup task. Operations report progress and provide retry actions without treating every failure as a sign-in or configuration problem. Restore preserves locally available thought recordings and unfinished processing when an archive contains older or text-only data. Google OAuth registration for the current signing certificate, account consent and live Drive backup/restore remain unverified.

Open **Settings → Update**, allow installation if Android asks, then confirm Android's installer. The updater verifies hash, size, package, version and signing identity. Public downloads are verified before the update feed advances.

### Android verification and source

The final build, full lint, JVM-suite and artifact checks are recorded in `verification/v0.1.17-preview/VERIFICATION.txt`. Historical documents under `android/` describe older versions; `android/app/build.gradle` identifies this source version.

Phone/device testing: not performed.

App switching, browser/custom input recognition, screen timeout and manual lock behavior, microphone capture, cursor and selection compatibility, clipboard access/expiry, haptic feedback, live providers, wireless call recording, notification timing, background retries and Android installation still need hardware testing. Google OAuth registration for the current signing certificate, account consent and live Drive backup/restore remain unverified.

Private signing keys, API keys, recordings, caches and machine configuration are excluded. Use JDK 17, Android SDK 35 and Build Tools 35.0.0; run `bash gradlew :app:assembleDebug` inside `android`. Compatible preview signing requires the existing private key through `VOICEFLOW_KEYSTORE`; it is not included.

## iPhone and iPad native preview

Native SwiftUI source targets iOS/iPadOS 17 or later and follows the Daybook layout with dictation, Quick thoughts, summaries, local source recordings, explicit clipboard capture, translation, provider settings and encrypted Files backup. [Browse the iOS source](ios/) and [its platform-specific instructions](ios/README.md).

The preview ZIP is for **iOS Simulator on a Mac with Xcode**. It is **not an installable iPhone IPA**. Unzip the source, open `VonoTation.xcodeproj`, choose the `VonoTation` scheme and an iPhone/iPad simulator, then Build and Run. Simulator use needs no Apple development team.

For a physical iPhone or iPad, build the source in Xcode with your own Apple development Team and provisioning. The app itself has no Apple ID sign-in requirement. No signing certificates, provisioning profiles, credentials or provider API keys are bundled.

The Android cross-app floating overlay and accessibility insertion are unavailable in this iOS app. Clipboard collection requires an explicit native Paste action. Call audio can be imported from Files; the app does not capture other apps' calls. iOS backup uses its own password-encrypted Files format. Google Drive account linking and Android/iOS backup interchange are not included.

### Native verification

[Native macOS CI run 38865347766](https://github.com/junior-rahman/Vonotation/actions/runs/38865347766) passed on exact source commit `ee43607f4030c94377ecabe06c0d326304e26d04`: **40/40 tests on iPhone SE (3rd generation)** and **40/40 on iPad mini (A17 Pro)**, with zero failures or skips. Both simulators ran iOS/iPadOS **26.2 (23C54), arm64**. Xcode **16.4 (16F6)** used SDK **18.5**. Unsigned iPhoneOS SDK compilation and a Release Simulator archive passed; one microphone-permission deprecation warning remains. The 17.0 deployment target does not establish iOS 17 hardware testing. Source/artifact hashes, generated recording-intent metadata and ten native screenshots are recorded with the release.

Reviewed screenshots use isolated Debug fixtures. Simulator tests and generated App Intent metadata checks do not demonstrate physical-device Siri discovery, real microphone quality, long background recording or live provider results. Apple signing/provisioning, physical hardware and App Store/TestFlight distribution remain unverified. No iOS Google Drive connection has been implemented or tested.
