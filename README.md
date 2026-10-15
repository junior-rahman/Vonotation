# VonoTation

Android dictation, voice notes, translation and call recording.

Current signed preview: **0.1.9-preview**, version code **81**, package `app.voiceflow`.

- [Download APK, source and checksums](https://github.com/JuelHossain/Vonotation/releases/tag/v0.1.9-preview)
- [Android source](android/)
- [Changes and verification limits](release-notes/v0.1.9-preview.txt)
- [Previous 0.1.8 preview](https://github.com/JuelHossain/Vonotation/releases/tag/v0.1.8-preview)

This build includes the optional Quick thoughts card and compact clipboard from 0.1.8, plus:

- In-app update progress, verified downloads and Android installation handoff.
- Deletion at the current cursor or selected range, with guarded held deletion.
- Quiet, deduplicated Call Notes connection notices and improved local reconnection/pairing preservation.

The existing design is retained. Full Android preview build, APK signature/identity checks and all 19 pure Java suites passed. Phone/Samsung cursor and touch behavior, installer permission return, notification presentation, reboot/update reconnection, OEM wireless debugging, background microphone startup and live provider/backup operations remain untested on a device. Reminders remain future work.

## Update inside the app

Open **Settings → Update**. Allow VonoTation to install apps if Android asks, then confirm installation. An older installed build may need Update tapped again after returning from Android permission settings. The new updater resumes a verified prepared update automatically on permission return and defers installation while audio work is active.

The public `latest.json` feed keeps the legacy top-level `versionCode`, `versionName` and `apk` fields and adds SHA-256 and byte size. A feed advances only after its public release APK is verified. No silent installation is promised.

## Source and builds

Signing keys, user API keys, recordings, machine caches and account data are excluded. Historical documents under `android/` describe earlier builds; `android/app/build.gradle` identifies the current source version.

Use JDK 17, Android SDK 35 and Build Tools 35.0.0. Run `bash gradlew :app:assembleDebug` inside `android`. A preview signed for installation over an existing app requires the existing private signing key through `VOICEFLOW_KEYSTORE`; it is not included. The published 0.1.7, 0.1.8 and 0.1.9 APKs share the verified signing certificate, so those matching builds update in place.

Prepared preview manifests and artifacts are published by `.github/workflows/publish-preview.yml`. It validates APK identity, hashes, sizes and source exclusions, uploads to an owned draft, then publishes a prerelease without replacing existing assets.
