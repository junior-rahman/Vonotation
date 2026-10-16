# VonoTation

Current signed preview: **0.1.12-preview**, version code **84**, package `app.voiceflow`.

[Download APK, source and checksums](https://github.com/JuelHossain/Vonotation/releases/tag/v0.1.12-preview) · [Android source](android/) · [Changes and validation](release-notes/v0.1.12-preview.txt)

## Quick thoughts

Enable **Settings → Quick thoughts** and connect **Floating microphone** under Permissions. Outside VonoTation, tap the faint dot when no input is active. Hold it to open Quick thoughts in the app. Drag it anywhere within the usable screen; the idle and active controls share its saved position. Recording uses the existing floating controls on your current screen. Changing apps, focusing a field or tapping elsewhere does not end it. Use the physical **Finish** or **Cancel** control; a system interruption can finalize a recording. A tap does not open the app or a capture popup. Normal input dictation keeps its selected engine and writes into the active field.

A thought uses the existing Voice Notes recording pipeline: source audio is saved locally even without a connection or API key. Once finished, it waits for the connected Notes provider to transcribe it, organize the note and produce a grounded summary and title. Original words remain available. Pending completed thoughts retry when a connection returns, including a persisted background job; Android may defer that job. Still-recording segments are excluded from transcription. Unsupported organization keeps the original safely.

Inside the app, the Thoughts card displays saved notes, summaries and titles for viewing, editing and deleting. It has no recording button or recording popup. Source audio stays hidden until **Source audio** is tapped, which reveals playback. Retained audio is stored on this phone and excluded from backups; transcription uses your connected Notes provider. Turning Quick thoughts off hides both the card and idle dot while retaining saved notes. The microphone is closed while idle, and expanded idle controls shrink after 60 seconds. Reminders remain a future feature. Existing colors, typography and layout are retained.

## Delete, settings and updates

Delete reads the current cursor or selection from the active editor. A selection is deleted in place; otherwise Delete removes the preceding word. Relative editor context supports fields that do not supply absolute cursor offsets. Repeated deletion waits for the editor to acknowledge each change and stops if the cursor, text or field changes. Editors that reject or cannot report a safe edit show feedback instead of deleting from a guessed position.

The Settings **Clear history** action has been removed. **Backup** now opens the backup flow rather than displaying the hardcoded “On hold” state. The encrypted backup/restore controls is restored, and restore is guarded while recording or audio processing is active. This is a source-level repair: Google account consent, OAuth registration for the current signing certificate and live Drive backup/restore still require phone verification.

Open **Settings → Update**, allow installation if Android asks, then confirm Android's installer. An older build may need Update tapped again after returning from permission settings. The public `latest.json` feed advances only after its public APK is verified. The updater verifies hash, size, package, version and installed signing identity, and defers while audio work is active.

The compact clipboard and quiet Call Notes recovery changes remain included. Android, device and network changes can still disconnect wireless debugging; automatic recovery cannot guarantee a permanent connection.

## Verification and source

Per-artifact hashes, signing identity and final build/test results are recorded during release preparation in `verification/v0.1.12-preview/VERIFICATION.txt`. Preparation checks APK identity/signature and the source archive and requires successful Android preview build and passing pure Java suite evidence. The release workflow performs separate validation before publishing. The final suite count is generated from the actual test files and recorded passing results.

**Phone/device testing: not performed.** Samsung/Niagara behavior, actual input-field deletion, microphone capture, live provider responses, installation, notification behavior, wireless debugging, Google consent and backup/restore remain unverified. Synthetic JVM tests and source review do not establish native UI or backend behavior.

Private signing keys, API keys, recordings, caches and machine configuration are excluded. Historical documents under `android/` describe older builds; `android/app/build.gradle` identifies this preview. Use JDK 17, Android SDK 35 and Build Tools 35.0.0; run `bash gradlew :app:assembleDebug` inside `android`. Compatible preview signing needs the existing private key through `VOICEFLOW_KEYSTORE`; it is not included. Release preparation checks the APK against the verified preview signing identity.
