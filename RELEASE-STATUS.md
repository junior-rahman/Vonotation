# Current Android release: Voitation 0.1.20-preview

[Published APK and release notes](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.20-preview). Settings → Update selects version 92. Android 8.0+, package `app.voiceflow`; install over the existing app without uninstalling.

- Polished Sand & Cocoa Settings, search, recovery and Recently deleted. Approved V-wave and selected Thought Loop across native controls, states and notifications.
- Clipboard pops out of the floating fan only after dictation loses its input and successfully copies the transcript. Ordinary copies, successful insertion, private dictation and failed copies do not trigger it. Tapping opens saved Clipboard; the fan never automatically pastes.
- Release source `8fdf788617a8c67ba6b1ec9bb6828ed2aec62601`, tree `9ccf7a6a1f318bb3bbd887a58733d94dd1800eab`. [PR #4](https://github.com/junior-rahman/Vonotation/pull/4) also preserves subsequent Vocading changes.
- [Native run 40165099233](https://github.com/junior-rahman/Vonotation/actions/runs/40165099233) passed both builds/lints, 43 Java suites, durable recovery and detached input-origin regressions, native Clipboard/privacy/Restore checks, light/dark, 160% text and unfolded layouts. Its test merge `fbd73f12e70c851e8176f2075815a809a75c1f2e` has the identical release tree. The 54 captures include four native artwork-state fixtures. A parallel run was blocked by a Quickstep system-launcher ANR before app-screen verification.
- APK: 6,275,081 bytes; SHA-256 `a726fda185909d26ed105bde1b13030a21b2e8e9749611ed4c5930be1bec560e`. Original certificate SHA-256 `5dca2219fbe89fb885b24c904fd4c57d3b58930922d21d455fc10d7a35f7568a`. Signed payload matches the tested unsigned build exactly, apart from signature metadata.
- [Publication run 40166608805](https://github.com/junior-rahman/Vonotation/actions/runs/40166608805) verified all asset digests. The public APK was downloaded and its checksum verified again.
- Physical-device microphone, provider calls and real cross-app input-loss behavior remain unverified. No iOS testing or release; backup/OAuth remains paused.

---

# Historical Android release: Voitation 0.1.19-preview

Published on GitHub: [download and release notes](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.19-preview). The main update feed now selects version 91. Android 8.0+, package `app.voiceflow`; install over the existing app without uninstalling. The original certificate matches 0.1.18 and 0.1.17.

- Source: `9aa8cf9c237c094facc99689f591500e95e41967`; [PR #2](https://github.com/junior-rahman/Vonotation/pull/2) merged as `f26021590449f7e0f7d4a5cc0d7276f5f32228d4`. Both share tree `7f54c0f7435ee4ac94a44fb1a0e442434920b144`.
- [Native Android run 40161476962](https://github.com/junior-rahman/Vonotation/actions/runs/40161476962) passed both builds/lints, all 40 Java suites, recovery regressions, native Undo and clipboard migration, light/dark screens, 160% text and unfolded layouts. A parallel PR run was blocked by a Quickstep launcher ANR; the successful run tested the identical tree.
- Recovery audio survives empty responses and interrupted delivery until text is saved. Unexpired retries are no longer silently evicted; each has Retry, Export and Discard controls. Includes search, a 24-hour Undo journal, upload-size protection, supplied branding and Settings refinements.
- Signed APK: 6,245,065 bytes; SHA-256 `ab92034fdcce27c9f62a98c083ff9ae534705cf6ef15f9e4cc6232b05f3b8267`. Certificate SHA-256 `5dca2219fbe89fb885b24c904fd4c57d3b58930922d21d455fc10d7a35f7568a`. Every application ZIP entry matches the native-tested unsigned build; only signing metadata is added.
- [Publication run 40161943534](https://github.com/junior-rahman/Vonotation/actions/runs/40161943534) succeeded and verified every uploaded asset digest.
- No new iOS testing or release. iOS audit source is preserved on `deferred/voitation-ios-audit`; Vocading improvements remain separate. The cancelled no-input change is excluded. Backup/OAuth remains paused.
- Physical-phone installation, real microphone/provider behavior, folding during live capture and third-party text insertion remain unverified.

---

# Historical split preview release status (0.1.18)

Completed October 25, 2026 UTC. Voitation's approved Sand & Cocoa pass and the Voitation/Vocading split are implemented, native-verified and delivered as previews through GitHub. Backup/OAuth remains paused.

## Source provenance

- Application source: `7d2c26aa8ce8d72b370138f5b1632093754e29c9`.
- Native CI merge commit: `5cbfc42408b3ac983570d102f7fedff2d5584594`.
- [PR #1](https://github.com/junior-rahman/Vonotation/pull/1) merged as `fbb5a4df89bedb119bcf8548456a74eebe6121c4`.
- All three have identical source tree `dd7d297bc4f7c9c3dc03b63d61f5b13cb05598c3`.
- Documentation and update-feed changes after that merge do not alter the application source.

## Android

[Exact native verification](https://github.com/junior-rahman/Vonotation/actions/runs/40154153363) passed both release-preview builds, full lint with zero errors, all 40 Java suites, native product/permission/card isolation, historical-recording retention, synthetic transfer roundtrips, growth beyond 1,000 recordings and composited capture-control contrast. Existing lint warnings remain. All 22 final native light/dark screenshots were reviewed.

| Download | Package / version code | Certificate SHA-256 |
| --- | --- | --- |
| [Voitation 0.1.18-split-preview](https://github.com/junior-rahman/Vonotation/releases/tag/v0.1.18-split-preview) | `app.voiceflow` / 90 | `5dca2219fbe89fb885b24c904fd4c57d3b58930922d21d455fc10d7a35f7568a` |
| [Vocading 0.1.0-preview](https://github.com/junior-rahman/Vonotation/releases/tag/vocading-v0.1.0-preview) | `app.vonotation.notes` / 1 | `b92734798f7d2642f336290beada2497ccc65c1f253511d9893499091777d317` |

The original main key was recovered from its private backup and verified before use. Voitation's signing identity matches 0.1.17 and version code increases from 89 to 90. Vocading has its own retained private identity. Both signed APKs preserve every tested application ZIP-entry byte, adding only signing metadata. Neither uses the disposable emulator signing identity. Release downloads include checksums and public signature evidence. Private keys and passwords are excluded.

Voitation APK SHA-256: `1facc7cf489b1531817683d753256d4ca0f5f9bee96c31a395f935b701459f9c`.

Vocading APK SHA-256: `a5d87eaff11931186137b5618a2840393829ab3f3af861dd91e3cadd8fdf1eb8`.

Install Voitation as an update without uninstalling the existing app. Previous recordings remain accessible; copying them into Vocading requires explicit export/import. Actual installation, document picker and playback on the owner's phone remain untested.

## iOS / iPadOS

[Exact native verification](https://github.com/junior-rahman/Vonotation/actions/runs/40154153323) and [public Simulator/source downloads](https://github.com/junior-rahman/Vonotation/releases/tag/split-ios-v0.1.18-preview).

| Product | iPhone SE (3rd generation) | iPad mini (A17 Pro) | Unsigned iPhoneOS SDK build |
| --- | --- | --- | --- |
| Voitation | 46 passed | 46 passed | Succeeded |
| Vocading | 6 passed | 6 passed | Succeeded |

All 104 test executions passed, with zero failures or skips. Both Release Simulator archives succeeded, and all 32 native light/dark screenshots were reviewed. Source/artifact hashes, test summaries and generated intent/product metadata were checked. One existing `requestRecordPermission` deprecation warning remains per product.

Bundle IDs remain `app.vonotation.ios` and `app.vonotation.notes.ios`; internal Xcode schemes remain `VonoTation` and `VonoNotes`. The ZIPs are Mac Simulator apps and source, not installable iPhone/iPad IPAs. Physical-device distribution requires owner Apple signing/provisioning.

## Remaining device checks

- Physical microphone quality, long/background recording and simultaneous use of both apps.
- Real cross-app dictation, keyboard/cursor behavior, accessibility, notifications and Android installer return.
- Live provider transcription, real call audio and actual document-picker transfer/playback.
- Physical iPhone/iPad behavior, Siri discovery and Apple provisioning; no App Store/TestFlight release.

These previews do not establish those outcomes. Backup/Google OAuth remains deliberately paused and is not included in the completion claim.
