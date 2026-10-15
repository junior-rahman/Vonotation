# VonoTation

Current signed preview: **0.1.10-preview**, version code **82**, package `app.voiceflow`.

[Download APK, source and checksums](https://github.com/JuelHossain/Vonotation/releases/tag/v0.1.10-preview) · [Android source](android/) · [Changes and validation](release-notes/v0.1.10-preview.txt)

## Quick thoughts

Open **Settings → Quick thoughts → On → Open Thoughts**. Record there, or tap the faint edge dot outside VonoTation when no input is active. The outside dot needs Floating microphone under Permissions. With an input active, normal dictation remains unchanged.

Turning the setting off hides the Thoughts home card and dot while retaining notes. The microphone stays closed while idle; expanded controls shrink after 60 seconds idle. The original words save before optional organization. The current Notes model formats paragraphs/bullets and selects a short source-anchored title; unsupported changes fall back safely. You can read the original, edit or delete. Reminders remain a future feature.

Earlier previews hid this default-off setting under Dictation and provided only light cleanup/opening-word titles. This build makes the control directly visible in Settings, opens Thoughts without searching the deck, adds guarded structured organization, and prevents an unchanged Save changes action from undoing formatting. Existing styles and the four original home cards are retained.

## Updates and existing fixes

Open **Settings → Update**, allow installation if Android asks, then confirm Android's installer. An older build may need Update tapped again after returning from permission settings. The public `latest.json` feed advances only after its public APK is verified. The updater verifies hash, size, package, version and installed signing identity; it defers while audio work is active.

The compact clipboard, current-cursor deletion and quiet Call Notes recovery fixes from 0.1.9 remain included. Android/OEM/network changes can still disable wireless debugging; permanent connection is not promised.

## Verification and source

Android preview build, vital lint, APK identity/signature checks and all 20 pure Java suites passed, including 96 organization checks. Actual phone/Samsung/Niagara UI, microphone/provider responses, installer return, notifications, wireless debugging and live backup/restore remain unverified. Title safeguards are conservative best-effort checks; the original is retained.

Private signing keys, user API keys, recordings, caches and machine configuration are excluded. Historical documents under `android/` describe older builds; `android/app/build.gradle` identifies this preview. Use JDK 17, Android SDK 35 and Build Tools 35.0.0; run `bash gradlew :app:assembleDebug` inside `android`. Compatible preview signing needs the existing private key through `VOICEFLOW_KEYSTORE`; it is not included. Verified 0.1.7/0.1.8/0.1.9/0.1.10 APKs use the same signing identity.
