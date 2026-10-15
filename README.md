# VonoTation

Android dictation, voice notes, translation and call recording.

Current signed feature preview: **0.1.8-preview**, version code **80**, package `app.voiceflow`.

- [Download and release notes](https://github.com/JuelHossain/Vonotation/releases/tag/v0.1.8-preview)
- [Android source](android/)
- [Preview validation and behavior limits](release-notes/v0.1.8-preview.txt)

This preview adds optional Quick thoughts and a compact clipboard pill/history popup using the existing design. The Android build and 16 pure Java suites passed. Phone, microphone/provider, native visual/touch, Niagara and live backup/restore testing remain pending. Reminder scheduling is future work.

The next signed build is adding the revised in-app update flow and fixing cursor-aware deletion and Call Notes reconnect notifications. The public `latest.json` feed will be published after its APK download is verified.

Signing keys, user API keys, recordings, machine caches and account data are excluded. Historical documents under `android/` describe earlier builds; `android/app/build.gradle` identifies the current source version.

Build the source with JDK 17, Android SDK 35 and Build Tools 35.0.0. Run `./gradlew :app:assembleDebug` inside `android`. A preview signed for installation over an existing app requires the existing private signing key, which is not included.
