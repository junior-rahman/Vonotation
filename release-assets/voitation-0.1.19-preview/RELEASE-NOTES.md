# Voitation 0.1.19-preview — Android

Installable Android update: `app.voiceflow`, version code **91**, Android **8.0 or later**. Signed with the original Voitation certificate (SHA-256 `5dca2219fbe89fb885b24c904fd4c57d3b58930922d21d455fc10d7a35f7568a`). Install over 0.1.18 without uninstalling; existing saved content and settings are retained.

- Recovery keeps audio until valid text is durably saved. Empty provider results and interrupted delivery no longer discard the only copy.
- Removes silent three-recording eviction. Every unexpired pending recording has Retry, Export and Discard controls; retry processing survives screen recreation.
- Cloud dictation finishes below the upload-size limit. Older oversized recordings can be exported from recovery.
- Adds full-text search and a 24-hour Undo journal for deleted saved content, including revision-conflict protection.
- Integrates the supplied Voitation logo and dictation-state artwork while retaining Sand & Cocoa. Cleans up Settings version text and card spacing.

Source: `9aa8cf9c237c094facc99689f591500e95e41967`. [Android verification](https://github.com/junior-rahman/Vonotation/actions/runs/40161476962): both app builds and full lint, all 40 Java suites, production recovery regressions, native Undo and clipboard migration, light/dark screens, 160% text and unfolded-screen layouts. The published APK is compared byte-for-byte with the native-tested unsigned payload before publication; only signature metadata may differ.

This is an owner-testing preview. Real microphone/provider behavior, folding during live recording, and insertion into third-party apps still require phone testing. No new iOS testing or release is included. iOS work is preserved separately, and Vocading improvements remain on their own branch. The cancelled no-input change is excluded. Backup/OAuth remains paused. Private signing keys and credentials are excluded.
