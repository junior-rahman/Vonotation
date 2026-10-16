# VonoTation for iPhone and iPad

Native SwiftUI preview, version **0.1.17 (89)**, for **iOS/iPadOS 17 or later**. The Daybook colors, stacked cards, expandable days, transcript-first notes and compact settings follow the Android app.

This project contains real iOS application, XCTest and XCUITest targets. It does not contain Apple signing certificates, provisioning profiles, user credentials or provider API keys.

## What is available

| Feature | iOS behavior |
| --- | --- |
| Dictation | Start in the app, keep recording until explicit Finish/Cancel, then copy or share the transcript. Changing cards does not change the recording kind. |
| Voice notes | Save source audio locally before processing; offline captures stay queued for Retry. |
| Quick Thoughts | Use the Record Quick Thought shortcut, Siri or `vonotation://thought`; title and concise summary lead, with full transcript and optional source audio inside. Turning Thoughts off hides its card. |
| Daybook | Daily groups for notes, dictations, thoughts and clipboard; daily recap controls for thoughts and dictation. |
| Clipboard | User-initiated native Paste control, full text and fixed 30-day expiry; no background clipboard monitoring. |
| Calls | Import an existing audio recording from Files. The app does not intercept or record other apps’ calls. |
| Translation | Record speech, keep the original transcript and produce a translation into the selected language. |
| Backup | Password-encrypted Files export/import, with explicit confirmation before replacing local data. iOS archive format is separate from Android. |
| Privacy | API key in Keychain; audio, clipboard and API key excluded from exported backup. |

The Android floating overlay, accessibility-driven text insertion, system call integration, universal clipboard capture and APK update installer cannot be reproduced with ordinary iOS application APIs. This preview does not advertise those capabilities. Google Drive account linking needs a separately registered iOS OAuth client and is not included; encrypted Files backup works independently.

Transcription uses your own compatible cloud-provider API key. No live API key is bundled. Keep the existing English transcription default; the separate translation output supports the target language you choose.

## Run the app in Xcode

1. Open `VonoTation.xcodeproj` on a Mac with Xcode and an iOS 17+ simulator runtime.
2. Choose the **VonoTation** shared scheme and an iPhone or iPad simulator.
3. Build and Run. Simulator testing needs no Apple Developer Team.
4. In the app’s Settings, add your own compatible provider API key when testing actual transcription. Microphone recording can be saved before a network connection or key is available.

The checked-in project has no external package dependencies. After adding/removing Swift sources or asset catalogs, run:

```sh
python3 scripts/generate-project.py
```

## Install on a physical device using your own Apple Account

Signing in to Apple inside VonoTation does **not** install or sign an app. Apple signing happens in Xcode or through an approved distribution channel.

1. On a Mac, add your Apple Account in **Xcode → Settings → Accounts**.
2. Open the application target’s **Signing & Capabilities**, enable automatic signing and select your own Team.
3. If necessary, change `app.vonotation.ios` to a unique bundle identifier under your account.
4. Connect your iPhone/iPad, enable Developer Mode when requested, choose the device and Run.

A free Personal Team can install for personal testing, with Apple’s provisioning limits and periodic reinstall requirements. App Store/TestFlight distribution requires the appropriate Apple Developer Program enrollment. The GitHub simulator ZIP runs in a Mac’s iOS Simulator; it is **not an iPhone-installable IPA**. An unsigned device SDK build proves compilation, not installation or distribution.

Apple references: [running on simulated or physical devices](https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices), [Personal Team provisioning limits](https://developer.apple.com/help/account/basics/about-your-developer-account).

## Native verification

```sh
bash scripts/run-ios-ci.sh
```

The script fails if Xcode, an available iPhone simulator or an available iPad simulator is missing. It does not substitute a browser mockup or Linux compiler for Apple SDK tests.

On a Mac runner it:

1. Checks the source inventory against the actual checked-in Xcode project.
2. Builds the native application and native XCTest/XCUITest bundles.
3. Runs policy, encrypted archive, full-text persistence and UI tests on both an available iPhone and iPad simulator.
4. Captures native simulator screenshots of Home, Dictation, Thoughts, Clipboard and Settings. These screenshots use explicitly isolated **DEBUG UI fixtures**, not user data or live provider results.
5. Compiles the Release application with the physical-device iOS SDK, with signing disabled.
6. Builds a Release simulator archive and records source/artifact hashes in `build/ci/VERIFICATION.json` only after every command above succeeds.

GitHub Actions uses the repository’s `.github/workflows/ios-ci.yml`. Failed runs retain logs and `.xcresult` evidence; simulator artifacts are exposed only when the verification step succeeds. A green run covers the listed tests, not every possible app/device/network combination.

The initial source was prepared on Linux, where Xcode and Apple simulators are unavailable. **Do not label this preview as natively tested unless the corresponding macOS CI run and its `VERIFICATION.json` have actually succeeded.** Physical hardware audio quality, long background recordings, user signing/provisioning, live provider accounts and App Store review still require their respective environments.

## Test isolation

Only a Debug build understands `--ui-testing`, `--ui-fixtures` and `--ui-screen`. UI-test storage is a separate temporary location. Fixtures never erase or populate the normal store, never inject a production API key and never stand in for provider-response tests. Unit tests create their own unique temporary stores and remove only those stores afterward.

Backup restore replaces completed text notes and settings only after a user confirms; pending source recordings must be preserved. Backups intentionally exclude audio, clipboard, recaps and the API key. Keep your backup password separately: it cannot be recovered from the archive.
