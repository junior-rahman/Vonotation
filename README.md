# VonoTation

Preview: **0.1.15-preview**, version code **87**, package `app.voiceflow`.

[Download APK, source and checksums](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.15-preview) · [Android source](android/) · [Changes and validation](release-notes/v0.1.15-preview.txt)

This preview focuses on reliability fixes for recording completion, Call Notes cleanup and recovery, asynchronous note processing, encrypted text backups, cursor editing, clipboard interactions and in-app installation. The current interface is retained; the broader color and aesthetics refresh follows the reliability work. See the release notes for the reviewed change scope.

Enable **Settings → Quick thoughts** and connect **Floating microphone** under Permissions. Outside VonoTation, tap the faint dot when no input is active to record in place, hold it to open saved thoughts, or drag it to reposition it. Use **Finish** or **Cancel** to end capture. Saved thoughts use the daily timeline, detailed summaries and full transcripts, with source audio hidden until requested. Turning Quick thoughts off hides the card and idle dot while keeping saved thoughts.

Source recordings stay local even without an API key or connection. Transcription and optional daily recaps wait for the configured Notes provider and a connection; Android may defer background retries. Transcription sends audio to that provider. Text backups exclude recordings.

Open **Settings → Update**, allow installation if Android asks, then confirm Android's installer. The updater verifies hash, size, package, version and signing identity. Public downloads are verified before the update feed advances.

## Verification and source

The final build, full lint, JVM-suite and artifact checks are recorded in `verification/v0.1.15-preview/VERIFICATION.txt`. Historical documents under `android/` describe older versions; `android/app/build.gradle` identifies this source version.

Phone/device testing: not performed.

Microphone capture, cursor and selection compatibility, live providers, wireless call recording, notification timing, background retries and Android installation still need hardware testing. Google OAuth registration for the current signing certificate, account consent and live Drive backup/restore remain unverified.

Private signing keys, API keys, recordings, caches and machine configuration are excluded. Use JDK 17, Android SDK 35 and Build Tools 35.0.0; run `bash gradlew :app:assembleDebug` inside `android`. Compatible preview signing requires the existing private key through `VOICEFLOW_KEYSTORE`; it is not included.
