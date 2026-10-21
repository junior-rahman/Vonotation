# VonoTation and Vono Notes

The two products share the proven capture/transcription code and Daybook visual language, but build as independent applications with independent private storage and provider credentials.

| Product | Main cards | Android identity | iOS identity |
| --- | --- | --- | --- |
| VonoTation | Dictation, Quick Thoughts, Clipboard | app.voiceflow | app.vonotation.ios |
| Vono Notes (working name) | Voice Notes, Call Recordings | app.vonotation.notes | app.vonotation.notes.ios |

VonoTation opens on Dictation. Switching Quick Thoughts off hides its card and idle dot while retaining saved thoughts. Cross-app dictation remains dictation, and Clipboard keeps the existing full-text 30-day retention. Vono Notes does not declare an accessibility service, input method, Clipboard job or Thoughts job. Its Voice Notes microphone foreground service owns only an explicitly started recording; Call Recordings remains an Android platform-dependent feature. On iOS, Call Recordings imports an existing audio file; the app does not intercept other apps' calls.

## Existing data

No original app is uninstalled and no historical recording is automatically deleted or moved. The original product retains ordinary voice/call entries and source audio, without processing or evicting them when saving thoughts. Settings → Previous recordings provides read-only legacy access. Historical translations also remain retained; the separate Translate main card has been retired from this split.

Android transfer is an explicit local file workflow: export a copy from VonoTation, then choose it in Vono Notes and confirm additive import. The export warns that the selected file contains unencrypted recording content. Imported source recordings are copied under fresh names/IDs; existing data is kept. The transfer allowlists voice/call text and source audio, excludes thoughts, dictations, Clipboard, provider keys and preferences, bounds archive sizes/paths, and stages imports before committing. No Google or network transfer is involved. The real document picker and playback still require device verification. iOS historical recordings remain individually shareable; the Android transfer ZIP is not advertised as an iOS import format.

The currently installed Android 0.1.17 build does not have the new transfer screen. Installing the original-product update is necessary to use that screen. The new app cannot directly read the old app's private data.

## Backup and updates

Google Drive backup work is paused. Its saved account/configuration is retained and old scheduling is cancelled. No OAuth setting is changed. iOS password-encrypted Files backup retains its exclusions and explicit restore confirmation; product markers prevent accidental cross-app replacement. Android/iOS backup interchange and iOS Drive linking remain unimplemented.

The original Android signing keystore is still required to update app.voiceflow. A new key cannot substitute for it without breaking update compatibility. Vono Notes has its own signing configuration and does not use the VonoTation update feed. No update feed or published release has been advanced by this source change. Never uninstall the original to work around signing failure.

## Builds and validation

Android: `bash android/scripts/build-split.sh --unsigned` builds/lints both apps without inventing replacement signing keys. `--qa` uses independent disposable test identities on an explicitly isolated emulator; those test signatures are not release identities. A signed original build requires VOICEFLOW_KEYSTORE with the existing certificate. A signed Notes build uses VONO_NOTES_KEYSTORE. `--product vonotation` or `--product notes` selects one product.

The Android native workflow builds both flavors, checks package/permission/component/card isolation, validates legacy retention and synthetic transfer roundtrips, and captures light/dark screens. Unsigned verified-payload artifacts cannot be installed until signed with the respective correct key.

iOS: open the VonoTation or VonoNotes scheme. `bash ios/scripts/run-ios-ci.sh` checks both products on native iPhone/iPad simulators, unsigned device SDK compilation and separate Simulator archives. Simulator packages are not installable iPhone IPAs.

At preparation, all 40 Java suites and source/manifest/script checks pass. Native build/test results must be read from the exact split commit's Actions runs; prior combined-app runs do not validate the split. Physical phone capture, cross-app simultaneous microphone arbitration, live providers, live telephony, real SAF transfer and user Apple signing/distribution remain separate checks.
