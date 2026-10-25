# Vocading 0.1.2-preview — in-app updates

Vocading now has **Settings → Update**. It checks Vocading's own GitHub feed, downloads a newer release, verifies it and opens Android's installation confirmation. Failed requests, interrupted downloads and cancelled installation remain retryable. Installation waits while recording or audio processing is active.

**Install this APK manually once** to add the Update option to Vocading 0.1.0 or 0.1.1. Install over the existing app without uninstalling. Subsequent updates can start inside Vocading.

- Package `app.vonotation.notes`, version code **3**, Android **8.0 or later**.
- Uses `vocading-latest.json`; Voitation continues using its separate `latest.json` feed.
- Rejects a feed for the wrong app before downloading. Verifies exact file size, SHA-256, APK package/version and signing identity before installation.
- Retains Vocading's original signing certificate: SHA-256 `b92734798f7d2642f336290beada2497ccc65c1f253511d9893499091777d317`.
- Includes all 0.1.1 improvements: recording status, playback seeking/speed, editing/sharing, search/pins, pause/resume, recoverable deletion and your Vocading artwork.

Exact source: `aa09ee75fd127b7c64adfcd0cd7c7a82d8c8aa78`. [Native Android verification](https://github.com/junior-rahman/Vonotation/actions/runs/40164942633) builds/lints both products, runs 42 Java suites plus recovery regressions, and checks recording/playback, product isolation and light/dark screens. The real Vocading Update button is exercised with synthetic wrong-product, server-failure and successful retry/current-version responses. The signed APK payload matches that successful build byte for byte. Source, verification, screenshots, public signature and checksums are included.

The published feed and public APK download are verified separately. Physical-phone installation and live future-version upgrade confirmation still need owner-device testing. Real microphone/call/provider behavior remains device-dependent. Backup/OAuth and iOS testing remain on hold.
