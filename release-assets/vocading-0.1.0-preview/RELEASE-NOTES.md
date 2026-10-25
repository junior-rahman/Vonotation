Vocading 0.1.0-preview is the new separate Android app for Voice Notes and Call Recordings.

- Package: `app.vonotation.notes`; version code 1; Android 8.0 or later.
- Installs alongside Voitation with separate storage and provider credentials.
- Retains the original Daybook palette.
- Signed with Vocading’s stable private signing identity. The private key is not included.

Both Android app builds and lint passed, along with all 40 Java suites and native light/dark product-isolation, recording-retention, transfer-codec and contrast checks. The signed APK’s application payload matches the native-verified build byte for byte. This preview is ready for phone testing; physical microphone quality, live providers, live telephone calls and real document-picker transfer/playback still require device verification.

Source commit: `7d2c26aa8ce8d72b370138f5b1632093754e29c9`.
[Android verification](https://github.com/junior-rahman/Vonotation/actions/runs/40154153363) · [Source and split-app changes](https://github.com/junior-rahman/Vonotation/pull/1)

This release contains the Vocading APK only. Voitation’s Sand & Cocoa update is built and its native preview image is included, but an installable in-place Voitation update still needs the original signing keystore. Keep the existing Voitation app installed. The new recording-export screen becomes available after that compatible update. No Voitation update feed is changed by this release. Backup/OAuth work remains paused. iOS distribution is separate; this release contains no installable iPhone app.
