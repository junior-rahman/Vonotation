# Vocading: first improvement review

Reviewed 2026-10-24 (America/New_York).
Source: `0480ae06939def53085865f4bbcf82c7757136e5` on `main`.
Published Android baseline: Vocading 0.1.0-preview, package `app.vonotation.notes`.

This is a source review and proposed implementation order. No application code changed and no new runtime tests were performed in this review.

## Product boundaries

Vocading contains Voice Notes and Call Recordings. Voitation contains Dictation, Quick Thoughts and Clipboard. Preserve separate identities, source audio and provider credentials. Current repository documentation retains Vocading's forest/ivory/copper Daybook palette; Sand & Cocoa belongs to Voitation. Backup/OAuth remains paused. Source, builds and downloads stay on GitHub; private signing material stays outside the repository.

## Findings and improvements

| Priority | Observed current behavior | Proposed improvement | Completion evidence |
| --- | --- | --- | --- |
| First | Android call setup and recording copy says both sides are saved, and one live hint says the user's side is always saved. Release notes explicitly leave actual call capture unverified. | Replace guarantees with accurate setup/connection/capture states; add a short record-and-listen check. Make speakerphone fallback clear before enabling it. Connection or an open recorder alone must not be presented as proof that both voices were captured. | Test unsupported/denied/disconnected states; on the owner's Samsung phone, verify actual two-person audio through earpiece, speaker and Bluetooth separately. |
| First | Pending/error detail exists, but the recording list does not give every item a clear saved/waiting/failed/partial state. Empty pending notes promise transcription when online even if provider credentials are missing. | Keep audio-save status separate from transcription status. Show compact status labels and a specific action: add provider key, retry, or inspect partial audio. | Offline save, absent/rejected credentials, partial transcript and interrupted capture remain understandable and retain source audio. |
| First | Android note detail provides transcription, summary, translation, language and delete actions, but no visible title/transcript edit or dedicated copy/share controls. iOS already has title/transcript editing and text copy/share. | Bring Android editing and text sharing to parity. Add source-audio sharing for active recordings on both platforms. Use a compact secondary-action menu where needed. | Editing preserves original transcript/source; a late transcription cannot overwrite a user edit. Shared files remain readable by the receiving app; multi-segment recordings export completely. |
| First | Android playback offers play/pause and five-second jumps. Waveform is display-only. Playback prepares media and inspects every segment's duration synchronously from the UI path. | Add a draggable position control and playback speed; prepare media/durations off the UI thread. Keep accessible time and position controls. | Long multi-segment recordings load without UI stalls; seeking across boundaries, pause/resume and playback errors work correctly. |
| Next | Voice Notes/Calls use date-grouped lists without a visible search field, tags or pinning in the reviewed Android list. | Add search over title/transcript first, then optional pinned notes and simple filters. Keep the existing card design. | Search works for Bengali/English, pending recordings remain reachable, and hundreds of notes do not make typing or scrolling slow. |
| Next | Android voice capture exposes Record/Finish, with notification Finish/Cancel. No user-controlled recording pause/resume path was found in the reviewed capture implementation. | Add explicit Pause, Resume and Finish states, preserving one logical note and all sealed audio segments. | Exclude paused time from duration; survive rotation/backgrounding; avoid duplicate starts, lost segments and microphone ownership conflicts with Voitation. |
| Later | Confirmed deletion immediately removes the note and its source files on Android. | Add Undo or Recently Deleted with an explicit retention choice and permanent-delete action. | Restore text and every source segment; handle storage failures; exclude discarded items from transcription. Do not inherit Clipboard's 30-day expiry for ordinary recordings. |

## Suggested build sequence

1. Clear recording/transcription states and truthful call-capability copy.
2. Android edit/copy/share and smoother playback.
3. Search/pinning, then pause/resume.
4. Recovery-friendly deletion and layout/accessibility polish after native screen review.

Record-and-recover mechanisms, partial-transcript preservation, revision-checked updates, provider/network retry hooks and foreground-service ownership already exist. Extend those mechanisms instead of replacing them.

## Evidence locations

All paths below refer to the reviewed source commit.

- [Product scope and release boundaries](../APP-SPLIT.md)
- [Android Notes](../android/app/src/main/java/app/voiceflow/Notes.java): list/detail, record/actionPill, togglePlay/playQueue, catchUp/ensureThoughtRetry, updateCaptured, remove, Wave.
- [Android Calls](../android/app/src/main/java/app/voiceflow/Calls.java): show, pair, enable.
- [Foreground recording ownership](../android/app/src/main/java/app/voiceflow/NoteRecordingService.java): session lifecycle and notification actions.
- [Segmented capture](../android/app/src/main/java/app/voiceflow/VoiceTake.java): arm, rotate, stop.
- [iOS detail/edit/player](../ios/VonoTation/Views/NoteDetailView.swift): NoteDetailView, EditNoteView, SourceAudioView.
- [iOS product navigation/import](../ios/VonoTation/Views/DaybookHome.swift).
- [Published Vocading preview and device-test limits](https://github.com/junior-rahman/Vonotation/releases/tag/vocading-v0.1.0-preview).

Physical microphone quality, real call behavior, simultaneous microphone use and actual file sharing remain validation tasks, not confirmed bugs or passed checks. iOS call recordings remain imported audio in this product.
