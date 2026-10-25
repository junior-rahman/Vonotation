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

The original Android signing keystore was recovered privately and its certificate verified against the existing release. The signed Voitation preview keeps app.voiceflow and increases version code to 90. Vocading uses its own retained signing identity and does not use the Voitation update feed. Public release assets are verified before advancing the main update feed. Never uninstall the original app to work around a signing failure.

## Builds and validation

Android: `bash android/scripts/build-split.sh --unsigned` builds/lints both apps without inventing replacement signing keys. `--qa` uses independent disposable test identities on an explicitly isolated emulator; those test signatures are not release identities. A signed original build requires VOICEFLOW_KEYSTORE with the existing certificate. A signed Notes build uses VONO_NOTES_KEYSTORE. `--product vonotation` or `--product notes` selects one product.

The Android native workflow builds both flavors, checks package/permission/component/card isolation, validates legacy retention and synthetic transfer roundtrips, and captures light/dark screens. Unsigned verified-payload artifacts cannot be installed until signed with the respective correct key.

iOS: open the `VonoTation` (Voitation) or `VonoNotes` (Vocading) scheme. `bash ios/scripts/run-ios-ci.sh` checks both products on native iPhone/iPad simulators, unsigned device SDK compilation and separate Simulator archives. Simulator packages are not installable iPhone IPAs.

Both final native runs passed for source `7d2c26aa8ce8d72b370138f5b1632093754e29c9`: [Android](https://github.com/junior-rahman/Vonotation/actions/runs/40154153363) and [iOS](https://github.com/junior-rahman/Vonotation/actions/runs/40154153323). See [release status](RELEASE-STATUS.md) for exact counts, package identities and remaining device checks.

## Completed approved scope

- Renamed public surfaces to Voitation and Vocading while retaining internal app/storage identities.
- Split product cards, native components, provider credentials and private storage.
- Preserved historical recordings and implemented explicit additive Android recording transfer.
- Applied Sand & Cocoa to Voitation's light/dark pages, Settings, stacked cards and floating capture/clipboard controls; retained layouts, geometry, motion, interactions and existing Android edge opacity.
- Preserved Vocading's original Daybook palette and product separation.
- Completed exact-source native Android and iPhone/iPad verification and reviewed all 54 native screenshots.
- Prepared separately signed Android previews, with Voitation's original certificate restored for compatible updates.
- Published source, Android previews, iOS Simulator previews, checksums and verification evidence through GitHub; finalized PR #1.

## Sand & Cocoa roles

This approved pass changes colors only. It preserves interface layout, card geometry, stacking, spacing, type, icons, navigation, gestures and behavior. Semantic error/warning meaning remains distinct.

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

The main microphone and floating bubble/pill use these roles across their supported states. Contrast checks account for actual composited surfaces; existing borders and offsets remain intact. Vocading retains the original forest/ivory/copper palette.

## Delivery and remaining boundary

[Voitation Android](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.18-split-preview) · [Vocading Android](https://github.com/junior-rahman/Vonotation/releases/tag/vocading-v0.1.0-preview) · [Both iOS Simulator previews](https://github.com/junior-rahman/Vonotation/releases/tag/split-ios-v0.1.18-preview)

The approved implementation and native preview packaging are complete. Physical phone capture, simultaneous microphone arbitration, live providers/telephony, actual SAF transfer/playback and complete cross-app dictation flows require device checks. Physical iPhone/iPad distribution requires owner Apple signing/provisioning; Simulator ZIPs cannot be installed on those devices. These limits are not represented as passing tests. Backup/OAuth remains paused.
