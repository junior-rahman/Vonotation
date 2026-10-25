# Vocading artwork integration

Native vector geometry follows the user-provided **Vocading logo and audio control concepts.png** sheet (2026-10-24, Forest & Copper, circular recording mark). The sheet is the visual reference; these scalable paths adapt its geometry for native controls and launcher safe areas.

- App/footer: open circular waveform with a copper dot. Android has adaptive and monochrome launchers; iOS has a separate Vocading app icon.
- Ready/pressed/starting/recording/saving/transcribing/saved/retry/microphone-blocked/disabled follow the supplied state sheet.
- Play and pause use the supplied playback symbols. The same two-bar pause glyph also labels the newly added recording Pause action.
- Voice notes, calls, import, transcript, share, delete, stop and settings use the supplied feature shapes. Search, pin and edit retain platform controls because the sheet does not define those glyphs.
- Text labels remain visible and accessible. Icons do not replace recording status messages.
- Android uses VectorDrawables. iOS uses lossless 144px symbol assets rendered from these SVGs (24pt display); the app icon is 1024px and opaque.

The palette and card layout remain the existing Vocading Daybook theme. The Voitation launcher is unchanged.
