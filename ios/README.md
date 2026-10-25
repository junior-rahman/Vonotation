# Voitation and Vocading for iOS

The source builds two native products. Voitation uses the approved Sand & Cocoa colors; Vocading retains the original forest/ivory/copper Daybook palette. Their existing layouts and interaction patterns are preserved:

| Scheme | App and bundle | Version | Main cards |
| --- | --- | --- | --- |
| `VonoTation` | Voitation, `app.vonotation.ios` | 0.1.18 (90) | Dictation, Quick Thoughts, Clipboard |
| `VonoNotes` | Vocading, `app.vonotation.notes.ios` | 0.1.0 (1) | Voice Notes, Calls |

Both apps require iOS/iPadOS 17+. Vocading imports existing call audio from Files; it cannot record other apps’ calls. Each bundle has its own application container, provider Keychain service, URL scheme and backup product marker. No app group, shared credential access or silent data migration is configured. Existing Voitation storage and provider-key names are unchanged.

The original app retains voice notes, calls and translations from its earlier combined version. They remain reachable in **Settings → Previous recordings**, where text and source audio can be read, played and shared without editing or deletion. Original recordings do not automatically appear in Vocading; share an audio copy and import it explicitly. Previous pending work is retained and is not automatically sent to a provider by the other product. Translation is retained as archived data; neither split product exposes a Translate card.

Google Drive/OAuth work remains paused. The existing iOS encrypted Files backup stays available with its audio/Clipboard/key exclusions and explicit restore confirmation. New archives declare their product; cross-product restore is rejected before changing local data. Unmarked pre-split iOS archives are accepted by the original app only. Restoring active-product text preserves the original app’s inactive historical notes and sources. Android backup interoperability is not included.

Run `python3 scripts/generate-project.py` after source additions. Both schemes and their test targets are generated from the same dependency-free project. `bash scripts/run-ios-ci.sh` verifies both products sequentially: native iPhone/iPad tests, per-product recording-intent metadata, actual SDK bundle identities, light/dark native screenshots, unsigned iPhoneOS compilation and Simulator archives. `VONO_SCHEME=VonoTation` or `VONO_SCHEME=VonoNotes` selects a single product. Current source defines 46 tests per device for Voitation and 6 per device for Vocading. Native results must be read from the matching CI run; local Python/shell checks do not establish Apple SDK or device execution.

Physical-device audio/provider behavior, user signing/provisioning and distribution remain unverified. A Simulator archive is not an installable IPA. Neither product bundles Apple credentials or provider keys. Existing signing/provisioning must be supplied by the owner before physical distribution.

---

The following 0.1.17 notes describe the prior combined preview and its historical validation, not a native test result for the split above.

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
2. Builds the native application and native XCTest/XCUITest bundles, then checks the actual generated metadata for both recording intents and their shortcut phrases.
3. Runs policy, encrypted archive, full-text persistence and UI tests on both an available iPhone and iPad simulator.
4. Captures native simulator screenshots of Home, Dictation, Thoughts, Clipboard and Settings. These screenshots use explicitly isolated **DEBUG UI fixtures**, not user data or live provider results.
5. Compiles the Release application with the physical-device iOS SDK, with signing disabled.
6. Builds a Release simulator archive and records source/artifact hashes in `build/ci/VERIFICATION.json` only after every command above succeeds.

GitHub Actions uses the repository’s `.github/workflows/ios-ci.yml`. Failed runs retain logs, `.xcresult` evidence and exported native test attachments; simulator artifacts are exposed only when the verification step succeeds. Diagnostic failure screenshots are kept separately from the ten planned review screenshots. A green run covers the listed tests, not every possible app/device/network combination.

The initial source was prepared on Linux, where Xcode and Apple simulators are unavailable. **Do not label this preview as natively tested unless the corresponding macOS CI run and its `VERIFICATION.json` have actually succeeded.** Physical hardware audio quality, long background recordings, user signing/provisioning, live provider accounts and App Store review still require their respective environments.

## Test isolation

Only a Debug build understands `--ui-testing`, `--ui-fixtures` and `--ui-screen`. UI-test storage is a separate temporary location. Fixtures never erase or populate the normal store, never inject a production API key and never stand in for provider-response tests. Unit tests create their own unique temporary stores and remove only those stores afterward.

Backup restore replaces completed text notes and settings only after a user confirms; pending source recordings must be preserved. Backups intentionally exclude audio, clipboard, recaps and the API key. Keep your backup password separately: it cannot be recovered from the archive.
