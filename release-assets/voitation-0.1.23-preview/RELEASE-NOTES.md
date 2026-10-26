# Voitation 0.1.23-preview

Open Quick Thoughts, open a saved thought, then tap **Share audio**. Choose a receiving app, or a file manager that offers a save action.

- Shares the original M4A recording, including thoughts still waiting for transcription. No provider connection is required for sharing.
- Recordings with multiple source parts share as a ZIP with numbered M4A files in order. The screen explains this before sharing.
- Active recordings and text-only thoughts do not offer Share audio. Missing source parts cannot produce a misleading partial export.
- Shared copies are read-only, temporary copies; the original saved audio stays in Voitation.
- Retains the latest persistent clipboard recovery, PIN/number keyboard behavior, approved artwork, and Sand & Cocoa design.

Android 8.0 or later. Package `app.voiceflow`, version code 95. Signed with the retained original Voitation certificate; install over the existing app.

Validation: both Android builds and lints, Java suites, recovery checks, native light/dark, enlarged-text and unfolded layouts. New native checks exercise the real Quick Thoughts Share audio button, Android chooser intent, URI grants, provider metadata, exact source bytes, ordered multipart ZIP, read-only access, and missing-source/active-recording/text-only cases. The chooser is intercepted by instrumentation; sending to a third-party receiving app and physical-device installation are not tested. iOS is unchanged and untested for this release.

Source commit: `97c9c7fa61169ef72bd964477ab5b49fcda38e11`.
Android verification: https://github.com/junior-rahman/Vonotation/actions/runs/40279485068
