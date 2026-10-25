# Voitation and Vocading

Approved public names: **Voitation** and **Vocading**. Existing package IDs, bundle IDs, storage paths, URL schemes, signing configuration and internal Xcode scheme names remain stable so this rename preserves app identity and existing data.

The two products share the proven capture/transcription code and Daybook visual language, but build as independent applications with independent private storage and provider credentials.

| Product | Main cards | Android identity | iOS identity |
| --- | --- | --- | --- |
| Voitation | Dictation, Quick Thoughts, Clipboard | app.voiceflow | app.vonotation.ios |
| Vocading | Voice Notes, Call Recordings | app.vonotation.notes | app.vonotation.notes.ios |

Voitation opens on Dictation. Switching Quick Thoughts off hides its card and idle dot while retaining saved thoughts. Cross-app dictation remains dictation, and Clipboard keeps the existing full-text 30-day retention. Vocading does not declare an accessibility service, input method, Clipboard job or Thoughts job. Its Voice Notes microphone foreground service owns only an explicitly started recording; Call Recordings remains an Android platform-dependent feature. On iOS, Call Recordings imports an existing audio file; the app does not intercept other apps' calls.

## Existing data

No original app is uninstalled and no historical recording is automatically deleted or moved. The original product retains ordinary voice/call entries and source audio, without processing or evicting them when saving thoughts. Settings → Previous recordings provides read-only legacy access. Historical translations also remain retained; the separate Translate main card has been retired from this split.

Android transfer is an explicit local file workflow: export a copy from Voitation, then choose it in Vocading and confirm additive import. The export warns that the selected file contains unencrypted recording content. Imported source recordings are copied under fresh names/IDs; existing data is kept. The transfer allowlists voice/call text and source audio, excludes thoughts, dictations, Clipboard, provider keys and preferences, bounds archive sizes/paths, and stages imports before committing. No Google or network transfer is involved. The real document picker and playback still require device verification. iOS historical recordings remain individually shareable; the Android transfer ZIP is not advertised as an iOS import format.

The currently installed Android 0.1.17 build does not have the new transfer screen. Installing the original-product update is necessary to use that screen. The new app cannot directly read the old app's private data.

## Backup and updates

Google Drive backup work is paused. Its saved account/configuration is retained and old scheduling is cancelled. No OAuth setting is changed. iOS password-encrypted Files backup retains its exclusions and explicit restore confirmation; product markers prevent accidental cross-app replacement. Android/iOS backup interchange and iOS Drive linking remain unimplemented.

The original Android signing keystore is still required to update app.voiceflow. A new key cannot substitute for it without breaking update compatibility. Vocading has its own signing configuration and does not use the Voitation update feed. No update feed or published release has been advanced by this source change. Never uninstall the original to work around signing failure.

## Builds and validation

Android: `bash android/scripts/build-split.sh --unsigned` builds/lints both apps without inventing replacement signing keys. `--qa` uses independent disposable test identities on an explicitly isolated emulator; those test signatures are not release identities. A signed original build requires VOICEFLOW_KEYSTORE with the existing certificate. A signed Notes build uses VONO_NOTES_KEYSTORE. `--product vonotation` or `--product notes` selects one product.

The Android native workflow builds both flavors, checks package/permission/component/card isolation, validates legacy retention and synthetic transfer roundtrips, and captures light/dark screens. Unsigned verified-payload artifacts cannot be installed until signed with the respective correct key.

iOS: open the `VonoTation` (Voitation) or `VonoNotes` (Vocading) scheme. `bash ios/scripts/run-ios-ci.sh` checks both products on native iPhone/iPad simulators, unsigned device SDK compilation and separate Simulator archives. Simulator packages are not installable iPhone IPAs.

At preparation, all 40 Java suites and source/manifest/script checks pass. Native build/test results must be read from the exact split commit's Actions runs; prior combined-app runs do not validate the split. Physical phone capture, cross-app simultaneous microphone arbitration, live providers, live telephony, real SAF transfer and user Apple signing/distribution remain separate checks.

## Remaining work plan

Approved October 24, 2026 (New York). Voitation is the priority for the next implementation pass.

### 1. Finish naming and retain existing data

