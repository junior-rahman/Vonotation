# Vocading 0.1.1-preview — Android

Installable Android update for `app.vonotation.notes`, version code **2**, Android **8.0 or later**. Signed with Vocading's existing certificate (SHA-256 `b92734798f7d2642f336290beada2497ccc65c1f253511d9893499091777d317`). Install over Vocading 0.1.0; do not uninstall it. Package, version and signing checks establish update compatibility; owner-phone installation remains to be confirmed.

- Clear saved, recording, paused, interrupted, transcription and retry states. Call setup explains capture limits and asks you to check both voices in playback.
- Seek across recording segments and choose playback speeds from 0.75× to 2×. Changing speed while paused remains silent until Resume; rapid Play/Pause handles notification startup safely.
- Edit titles and transcripts, copy/share text, and share original source audio. Original transcripts/audio remain intact, and late provider results cannot overwrite edits.
- Search recordings, pin favorites and filter pinned items. Load more beyond the initial 60 matches.
- Pause and resume into the same note, excluding paused time. Pause/Resume and Finish remain visible.
- Recently deleted, Undo, Restore and explicitly confirmed permanent deletion. Deleted recordings do not expire automatically and continue to use storage.
- Your Forest & Copper Vocading logo and control icons, including light/dark and launcher artwork.

Single-part recordings share as M4A; segmented recordings share as a ZIP with numbered M4A files. No re-encoding. Temporary read-only share copies expire after 24 hours; original recordings are retained.

Exact source: `75dc086c3cc23120c7fe65c0470746d855cad294`. [Android verification](https://github.com/junior-rahman/Vonotation/actions/runs/40164011154) builds and lints both products, runs 42 Java suites plus recovery regressions, exercises native recording/playback/edit/share/restore behavior and checks light/dark screens. The signed APK payload is verified byte for byte against that successful native build. Source, verification evidence, native screenshots, signing receipt and checksums accompany this release.

Voitation remains a separate app and release. Backup/OAuth remains paused. iOS testing is deferred and this release contains no iOS update. Real-phone microphone quality, long/background recordings, live transcription providers, actual telephone audio and document-picker transfers still need device testing; emulator checks do not establish those behaviors.
