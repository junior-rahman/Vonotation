# Vocading Android 0.1.1 preview

The Android update implements all six approved improvements and uses the supplied Vocading Forest & Copper logo and control icons. Package `app.vonotation.notes` advances to version code 2; existing storage and signing identities remain unchanged.

| Area | Result |
| --- | --- |
| Status | Active, paused, interrupted, waiting, transcribing and retry states distinguish saved audio from transcription. Call setup explains speakerphone fallback and asks users to check both voices in playback. |
| Playback | Asynchronous preparation, a seek timeline across source segments, and 0.75×–2× speed. Changing speed while paused stays silent until Resume. Rapid Play/Pause no longer races the notification service. |
| Edit and share | Edit titles/transcripts and copy/share text or source audio. Original audio and original transcripts remain intact; stale provider results cannot overwrite user edits. |
| Find recordings | Search titles, transcripts, original text and caller labels. Pin recordings, filter pins and load more than the initial 60 matches. |
| Pause/resume | Seal audio when paused, resume into a new segment and keep all parts in the same note. Paused time is excluded. Finish remains visible in the expanded control. |
| Recovery | Move recordings to Recently deleted, Undo the last deletion, restore them or explicitly confirm permanent deletion. Trash has no automatic expiry and continues to use device storage. |

## Artwork

Native Android vectors and adaptive/monochrome launcher assets follow the supplied **Vocading logo and audio control concepts.png** sheet. Editable SVGs live in [design/vocading](../design/vocading). Labels remain accessible. Voitation retains its own palette, launcher and storage.

## Source audio shares

A single part shares as M4A. Long or paused recordings share as a ZIP containing a read-me and numbered M4A parts in order. Originals are copied without re-encoding. Missing segments abort export. The private provider grants read-only access to temporary copies, which expire after 24 hours.

## Verification

The Android pipeline builds/lints both app variants, runs 42 Java suites and tests actual native behavior on a disposable emulator. It covers editing, stale-response protection, pin/trash/restore, read-only shares, cross-segment seeking, speed, late-prepare cancellation, microphone pause/resume and control visibility. Native captures cover light and dark themes. Final run links are recorded in the pull request.

Real phone microphone quality, long/background recording, live provider responses, actual call audio and owner-device installation still require device checks. Backup/OAuth remains paused. iOS work is retained separately on `improve/vocading-recordings`; its testing does not block this Android handoff.