Use **Voitation** for Dictation, Quick Thoughts and the 30-day Clipboard; use **Vocading** for Voice Notes and Call Recordings. This source update changes launcher names, in-app labels, accessibility descriptions, recording shortcuts and transfer instructions. Internal package/bundle IDs, storage locations, archive format markers, clipboard protocol labels, URL schemes and Xcode scheme names remain stable.

Do not treat the rename as a new install of the original app. Keep previous recordings accessible, preserve provider settings and retain the explicit, additive recording-transfer flow.

### 2. Apply Sand & Cocoa to Voitation

Implementation is now in the candidate source: shared light/dark roles, depth-dependent card colors, native system colors and floating capture/clipboard controls. Vocading retains its original palette. Candidate native verification and screenshot review are pending.

Reference: [approved design](https://chatgpt.com/library?file_id=libfile_98bab84d922881919f3e20bead6622b5&account_id=personal), approved October 21. This is a color-only pass: preserve interfaces, card geometry, stacking, spacing, type, icons, navigation, gestures, motion and behavior.

| Shared theme role | Light | Dark |
| --- | --- | --- |
| Page background | #EAE3D9 | #211B17 |
| Main surface | #FFF9EF | #30261F |
| Secondary panel | #E9DED0 | #45372B |
| Nearest underlay | #E5D4BB | #4A392C |
| Middle underlay | #C7B291 | #634D39 |
| Furthest underlay | #A58E6C | #81654D |
| Existing outline | #9A8468 | #AD9170 |
| Primary action / dictation button | #6B4F3C | #E5C8A1 |
| Text / icon on primary action | #FFFFFF | #211B17 |
| Primary text | #372D25 | #FBF3E8 |
| Secondary text | #6B5B4A | #D1BEA7 |

Cover Dictation, Quick Thoughts, Clipboard, Settings and subpages, dialogs, input/focus states, notices, and all app-controlled dictation/capture controls. Include the main microphone and existing floating bubble/pill in every supported idle, pressed, listening, processing, paused and disabled state. Preserve semantic error/warning meaning. Check contrast on actual composited surfaces and separate stacked cards through color, with existing edge widths and offsets.

Keep Vocading's approved Daybook structure and product separation. Do not add new features or reintroduce recording cards into Voitation during the color pass.

### 3. Verify the exact candidate

Run the existing Java suites, Android builds/lint and native product-isolation/retention/transfer checks. On iOS, run native iPhone/iPad tests, product metadata checks and unsigned device SDK compilation. Match all results to the candidate commit.

Review actual light/dark captures of every affected page and supported dictation state. Check narrower phone widths and larger screens for the new names, contrast and readable card layers. Generated design previews are reference material, not app verification.

Recheck dictation insertion/recovery, Quick Thoughts retention and toggling, full-text Clipboard expiry, and preservation of historical recordings. Existing simulator fixtures cannot establish real microphone quality, live provider responses, real call recording or physical-device transfer/picker behavior; report those separately.

### 4. Build and deliver

First resolve whether the original Android signing key is available and verify its certificate against the previously installed release. A compatible Voitation update requires that signer; never uninstall the original app to work around a mismatch. Vocading needs its own stable release signing identity. Do not ship unsigned payloads or disposable QA signatures as installable updates.

After candidate checks, prepare the installable Android packages with version, checksum and signing/update-compatibility information for phone testing. Keep iOS Simulator previews clearly separate from physical-device distribution, which requires valid owner signing/provisioning.

Finalize the draft and advance release/update-feed metadata only after verified artifacts are available. Backup/OAuth work remains paused.

### Evidence and completion boundary

After this naming update, all 40 existing pure-Java suites passed locally. The Xcode project inventory, XML/plist parsing, Python/shell syntax and diff checks passed. Existing data-format markers and app/storage identities were retained. Native Android/iOS verification for the renamed source is still required.

The pre-rename split commit `7cb318dfd16dbf06e1a4475101280f0ba1c98bdc` has successful [Android verification](https://github.com/junior-rahman/Vonotation/actions/runs/39623319209) and [iOS verification](https://github.com/junior-rahman/Vonotation/actions/runs/39623319425). Those results do not validate later naming or color changes.

Sand & Cocoa is implemented in source; no new release has been delivered. The next deliverable is a tested color-update candidate with native screenshots; installable delivery depends on compatible signing.
