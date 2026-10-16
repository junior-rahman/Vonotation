# Google Drive backup setup and verification

Updated October 16, 2026. App implementation and signing identity are verified locally; the current Google Cloud configuration and real-account backup are not verified.

## Current preview status

Settings → Backup opens the encrypted Google Drive backup and restore flow. The old On hold screen was removed in 0.1.11-preview. Automatic backup is opt-in, and restoring requires confirmation.

The published 0.1.16-preview APK was independently inspected with Android build-tools 35.0.0 `apksigner verify --print-certs`:

| Android registration detail | Current published preview |
|---|---|
| Package | `app.voiceflow` |
| Certificate SHA-1 | `A7:80:92:53:A2:9D:DD:19:16:F9:B4:B2:98:92:31:95:DA:58:EB:3F` |
| Certificate SHA-256 | `5D:CA:22:19:FB:E8:9F:B8:85:B2:4C:90:4F:D4:C5:7D:3B:58:93:09:22:D2:1D:45:5F:C1:0D:7A:35:F7:56:8A` |

The previous setup handoff records an Android OAuth client for a different, older signing certificate. There is no current evidence that the package and SHA-1 above have been registered in [the existing Google project](https://console.cloud.google.com/apis/credentials?project=voiceflow-37d9d). This is a registration check that remains necessary, not proof of the cause of every failed or closed Google screen.

If that exact package/certificate pair is absent, add an Android OAuth client for it in the existing project while preserving the previous clients. Keep the current APK signing identity so installed previews can update. Do not rotate the signing key, replace the Google project, publish the OAuth app, or enable billing as part of this repair.

The authorization-only implementation does not need an embedded client ID, a Web client secret, Firebase Authentication or a new API key. Supplying one of those does not register the APK's signing certificate.

## App integration

- Google Identity Services `play-services-auth:22.0.0`, using `AuthorizationClient`.
- Requested scope: `https://www.googleapis.com/auth/drive.appdata` only. Google classifies this as non-sensitive; it grants access to this app's hidden data, not the user's whole Drive.
- Initial connection and Change Google account use Google's account chooser. Reconnect and background backup request the saved Google account.
- Drive v3 REST uses `https://www.googleapis.com` with redirects disabled.
- The account returned by Drive `about.get` is pinned by email and permission ID before connected-account operations.
- Access tokens stay in memory. A rejected HTTP 401 token is invalidated in Google's cache before reconnection.
- Background authorization never launches account or consent UI. If consent is required, automatic backup pauses until foreground reconnection.
- No Credential Manager ID-token sign-in, backend auth-code exchange or device-stored refresh token is used.

Thought text and summaries may be included in encrypted text backups. Source audio and temporary clipboard history remain local and are excluded. Source-only pending thoughts do not block a text backup; matching existing local sources are preserved during restore. Restoring waits for recording, audio finalization and note processing to finish.

Google status `10 DEVELOPER_ERROR` identifies a setup problem and is shown with the installed package and certificate. A generic `12500 SIGN_IN_FAILED` or a canceled Activity without a Google status does not establish a cause. These outcomes offer retry and account/registration checks without claiming that the user canceled or that the certificate is definitely missing.

## Last reported Google project setup

These details come from the earlier phone-guided handoff. They have not been independently checked in the current Cloud Console.

| Detail | Last reported value |
|---|---|
| Project name | VoiceFlow |
| Project ID | `voiceflow-37d9d` |
| Project number | `1032987083442` |
| Owner and test-user account | `jrrahman01@gmail.com` |
| Billing | Firebase Spark; no billing linked |
| Drive API | Enabled |
| OAuth audience / publishing | External / Testing |
| Requested scope | `https://www.googleapis.com/auth/drive.appdata` |
| Historical Android OAuth client | `1032987083442-oe6olqiem3q4qerjc8ur7kgk07jgg9s5.apps.googleusercontent.com` |
| Historical registered SHA-1 | `68:24:BC:2C:9F:CF:54:26:6D:DB:53:DA:B1:05:7D:00:17:82:3F:FC` |

A Web client was also reportedly created; this app does not use it. Its secret belongs only in Console.

The old certificate was verified on historical 1.9.x APKs. The current certificate was introduced by the historical 1.9.15 preview after the old signing key was unavailable. Later 0.1.x previews retain the current certificate. Historical statements that the old OAuth registration “matches the APK” refer only to those older APKs; they do not establish registration for the published 0.1.16-preview.

## Testing-mode warning and reconnection

Google documents that External apps in Testing allow only listed test users, show a testing warning and expire authorizations seven days after consent unless the request is limited to basic identity scopes. Drive app-data access is outside that exception even though the scope is non-sensitive.

The app cannot remove Google's testing warning or make a Testing authorization permanent. Keep the project in Testing for the current repair. Publishing, branding verification and a public production rollout are separate decisions.

## Remaining real-account checks

1. In project `voiceflow-37d9d`, confirm the Android OAuth client registers `app.voiceflow` with the current SHA-1 above. Confirm Drive API is enabled, the app-data scope is configured and the intended account is an allowed test user.
2. Install the signed preview as an update and open Settings → Backup → Connect Google. Confirm native consent requests only this app's Drive app-data access.
3. Create a backup password, save a backup and confirm last-success status.
4. Change local sample data, explicitly restore and check history, dictionary, settings and thought text.
5. On another compatible installation/device, connect the same Google account, discover the backup, enter its password and explicitly confirm restore.
6. Check canceled account changes, revoked permission, expired authorization, offline retry and opted-in scheduled backup on a device with Google Play services.

No live Google sign-in, Drive persistence, production eligibility or second-device restore has been established by local/offline fixture tests. No Console or Firebase setting was changed by the native implementation or this audit.

## Official references

- https://developer.android.com/identity/authorization
- https://developers.google.com/android/guides/client-auth
- https://developers.google.com/workspace/drive/api/guides/appdata
- https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get
- https://support.google.com/cloud/answer/15549945
- https://developers.google.com/identity/protocols/oauth2/production-readiness/overview
