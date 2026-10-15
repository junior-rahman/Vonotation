# Google setup and verification status

Updated October 4, 2026 UTC. This is integration setup evidence, not a production sign-in approval.

## Eva's handoff

Project identity is reported independently verified in the supplied handoff:

| Registration detail | Value |
|---|---|
| Project name | VoiceFlow |
| Project ID | `voiceflow-37d9d` |
| Project number | `1032987083442` |
| Owner account | `jrrahman01@gmail.com` |
| Firebase plan | Spark; no billing linked |
| Optional creation features | Analytics and Gemini switched off |

Console settings are user-reported from phone-guided steps, not independently checked by this integration:

- Google Drive API enabled.
- OAuth app name VoiceFlow; support/contact email `jrrahman01@gmail.com`.
- Audience External; publishing status Testing.
- Test user `jrrahman01@gmail.com` added after the reported eligibility error was resolved.
- `https://www.googleapis.com/auth/drive.appdata` selected, updated and saved.
- Both OAuth clients created in `voiceflow-37d9d`; their types below are supplied by the user and have not been independently checked in Console.

| Client type, user-reported | Public client ID |
|---|---|
| Android | `1032987083442-oe6olqiem3q4qerjc8ur7kgk07jgg9s5.apps.googleusercontent.com` |
| Web, unused by this implementation | `1032987083442-40bf4bfkau7lb6ahg1d5142v9pbq5up2.apps.googleusercontent.com` |

The supplied Android package and SHA-1 match the inspected preview APK. The Web client reportedly has no JavaScript origins or redirect URIs. Its existence does not change the AuthorizationClient-only implementation. Its secret stays in Console and is not required in the app or this handoff.

The handoff says Google configuration is ready for integration and testing. OAuth configuration, sign-in, backup, restore and production readiness have not been independently or end-to-end verified. No Console or Firebase setting was changed by the native app implementation.

## Android registration

Package: `app.voiceflow`.

Full signing certificate SHA-1, independently verified from Signer #1 of the fresh 1.9.9 APK at `prebuilt/VoiceFlow.apk` with Android build-tools 35.0.0 `apksigner verify --verbose --print-certs`. Its APK SHA-256 is `2bdb49cefdd5235de2670eab04bfe0b73fee51ca73a43df42bbf69e582af0270`. It matches the preserved 1.9.7/1.9.6/1.9.5 and earlier 1.9.4 APKs and the project Preview certificate:

`68:24:BC:2C:9F:CF:54:26:6D:DB:53:DA:B1:05:7D:00:17:82:3F:FC`

This is the custom development/preview signing certificate, `CN=VoiceFlow Preview, OU=Development, O=VoiceFlow, C=US`. It is not the automatically generated Gradle debug certificate. No production release, upload or Play app-signing certificate was available in this project. The included preview key is for personal testing, not public production distribution.

The Android OAuth client must register this exact package and certificate in the same Google project. A normal Gradle debug build has a different certificate and needs its own Android registration. The supplied client types remain user-reported; neither ID is hardcoded into the APK.

## Current integration

- Google Identity Services `play-services-auth:22.0.0`, `AuthorizationClient`.
- Scope: `https://www.googleapis.com/auth/drive.appdata` only.
- Drive v3 REST to `https://www.googleapis.com` with redirects disabled.
- Account identity comes from Drive `about.get` and is pinned by email plus permission ID.
- No Credential Manager Google ID-token sign-in, Firebase Authentication, Web client ID, backend auth-code exchange or device-stored refresh token.
- This authorization-only on-device path uses Android registration. Adding Credential Manager Google sign-in would require a Web OAuth client ID as its `serverClientId`.
- No API key, password, token or client secret belongs in this registration handoff.

## Required real-account checks

Google documents a seven-day expiration for Testing authorizations outside the basic identity-scope exception. Drive app-data access is outside that exception. Background resolution/authorization failures pause automatic backup and require foreground reconnection; expired/revoked HTTP 401 tokens are invalidated before reconnect. This behavior still needs live Google testing. Keep the OAuth app in Testing with billing off; publishing and release review are separate future work.

1. Install the signed preview as an update and open Settings → Google Drive backup → Connect Google.
2. Confirm the configured test account is eligible and the native consent grants only VoiceFlow app-data access.
3. Create a backup password, save a backup and confirm last-success status.
4. Change local sample data and explicitly restore; check history, dictionary and settings.
5. On another compatible installation/device, sign into the same Google account, discover the existing backup, enter its password and confirm restore.
6. Check cancelled account changes, revoked permission/reconnection, offline retry and opted-in scheduled backup on Google Play services.

The available isolated emulator uses an AOSP system image without Google Play services. Offline HTTPS/native tests can verify implementation and error handling, but cannot prove real Console registration, Google sign-in, Drive persistence, production eligibility or second-device restore.

Official references:

- https://developer.android.com/identity/authorization
- https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
- https://developers.google.com/workspace/drive/api/guides/appdata
- https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get
- https://support.google.com/cloud/answer/15549945?hl=en

## 1.9.15 preview build (October 11–12, 2026)

What Juel needs to do: in Google Cloud Console, open project `voiceflow-37d9d` and go to APIs & Services → Credentials → **Create credentials → OAuth client ID** → Application type **Android**. Enter package name `app.voiceflow` and the SHA-1 below, then Create. Keep the OAuth consent screen in Testing with `jrrahman01@gmail.com` as a test user. The existing Android client for the original key can stay. Alternatively, re-sign the APK with the original `voiceflow.keystore` if it is found.

In 1.9.15 the app is otherwise unchanged in how it uses Google: `AuthorizationClient` with the drive.appdata scope only, no serverClientId or Web client, and no Firebase. Cloud transcription, the always-on bubble, the notification fallback and the reboot handling do not involve Google.

The 1.9.15 APK is signed with a new preview key because the original `preview-signing/voiceflow.keystore` was not in the 1.9.13 source package:

- SHA-1 `A7:80:92:53:A2:9D:DD:19:16:F9:B4:B2:98:92:31:95:DA:58:EB:3F`
- SHA-256 `5D:CA:22:19:FB:E8:9F:B8:85:B2:4C:90:4F:D4:C5:7D:3B:58:93:09:22:D2:1D:45:5F:C1:0D:7A:35:F7:56:8A`

The Android OAuth client above is registered for SHA-1 `68:24:BC:…:3F:FC`. Google authorization from this build therefore fails until one of these is done: an Android OAuth client is added for `app.voiceflow` with the new SHA-1 in project `voiceflow-37d9d`, or the APK is re-signed with the original key. Failures now log `VoiceFlowDrive` with the exact status and resultCode, and they show the specific reason, including this build's SHA-1, in the Google Drive card with Retry.

## Status (round4)
Drive backup is deferred: the OAuth Android client is registered to the old SHA-1 68:24:BC:2C:…; preview builds are signed with A7:80:92:53:A2:9D:DD:19:16:F9:B4:B2:98:92:31:95:DA:58:EB:3F, so sign-in reports "cancelled" until that SHA-1 is added in project voiceflow-37d9d. Haptics and reboot handling don't involve Google.
