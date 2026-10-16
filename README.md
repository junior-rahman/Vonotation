# VonoTation

Preview: **0.1.16-preview**, version code **88**, package `app.voiceflow`.

[Download APK, source and checksums](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.16-preview) · [Android source](android/) · [Changes and validation](release-notes/v0.1.16-preview.txt)

This preview focuses on keeping input-started dictation active while switching apps or temporarily leaving an input, improving browser/custom input recognition, separating the Quick thoughts icon from the existing dictation icon, producing concise thought summaries, and adding a matching daily Clipboard card. The broader color and aesthetics refresh follows this reliability work. See the release notes for the reviewed change scope.

Start dictation from an input and use **Finish** or **Cancel** to end capture. Changing apps, closing the keyboard or temporarily losing the input does not convert the session into Quick thoughts. Text inserts while the original editor remains verified. Losing that editor's authority permanently suspends insertion for the rest of the capture, including after returning to the field. Normal Finish keeps the full dictation in clipboard recovery and adds history only when Save history is enabled; private capture stays in memory for explicit Copy only. Recording keeps the screen awake while capture is active, with manual screen locking respected.

The separate **Clipboard** card uses the existing Daybook daily expandable interface. It keeps the full text of platform-accessible ordinary copies locally for **30 days**, without earlier count or character limits discarding unexpired copies. Expired entries disappear immediately and are cleaned up in the background. The compact floating latest-copy pill and scrolling tray remain available; copies do not open automatic popups.

Android permits clipboard reads while VonoTation has focus or is the selected keyboard. Copies unavailable under those rules cannot be recovered retroactively. Sensitive, password and system-private copies are excluded, and clipboard history is excluded from Drive text backups.

Enable **Settings → Quick thoughts** and connect **Floating microphone** under Permissions. Outside VonoTation, tap the faint thought icon when no input is active to record in place, hold it to open saved thoughts, or drag it to reposition it. Saved thoughts use the daily timeline, concise summaries and full transcripts, with source audio hidden until requested. Turning Quick thoughts off hides the card and idle control while keeping saved thoughts.

Thought source recordings stay local even without an API key or connection. Transcription and optional daily recaps wait for the configured Notes provider and a connection; Android may defer background retries. Transcription sends audio to that provider. Text backups exclude recordings.

Open **Settings → Update**, allow installation if Android asks, then confirm Android's installer. The updater verifies hash, size, package, version and signing identity. Public downloads are verified before the update feed advances.

## Verification and source

The final build, full lint, JVM-suite and artifact checks are recorded in `verification/v0.1.16-preview/VERIFICATION.txt`. Historical documents under `android/` describe older versions; `android/app/build.gradle` identifies this source version.

Phone/device testing: not performed.

App switching, browser/custom input recognition, screen timeout and manual lock behavior, microphone capture, cursor and selection compatibility, clipboard access/expiry, haptic feedback, live providers, wireless call recording, notification timing, background retries and Android installation still need hardware testing. Google OAuth registration for the current signing certificate, account consent and live Drive backup/restore remain unverified.

Private signing keys, API keys, recordings, caches and machine configuration are excluded. Use JDK 17, Android SDK 35 and Build Tools 35.0.0; run `bash gradlew :app:assembleDebug` inside `android`. Compatible preview signing requires the existing private key through `VOICEFLOW_KEYSTORE`; it is not included.
