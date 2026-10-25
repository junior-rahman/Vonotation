# Voitation 0.1.18-split-preview

Installable Android preview, package `app.voiceflow`, version code 90. Signed with the existing main-app certificate, verified SHA-256 `5dca2219fbe89fb885b24c904fd4c57d3b58930922d21d455fc10d7a35f7568a`. This preserves Android update compatibility with 0.1.17. Install over the existing app; do not uninstall it. Physical-device installation remains to be confirmed by the owner.

Voitation keeps Dictation, Quick Thoughts and the full-text 30-day Clipboard. The approved Sand & Cocoa colors now cover light/dark pages, stacked cards, Settings and floating dictation controls while preserving layout and behavior. Vocading separately provides Voice Notes and Call Recordings, retaining the original Daybook colors.

Existing identities, data paths and settings remain stable. Previous voice/call recordings remain available read-only under Settings → Previous recordings. Android recording transfer uses explicit local export from Voitation and confirmed additive import in Vocading; it does not move or delete originals. Exported recordings are unencrypted, so choose the destination carefully. No Google transfer is involved.

Exact source: `7d2c26aa8ce8d72b370138f5b1632093754e29c9`. [Android verification](https://github.com/junior-rahman/Vonotation/actions/runs/40154153363) passed both builds, full lint with no errors, all 40 Java suites, native product isolation, retained-data/transfer fixtures and capture-control contrast. All 22 light/dark screenshots were reviewed. The signed APK payload is byte-for-byte identical to the tested unsigned build except signing metadata. Private signing keys are excluded.

[Get Vocading for Android](https://github.com/junior-rahman/Vonotation/releases/tag/vocading-v0.1.0-preview). [iOS Simulator previews and source](https://github.com/junior-rahman/Vonotation/releases/tag/split-ios-v0.1.18-preview).

This is a preview for owner phone testing. Physical-device audio, live providers/calls, cross-app dictation/keyboard behavior and actual document-picker transfer/playback remain unverified. Backup/OAuth stays paused. No App Store/TestFlight release is included.
