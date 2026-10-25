# Vocading 0.1.1 preview changes

This update implements the six recording improvements and integrates the supplied Vocading circular waveform logo and Forest & Copper control icons. Android package `app.vonotation.notes` advances to version code 2; iOS bundle `app.vonotation.notes.ios` advances to build 2. Existing storage identities remain unchanged.

| Area | Result |
| --- | --- |
| Recording status | Audio availability, active/paused recording, interrupted capture, pending transcription, processing and retry states are explicit. Android call setup distinguishes connection from verified capture; iOS continues to import call audio. |
| Playback | Android prepares audio asynchronously with a seek timeline across all source segments. Both platforms offer 0.75×, 1×, 1.25×, 1.5× and 2× playback. iOS also has five-second jumps. |
| Edit and share | Edit a title or transcript, copy text, share text and share source audio. User edits do not rewrite source audio or the original transcript; stale provider results cannot overwrite edited text. |
| Find recordings | Search titles, transcripts and original text, pin useful recordings and filter to pinned items. Android initially renders 60 matches and offers Show more. iOS expands matching date groups during a search. |
| Pause and resume | Pause and resume a voice note before finishing it. Paused time does not count as recorded audio. Android seals each paused segment and groups all parts into the same note. iOS resumes within the same file. |
| Recover deletion | Deleting moves a recording into Recently deleted. Restore keeps its title, transcript, pin and source audio. Permanent deletion requires a separate confirmation. Android also offers Undo last deletion. |

Recently deleted recordings have no automatic expiry and continue to use device storage. They are excluded from active lists, search and pending transcription. No source audio is removed by ordinary deletion.

## Artwork

[Native artwork sources](../design/vocading/README.md) are adaptations of the user-supplied **Vocading logo and audio control concepts.png** sheet. Android includes VectorDrawable controls plus adaptive/monochrome launcher assets. iOS includes separate Vocading control and app-icon assets. Existing Daybook colors remain in place; Voitation retains its own identity.

## Source audio sharing

Android shares a read-only temporary copy using a private content provider. One segment is a `.m4a`; multiple segments are an ordered ZIP with a read-me and numbered `.m4a` parts. Parts are never concatenated as raw bytes or re-encoded. Missing segments abort export rather than producing an incomplete share. Shared cache copies expire after 24 hours; private originals remain unchanged. iOS shares its original single source file using the native share sheet.

## Verification scope

The repository checks both products so shared-code changes cannot silently break Voitation. Local Java coverage includes recording timing, Unicode search/status decisions and the existing product-isolation suites. Android native tests exercise editing, stale-response guards, pin/trash/restore, read-only sharing, cross-segment seeking, playback speed, late preparation cancellation and microphone pause/resume. iOS tests cover old saved-note decoding, search, stale-response protection, edit/trash/restore durability and native audio pause/resume/playback. Native light/dark captures cover both apps; microphone controls are additionally captured on Android.

Native CI result links and final counts are recorded in the pull request after checks complete. These are emulator/Simulator checks. Physical-phone microphone quality, long/background recording, actual call audio, live provider requests and owner Apple signing still require device validation. Call capture cannot be guaranteed from an enabled switch or a connected call.

Backup/OAuth work remains paused. This change does not migrate recordings between the two apps or replace either app's signing identity.
