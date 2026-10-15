# VoiceFlow 1.9.15 preview

Built from the 1.9.13 source (the intended 1.9.14 save was not available; its changes are not included). See `VALIDATION-1.9.15.md` for what was verified and what was not. This build is signed with a **new preview key**: it cannot install over an existing VoiceFlow install; uninstall the old app first (export a Drive backup before you do).

What changed:

- **Word-for-word guard.** AI cleanup is still rejected if it changes, adds or removes words. It is now also rejected if it changes numbers, signs or currency (`$5` → `-5`), adds or removes `%`, or adds/removes meaning-bearing symbols (`&`, `/`, `*`, `+`, `=`…). English-letter (Banglish) output is now checked vowel by vowel against the on-device pronunciation reference, not consonants only. Rejected output falls back to your captured words with a warning.
- **Spoken punctuation vs. ordinary speech.** "comma", "colon", "new line" and similar become punctuation only when used as dictation commands. "The colon is…", "my colon", "an Oxford comma", "came to a full stop" stay as words. Say "literal comma" to keep the word. Settings → AI cleanup → **Spoken punctuation commands** turns the commands off entirely.
- **Verbatim mode.** Settings → AI cleanup → **Verbatim mode** inserts exactly what speech recognition returned, including spacing, without cleanup, commands, dictionary or AI.
- **Cloud failures keep the recording.** If Cloud transcription fails, the audio is kept privately (no-backup app storage, not History/backups) for 24 hours. Home shows **Recording not transcribed · Retry / Discard**.
- **Clear processing stages and Cancel.** The floating controls show Saving → Uploading → Transcribing → Polishing/Converting → Inserting instead of "…". During processing the Finish button becomes **Cancel**; nothing is inserted after Cancel (late results are ignored).
- **Launcher search detection.** Home-launcher search (e.g. Niagara) is recognized from the user actually opening/focusing that search field, not only from a visible typing keyboard.
- **Private dictation is never copied automatically.** If a password/PIN dictation cannot be inserted, it is held in memory for 2 minutes; Home offers **Copy privately / Discard**.
- **Daily recaps are an explained opt-in** (off by default, confirmation explains what is sent).
- **Backup Cancel.** "Back up now" has **Cancel backup**, honored at every step before Drive commits; if Cancel arrives after the commit the result says so. If the saved backup is an older snapshot than the phone's data, the status says so.
- **Visual.** Larger transcript text, quieter linen texture, each day's dictations above its recap, and expanded entries show **Inserted** text and **Heard (before cleanup)** text when they differ.

### Later 1.9.15 changes (same version, versionCode 26)

- **Cloud only, English only.** Transcription always uses Cloud. Android and on-device speech are removed from the UI. Cloud is sent `language=en`. Auto-detect, Banglish/English-letter and multi-language options are removed, and saved preferences migrate automatically (engine → cloud, language → en-US, English letters → off). **Language detection is paused**: auto-detect and Bengali may return later, but this build is English only.
- **Always-on bubble.** There is no Show bubble switch. The bubble runs whenever VoiceFlow's Accessibility service is on, and Home shows one prompt if it is off. Settings is compact: API key & models first, then Transcription (AI cleanup), Floating bubble, Privacy, Backup, and a collapsed **Advanced** (cleanup options, dictionary, permissions, haptics, clear history).
- **API key & models dialog.** A saved key shows only as a masked preview ("••••••••a1b2") with "✓ API key saved", including after reopening. **Update** (or tapping the field) clears the field for a new key, and **Remove key** deletes it. A new key is checked with the provider (`GET {base}/models`) before it is saved: a 401/403 shows an inline error, nothing is saved, and the previous key stays. The Settings row reads "Key saved · …a1b2" or "No API key". A key being typed is masked.
- **Mic errors are visible.** If the mic can't start, a message appears beside the bubble (Android 13+ hides toasts from a background service), for example "API key required" or "Microphone access required", with a **Fix** action that opens the right setting. Mic permission and the API key are checked before recording starts. Home shows an "API key required" card. Blocks are logged (`adb logcat -s VoiceFlow`).
- **Notification fallback.** The "VoiceFlow is listening" notification is optional. With notifications allowed it shows. With them denied, the microphone foreground service still starts but Android hides its notice. If Android refuses the foreground service, recording continues in the bubble's own service. In every case the bubble shows recording and processing status, and no notification failure ends a dictation. POST_NOTIFICATIONS is requested once after the mic is allowed.
- **Round5 design (approved mockups).**
  - *Dictation button:* 48dp square, 12dp corners, solid coral button (round7): #B3422F→#A33A28 top-to-bottom fill, white thin-stroke glyph, soft dark shadow, no light surface. Fan tiles are the same coral with white glyphs; Cancel is darker (#8F3424, faint rim). Contrast: white on coral 5.6:1; coral on #121212 3.3:1 and on white 5.6:1. Coral #B3422F thin-bar waveform V; with the keyboard up the bars settle onto a key-row baseline (no badge). Treatment A ships; the coral glow (B) sits behind `FlowAccessibility.GLOW_WHILE_DICTATING=false`.
  - *Arc fan (`ArcFan.java`):* hold 300ms and Cancel replaces the button while Delete and Keyboard fan out on a 76dp arc. It fans left from the right edge, right from the left edge, and up near the bottom, staying 8dp inside the screen and rotating in 5° steps to keep clear of the caret line. Tiles spring out staggered along the arc and fold back in reverse. Slide onto a tile and release to act (12% magnify plus a TICK on hover); resting on Delete for 450ms fills a ring and deletes one word. Hold-and-release without moving leaves it open for taps; one tap on a tile acts. Round7: Delete never closes the fan. Each tap deletes the word before the cursor (with a TICK), rapid taps all count, and holding repeats (first word at 450ms, then every 300ms). Releasing a slide on Delete deletes and leaves the fan open. Taps inside the focused field pass through (the fan window covers only the tiles), so you can move the cursor and keep deleting. A tap anywhere else, Cancel or Keyboard closes it. Labels show only on hover or focus. If the keyboard is already open, a hold just dismisses it. HEAVY_CLICK on pop. TalkBack order is Cancel → Delete → Keyboard. With animations off, it fades in 150ms.
  - *Settings:* four expandable cards, one open at a time: Cloud setup (key masked to the last 4, transcript model), Dictation (AI cleanup, bubble size, haptics, dictionary chips), Backup (on hold, with the reason) and live Permissions. Save dictations and recaps moved to Advanced · Privacy & connection. Copper and Sand tokens unchanged.
- **Launcher search (round7).** The bubble now also appears in launcher search fields (e.g. Niagara) that focus their input without a click: VoiceFlow re-checks the launcher's focused input on window-state/content changes and no longer forgets it when the launcher's window state changes.
- **Haptics (round4).** Haptics now drive the vibrator directly (`Haptics.java`: VibratorManager + predefined CLICK/TICK/HEAVY_CLICK, 18-35 ms one-shot fallback, `USAGE_ACCESSIBILITY` on Android 13+, `VIBRATE` permission). Fires on bubble press, release/tap, and hold-menu pop. Settings → Haptic feedback is on by default. Before, it used only `performHapticFeedback(CONTEXT_CLICK)`, which Samsung maps to nothing and mutes when system touch feedback is off.
- **Samsung: turn the bubble back on.** Settings > Accessibility > Installed apps > VoiceFlow > on. If haptics feel weak: Settings > Sounds and vibration > Vibration intensity > Touch interaction (above zero), and make sure the phone isn't on Mute.
- **Deferred.** Google Drive backup (OAuth client still tied to the old signing key) and language detection (English only for now) are on hold.
- **Reboot.** After a restart or update, `BootReceiver` checks that the bubble's Accessibility service came back. If Android or the phone turned it off, or it didn't start, VoiceFlow posts **Tap to turn VoiceFlow back on** and Home shows **VoiceFlow bubble is off · Turn back on**. When the service reconnects it shows the bubble again, without needing a new tap in the field. Home also asks once for an exemption from battery optimization ("Keep the bubble running"), and Advanced → Permissions shows the battery state. On Samsung, also see "Samsung: keep the bubble after a restart" below.
- **Google Drive and the signing key.** This build is signed with a new preview key, SHA-1 `A7:80:92:53:A2:9D:DD:19:16:F9:B4:B2:98:92:31:95:DA:58:EB:3F`. The registered Android OAuth client uses the original key, so Google sign-in fails until a matching Android client is added (see `GOOGLE_SETUP.md`). Failures now show the real reason and code inline with **Retry**, not just "cancelled".
- **Settings card and dictation button redesigns** exist as mockups only. They are **on hold** awaiting approval and are not in this build.
- **Icon.** The launcher icon is the approved "waveform V, dark" design (adaptive, with a monochrome layer).

### Samsung: keep the bubble after a restart

Labels below are from One UI 6/7; older versions differ slightly.

1. **Unrestricted battery:** Settings → Apps → VoiceFlow → Battery → **Unrestricted**. Or tap **Allow** on VoiceFlow's "Keep the bubble running" card.
2. **Not a sleeping app:** Settings → Battery (or Battery and device care → Battery) → **Background usage limits**. Remove VoiceFlow from **Sleeping apps** and **Deep sleeping apps** (tap the list, select VoiceFlow, then Remove). Then open **Never sleeping apps** → **+** → VoiceFlow → Add. You can also turn off **Put unused apps to sleep**.
3. **Accessibility:** Settings → Accessibility → **Installed apps** (or Installed services) → **VoiceFlow bubble** → On. If it is greyed out: Settings → Apps → VoiceFlow → ⋮ → **Allow restricted settings**, then try again.
4. **Shortcut to turn it back on quickly:** in the same VoiceFlow bubble screen, turn on **VoiceFlow bubble shortcut** and choose **Accessibility button** (or "Press side and volume up keys"). After a restart, a single press turns the bubble back on if One UI switched it off.
5. Optional: Settings → Apps → VoiceFlow → **Remove permissions if app is unused** → Off.

Historical documentation follows.

# VoiceFlow 1.9.13 preview

Cloud speech always auto-detects the spoken language. The manual English, Bengali, Hindi, Nepali and Spanish choices are removed. Saved language selections no longer steer recognition. The optional Write in English letters switch remains.

For the first phone check, use Settings → Transcription → Cloud → Large v3, with AI cleanup and Write in English letters Off. Dictate a clear, complete English sentence, then a Bengali sentence. This isolates speech recognition from text processing. Auto-detection can still confidently choose the wrong language; this update does not guarantee recognition accuracy.

AI cleanup is limited to punctuation, capitalization and paragraph breaks. Changed word sequences, chatbot answers, refusals and censorship are rejected, keeping the original/basic-cleaned transcript with a warning. English-letter conversion is checked word by word against an on-device pronunciation reference, preserving existing Latin words and numbers. Its pronunciation check is conservative and approximate; legitimate spellings can be rejected, and it is not a semantic guarantee. Checked conversion requires Android 10 or later. It does not translate.

Whisper requests use zero temperature and confidence metadata, without language or personal spelling prompts. Clearly low-confidence or likely non-speech/repetition segments reject the whole recording with a retry message. These scores are not a calibrated language-confidence measure; confidently wrong recognition remains possible. Custom speech models may not provide these scores. Personal spelling replacements still apply locally after recognition.

Android speech requests supported high-precision automatic language switching and unmasked offensive words. The phone service may ignore these settings; older Android versions use the phone language. Offline and password/PIN rules are unchanged. Daily recaps remain separate from dictation output.

Read VALIDATION-1.9.13.md for actual verification and limits. Original 1.9.10 source remains in baseline/. Historical documentation follows.

# VoiceFlow for Android

**Current work: 1.9.10.** See `RELEASE_NOTES-1.9.10.md` and `VALIDATION-1.9.10.md` for this update. The following 1.9.9 recovery description is historical; its results are not new-version test passes.


**Rebuilt checkpoint:** 1.9.9 reapplies the selected native Daybook design to the preserved, corrected 1.9.7 source. It is a new build, not a bit-for-bit restoration of the lost 1.9.8 artifacts. Fresh Android CLI build/signing and baseline packaging comparison passed. Standalone Android CLI lint passed with no errors and 26 warnings. Native tests remain pending; historical passes do not establish native behavior for this APK. The signed 1.9.5, 1.9.6 and 1.9.7 checkpoints remain preserved. The earlier 1.9.6 app ANR is unresolved. See `RECOVERY.md` and `VALIDATION.md` for recovery provenance and version-specific results. Real Google sign-in, upload and restore remain unverified.

New backups exclude provider API keys. They retain provider/model/endpoint settings. Restore never imports keys from older archives; a working key on this phone is retained only when the complete API endpoint matches. Reconnect your provider on a new phone or after restoring a different endpoint. No real API keys, Google tokens or Web client secret are embedded in the APK.

A native Android dictation app inspired by Wispr Flow. This is an independent app, with original branding and UI. It is not affiliated with Wispr and does not include its proprietary speech models.

## Install on your phone

1. Download and open the provided `VoiceFlow-1.9.10.apk` on your Android phone (Android 8 or newer); the source archive also includes the same APK under `prebuilt/VoiceFlow.apk`.
2. If Android asks, allow your browser or Files app to install this APK.
3. Open VoiceFlow, tap the top-right **Settings** sliders icon, then **Permissions → Microphone**.
4. For typing in other apps, follow the floating mic or floating keyboard setup below.
5. History is the main screen. Settings stays reachable from the top-right sliders icon; there is no bottom navigation.

## Type in other apps

**Floating mic:** Open VoiceFlow → Settings → **Permissions → Floating microphone**. Read the access explanation, then enable **VoiceFlow bubble** in Android Accessibility settings. If Android blocks a sideloaded service, open Android Settings → Apps → VoiceFlow → the three-dot menu → **Allow restricted settings**, then return to Accessibility. Exact labels vary by phone.

Tap a message or note field in another app, or open Finder search in your launcher. One small glass microphone appears on the right. Tap to start recording; a compact waveform/timer sits to its left and Finish replaces the mic. Tap Finish to stop and keep your words in the selected input. After recording, the controls return to one bubble. There are no floating Delete or Enter buttons.

Hold the bubble to toggle your usual typing keyboard whenever you need to edit, erase, insert a newline or use the input's normal action. The bubble has distinct keyboard-visible and keyboard-hidden marks, and announces its state to screen readers. Your choice persists across inputs, apps and VoiceFlow service restarts. If Android dismisses a visible keyboard with Back, another hold opens it. Password and PIN fields temporarily open ordinary typing without changing your saved choice; the side mic remains available for an explicit tap. Leaving the input hides the bubble; selecting another brings it back.

Email fields open ordinary typing automatically and keep the side microphone available. Holding the bubble can hide the keyboard for that email field; focusing another email field opens typing again. Password and PIN fields also open ordinary typing automatically and keep the side mic available. Focusing an input never starts recording. These automatic changes do not alter your saved normal-field keyboard choice. Detection uses Android's input type; a web page that does not expose its email/password type may be treated as an ordinary text field.

The floating controls use warm Daybook surfaces, the coral VoiceFlow mark, a distinct keyboard-state badge and a coral Finish button. Modest artwork transitions and optional haptic feedback acknowledge taps without moving the touch target. Disabled system animations leave the controls still. Each button has its own surface, with clear spacing and a transparent surround. Drag the mic or waveform area to move them. The mic stays anchored as controls expand, they fit above the typing keyboard, and they avoid covering a selected input when there is room above it. Settings → **Bubble size** keeps **Small**, **Medium**, and **Large** touch sizes. Language stays in Settings.

VoiceFlow stays hidden on the idle home screen, including launchers that retain input focus there. Opening Finder search shows the floating microphone and allows dictation directly into its search field. Closing Finder hides the controls again. This uses visible keyboard windows and VoiceFlow's own input-window lifecycle to distinguish an opened search from stale home focus, without relying on a launcher name or search label. Your saved keyboard visibility choice applies in Finder too.

The voice keyboard writes through its own input connection on Android 8+. It never opens the in-app recording popup. Clipboard copying is a recovery fallback when the original input is unavailable or rejects insertion. The floating-mic Accessibility service must be enabled to display the floating controls. If it is disabled, VoiceFlow shows a setup reminder and reserves no keyboard panel; there is no docked input view.

## Password and PIN dictation

Tap the side mic yourself to dictate into a password or PIN input. Recognized words appear only when recording finishes, through one native commit into the original selection; the app does not read the existing credential or replace the whole field. Changing the input, moving the selection or manually editing it prevents that final write. Password text uses the recognition result without capitalization, punctuation, dictionary replacement or AI cleanup. PINs accept digits or individual English digit names, preserving leading zeros: `zero one two three` becomes `0123`.

Private credential dictations skip History and the retained draft, so they are not included in VoiceFlow backups or daily recaps. Personal dictionary hints are also excluded from transcription requests. If the result cannot safely be inserted, VoiceFlow copies the captured words to a clipboard marked sensitive; it does not save a private recovery entry. Successful insertion leaves the clipboard unchanged. A pending result is recovered during input-service teardown instead of being dropped, including the short wait for native insertion acknowledgement.

Private direct insertion is supported through the selected VoiceFlow IME on Android 8/API 26+, and alongside another typing keyboard through the Accessibility input connection on Android 13/API 33+. If a compatible connection is unavailable, the app asks you to refocus or use VoiceFlow as the input method rather than reading or rewriting the password field.

The selected speech engine still processes what you say. Cloud mode uploads the recording to your configured provider after Finish; Android speech may process audio online. On-device mode requires a supported offline language pack.

## Native Daybook, compact Settings and daily History

The rebuilt native Home and Settings implement the selected **Copper + Sand · Woven Linen** design. Paper and warm surface colors support both light and dark Android themes. A faint, repeating linen texture is drawn from a cached tile; it does not capture the screen underneath. Home combines the original coral VoiceFlow mark, a **Your days** heading, the current month and a newest-first timeline. The top-right sliders icon opens Settings; Settings has a back control. There is no bottom navigation.

The floating controls now share the warm Daybook palette, original flow mark and coral Finish action. The selected Daybook HTML remains a design reference with sample history and simulated controls. Native Home reads actual saved dictations, and native Backup & restore opens the real Google integration.

Settings shows direct rows for **Transcription**, **AI cleanup**, **Language**, **Bubble size** and **Personal dictionary**, plus a visible **Haptic feedback** switch and **Google Drive backup** card. Tapping a row opens a focused native panel. There are no expanding Settings section headers. **Permissions** provides interactive microphone, floating-service and optional-keyboard status. API connection, history privacy, daily recaps and Clear history live under **Advanced · Privacy & connection**. The Show bubble switch sits beside bubble size and haptics. Opening Settings preserves existing provider and model choices, including custom model IDs.

Direct rows are built to activate on the first tap rather than consume it for focus. Returning from a panel restores focus to its originating row. This implementation requires fresh native first-tap verification; a second-tap workaround is not a pass. Native ripple feedback and a modest 160 ms transition make interactions responsive. Transitions respect Android's disabled animation setting. Optional haptics apply to Daybook controls and floating record/Finish taps; actual vibration strength depends on the phone.

The **Transcription** panel separates Android, On-device and Cloud speech modes from the Large v3/Turbo transcript model. The **AI cleanup** panel exposes Basic cleanup, AI cleanup and the cleanup model directly. Choosing a size or language saves it and returns to Settings. Other model/mode choices stay in their panel so related options remain easy to reach. The **Personal dictionary** panel lists saved replacements with Remove controls and separate Heard spelling/Preferred spelling fields. Add word saves a replacement without editing a code-like text block.
History groups saved VoiceFlow dictations by your phone's local calendar day, including daylight-saving changes. The newest day appears first, with the newest dictations first within each day. Each day has a brief AI recap and a timeline. Today's timeline starts open; older timelines start folded. Tap a day to show or hide its timeline. Tap an entry to expand its complete text and reveal Copy and Delete. Day and entry expansion survives Activity recreation. Recap analysis retains chronological input, so the display change preserves existing cached recaps.

Daily recaps use the exact **AI cleanup → Cleanup model** selected in Settings, including custom model IDs. OSS 20B remains the default. The **Show daily recaps** switch under **Advanced · Privacy & connection → History** is separate from AI cleanup. With a saved API key, daily summaries enabled, and speech mode other than On-device, opening History generates missing recaps in the background. This sends that day's saved dictated text to your configured AI provider and uses its normal API billing. It does not collect other conversations or activity.

Recaps are cached on this phone. Reopening unchanged History makes no additional request. Adding, editing, or removing entries, changing the model or endpoint, or changing the phone's timezone invalidates the affected recap. Very long days are summarized in complete chunks before those recaps are combined; dictations are not silently truncated. Missing keys or request failures leave the original timeline readable, with a Retry option for failed recaps. Leaving History cancels outstanding summary work. On-device mode makes no summary requests, but existing cached recaps remain readable.

Deleting a dictation removes any cached recap that contains it. Clear history deletes both saved dictations and daily recaps. Day recaps cover the saved VoiceFlow dictations for that day and may need checking against the original timeline.

## Optional Google Drive backup

Open **Settings → Google Drive backup → Backup & restore → Connect Google**. Choose an account in Google's native account/consent screen. VoiceFlow requests only its own private Drive app-data storage; it does not request access to your other files. Google Play services must be available and up to date. The Drive app and a file picker are not required for the new connection.

On a new account, create and confirm a separate backup password of at least eight characters. If VoiceFlow finds existing backups, enter their password first and choose **Keep this phone's data** or **Restore**. Connecting never uploads empty history or enables automatic backups by itself. Account changes or cancellation preserve the previous connection until the new setup succeeds.

Use **Back up now** for a manual backup. A fixed status panel stays visible while the settings scroll, immediately shows the active operation, and displays its real completion or error with a Details action. Progress is indeterminate because the transport does not report an accurate percentage. Automatic backup is **Off** by default; opt into **Daily** or **Weekly**, with **Wi-Fi only** enabled by default (Android's unmetered-network rule). Android can delay scheduled jobs. A saved backup is uploaded as a new encrypted generation, downloaded and byte-checked, then marked complete. The previous completed generation stays available until the new one is verified. Successful backups keep the latest three completed generations. An interrupted upload can leave an unfinished encrypted generation; these are excluded from restore and are not currently cleaned automatically.

Use **Restore from Google Drive** to discover backups in the selected account, choose the latest or an older generation, enter its password and confirm before replacing this phone's data. Restoring does not silently opt into automatic backup. An older valid generation can be restored even when the latest cannot be unlocked; uploading and automatic backup stay paused until the latest backup password is verified. Finish active dictation before restoring.

Deleting or clearing saved dictations also removes a matching retained draft, so deleted text cannot reappear in a newly created backup. Unrelated unsaved draft text is preserved. If Drive contains corrupt or unsupported committed generations, VoiceFlow warns you and offers valid older backups for explicit restore. New uploads and password adoption stay blocked until the listing is complete; rejected generations are not deleted.

The portable AES-GCM password-encrypted archive contains saved ordinary dictations/raw transcripts, dictionary, settings (including haptic feedback), provider/model/endpoint choices and retained draft. It excludes provider API keys, audio, cached recaps, device permissions, Google account/access tokens, Drive connection state and device-bound Keystore values. Keys inside older archives are ignored during restore. A local provider key is kept only when the restored API endpoint matches; otherwise reconnect your provider. Private password/PIN dictations are excluded upstream. The archive is limited to 8 MB. Backup passwords are 8–1,024 characters and cannot be recovered by VoiceFlow or by signing into Google.

For automatic backups, this phone remembers the backup password separately under Android Keystore. AuthorizationClient obtains short-lived tokens for the saved account; VoiceFlow does not save access or refresh tokens. Expired/revoked consent pauses automatic backup and displays **Reconnect Google**. **Disconnect backup** stops jobs and forgets the local connection and backup password, while leaving history/settings and remote backups intact.

An upgrade retains a previously selected file-provider backup until native Google setup succeeds. **Import previous backup file** appears only for that migration path. These older encrypted archives remain compatible; importing validates them before replacement confirmation.

The native integration uses Google Identity Services `AuthorizationClient` plus Drive v3 HTTPS. It does not use Credential Manager, Firebase Authentication, Analytics or a developer backend. Registration/setup evidence and the remaining real-account checks are recorded in `GOOGLE_SETUP.md`. Offline tests do not independently verify the Google Console configuration or actual sign-in/upload/restore.

## Speech modes

| Mode | API key | Network behavior |
|---|---|---|
| Android speech (default) | None | Uses the configured Android speech provider; it may process speech online. |
| On-device only | None | Requires Android 12+ and an installed, supported speech model/language pack. No online fallback or AI cleanup. |
| Cloud transcription | Your own | Records AAC/M4A and sends it to your configured HTTPS provider after you stop. Inserts directly in the field when transcription completes. |

English, Bengali, Spanish, and Hindi language selections are included. Availability and mixed-language accuracy depend on the selected speech engine. Android speech sessions are chained while recording, so short pauses do not immediately end your overall dictation. Sessions stop after five minutes. The floating mic uses an Android microphone foreground service and a recording notification; the separate in-app recording panel stops when it leaves the foreground.

Cloud microphone setup, start, stop, level sampling and audio-file cleanup run on one serialized background worker; status and result callbacks return to the main UI thread. The recorder reports **Starting microphone…** until hardware startup completes, then **Listening…**. Finish remains available during startup: an early Finish waits for setup on that worker before ending the recording and starting transcription. Teardown releases the recorder and cleans temporary files through the same owner, keeping native microphone calls off the UI thread.

## Whisper Large v3 transcription and optional AI cleanup

Open Settings → **Advanced · Privacy & connection → Use Groq defaults**. Create your own key at https://console.groq.com/keys, paste it into the API-key field in the app, and Save. The preset selects cloud transcription and fills:

- API base: `https://api.groq.com/openai/v1`
- Transcription model: `whisper-large-v3`
- Optional cleanup model: `openai/gpt-oss-20b` (low reasoning effort)

The Settings option panels use short names:

| Section | Name | Model ID |
|---|---|---|
| Transcript | Large v3 (default) | `whisper-large-v3` |
| Transcript | Turbo | `whisper-large-v3-turbo` |
| Cleanup | OSS 20B (default) | `openai/gpt-oss-20b` |
| Cleanup | OSS 120B | `openai/gpt-oss-120b` |
| Cleanup | Instant | `llama-3.1-8b-instant` |
| Cleanup | Versatile | `llama-3.3-70b-versatile` |

Groq’s [deprecation notice](https://console.groq.com/docs/deprecations) lists August 16, 2026 as the shutdown date for Instant and Versatile on free and developer tiers. The exemption specifically covers enterprise customers with committed-spend contracts. They remain selectable for accounts with that access. OSS 20B is the default for fast formatting on regular Groq accounts. The API dialog includes **Custom** for other compatible providers/models. Changing models in Settings does not change your endpoint or send requests immediately.

Whisper Large v3 transcribes your recording. Tap **Finish** and the completed transcript is inserted directly into the active field. This upload API does not provide live words during recording. Use Android speech or On-device only for live partial words instead. AI cleanup is a separate switch for punctuation, clear self-corrections, and dictated lists; it preserves your meaning and language. Add names and uncommon terms to the personal dictionary; correct spellings are sent as transcription hints and applied to the output. Pick the actual speech language in Settings for better recognition.

Existing provider settings are preserved. Changing hosts requires a new key, so an existing credential is not silently sent elsewhere. **Advanced · Privacy & connection → API connection** also supports compatible HTTPS providers using Custom model IDs. API usage follows your provider's billing and limits. Version 1.9.3 regression checks intercept HTTPS rather than using a live account; account/model availability depends on your provider. Wispr Flow accuracy parity has not been measured.

There is no built-in speech API key, developer server or subscription. Google Drive connection is optional. Speech API keys are encrypted using Android Keystore, and Android OS app backup is disabled. The key field never displays an existing key. Changing the endpoint requires a new key to prevent sending an existing credential to another host.

Without AI cleanup, **Basic cleanup** removes `um` and `uh`, capitalizes the first letter, and handles spoken English punctuation commands: `comma`, `question mark`, `exclamation mark`, `full stop`, `colon`, `semicolon`, `new line`, and `new paragraph`. It does not interpret self-corrections. These words are treated as commands when cleanup is enabled; turn cleanup off for literal dictation.

The personal dictionary saves heard/preferred spelling pairs, such as `jewel` → `Juel`. Replacements are case-insensitive and respect word boundaries. Add word accepts up to 80 characters per field, rejects empty/duplicate heard spellings and excludes line breaks or `=` within a spelling. Existing stored replacements remain compatible. The portable dictionary is limited to 100,000 characters.

## Privacy and limitations

- No ads, analytics, background screen collection, or developer-operated backend.
- The bubble checks focused-field metadata to decide when to appear. It reads the selected field's text and cursor only during dictation you start. Field text is never uploaded by VoiceFlow.
- Ordinary live dictation checks its original field, text and cursor before each insertion to protect manual edits. Private credential dictation avoids existing-text reads and commits only the final result. The app does not send messages for you.
- Ordinary finished transcripts, including raw recognition text, are stored privately on your phone, up to 100 entries. Private password/PIN dictations skip History and retained drafts. Disable future ordinary history saves in Settings; delete saved entries in History.
- Audio files are temporary and removed after transcription or recording failure. Closing a recording finishes its captured audio. Provider-side retention is governed by the selected provider.
- The floating recording control keeps the current app and input active; the typing keyboard follows your saved visibility choice, with temporary typing for email/password/PIN inputs. It writes only to the original field. Changing fields recovers the completed transcript to the clipboard without writing into the new input; ordinary dictation also saves to History when enabled. Private credential recovery skips History/draft and marks the clipboard sensitive. Screen lock, recorder dismissal and input-service teardown preserve captured words through the recovery path when recognition can complete.
- Custom editors can refuse text APIs. The direct editor connection on Android 13+ and the VoiceFlow keyboard improve compatibility, but app-specific or OEM behavior still needs checking on your phone.
- This is a signed preview APK, not a Play Store production release. Real microphone/language behavior, actual Niagara, Samsung/Android 17 editors and Google Drive cloud sync need testing on your phone. The current regression checks use mock HTTPS and offline Drive responses; they are not real-world speech accuracy or Drive-sync benchmarks.

## Build the source

For the restored official-toolchain command-line build used by this checkpoint, see `tools/README.md`. Its verification receipts remain separate from native test and lint results.

Open this folder in Android Studio, with JDK 17, Android SDK 35, and Build Tools 35.0.0. Sync Gradle and build the `app` module. Or run:

```sh
./gradlew assemblePreview
```

The build wrapper resolves the Google Play services dependencies through Gradle:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk ./build-apk.sh
```

It creates `build/VoiceFlow.apk`. The distributed source archive excludes the preview signing key. To reproduce the preview signing identity, set `VOICEFLOW_KEYSTORE` to the separately retained original preview keystore before building. This key is only for personal test builds; never use it for a public production release. Gradle's normal debug build uses its own debug key; Android cannot install it over this preview unless signing is configured to match. Uninstalling removes local history.

Core text checks (JDK required):

```sh
mkdir -p build/tests
javac -encoding UTF-8 -d build/tests app/src/main/java/app/voiceflow/Cleaner.java app/src/main/java/app/voiceflow/TextInsertion.java app/src/main/java/app/voiceflow/LiveInsertion.java app/src/main/java/app/voiceflow/EditorInsertion.java app/src/main/java/app/voiceflow/BubblePlacement.java app/src/main/java/app/voiceflow/HistoryDays.java tests/HistoryDaysTest.java tests/BubblePlacementTest.java tests/CoreTest.java tests/LiveInsertionTest.java tests/EditorInsertionTest.java
java -cp build/tests app.voiceflow.CoreTest
java -cp build/tests app.voiceflow.LiveInsertionTest
java -cp build/tests app.voiceflow.EditorInsertionTest
java -cp build/tests app.voiceflow.BubblePlacementTest
java -cp build/tests app.voiceflow.HistoryDaysTest
```

The fixture sources describe version-specific checks, not automatic proof that the current APK passes them. Historical 1.9.7 native results and earlier failures are recorded in `VALIDATION.md`. The rebuilt 1.9.9 run must install and launch its exact signed APK, exercise the changed UI and keep a clean version-specific app crash/ANR interval. The lost 1.9.8 run is not available as reproducible current evidence.

`DaybookUiTest.java` covers the rebuilt native Home/Settings with synthetic history, one physical first tap per direct Settings row, panel return focus, option persistence, dictionary Add/Remove, day/entry recreation and real backup controls. It also covers the portable haptic setting and captures actual native screens. Those captures must be labelled with the tested APK version/hash and kept distinct from the selected HTML design reference. Fresh results are pending.

`SidePreview.java` exercises the single-bubble gesture/insertion path, actual typing-keyboard Delete/Return editing, persistent keyboard visibility and focus hiding. `LauncherTest.java` describes stale Home focus, opened Finder dictation and persistent keyboard choice. `DeleteHoldTest.java` records historical floating-Delete behavior; the current app uses the regular keyboard for editing. `RecoveryTest.java` checks recorded audio through mock Whisper HTTPS after recorder dismissal and screen lock, plus retained speech segments after recognition timeouts. `InputRecoveryTest.java` checks original-field ownership, manual edits, clipboard fallback and insertion alongside a regular typing keyboard. `AppScreensTest.java` describes the older Settings/History UI and needs adaptation for Daybook. `KeyboardStateTest.java` checks direct hold toggling and the observed keyboard indicator. `AuthFieldTest.java` checks automatic typing with explicit final-only credential dictation. `ComposeDeleteTest.java` checks composing spans through a real Accessibility input connection. `DriveCloudTest.java` exercises production Drive request, encrypted backup/restore, account, rollback and scheduling code with offline HTTPS responses. The former file-provider backup UI/integration fixtures are historical and require the 1.9.4 implementation. They do not establish current Google sign-in or Drive behavior.

Current offline Drive fixtures have a reproducible builder at `tests/build-drive-fixture.sh` and isolated-device instructions in `tests/drive/README.md`. Archive and credential-policy fixtures use `tests/build-backup-fixture.sh` with `tests/backup/README.md`; the single-bubble input fixture uses `tests/build-side-fixture.sh` with `tests/side/README.md`. Activity draft-recreation checks use `tests/build-draft-fixture.sh` with `tests/draft/README.md`. Native Daybook checks use `tests/build-daybook-fixture.sh` with `tests/daybook/README.md`. These builders compile against the actual production preview classes and package only fixture code. The older `BackupIntegrationTest` and `BackupUiTest` sources describe the historical document-picker implementation; they are not the new cloud-backup suite.

The remaining historical native fixture sources and `tests/fixtures/AndroidManifest.xml` are included, but their standalone QA build/setup scripts are outside this source archive. These fixtures are not a wired Gradle `androidTest` target. To reproduce them, build a separate fixture APK against the production classes and Android SDK, process it with aapt2/d8, sign it with the same certificate as the app, and install both packages on an isolated emulator. Most input suites also require the Accessibility service, selected VoiceFlow IME and fixture speech recognizer. Fixture results distinguish synthetic recognition and intercepted HTTPS from real microphone recordings or live provider tests; launching every fixture without its setup is not an equivalent validation run.

## Reference documentation

- [Google Drive authorization](https://developer.android.com/identity/authorization)
- [Google Drive app data](https://developers.google.com/workspace/drive/api/guides/appdata)
- [Android speech recognition](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android input methods](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)
- [Android soft keyboard controller](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.SoftKeyboardController)
- [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [OpenAI speech-to-text API](https://developers.openai.com/api/docs/guides/speech-to-text)
- [Groq speech-to-text](https://console.groq.com/docs/speech-to-text)
- [Groq model availability](https://console.groq.com/docs/models)
- [Groq model deprecations](https://console.groq.com/docs/deprecations)

## Code map

`MainActivity` owns the native Daybook Home, direct Settings panels, sliders/back navigation, dictionary editor, day/entry expansion and separate Home/Settings scroll positions. `Daybook` contains the Copper + Sand light/dark palette, cached linen background, native mark/sliders icon, ripple, motion and optional haptic helpers. `BackupActivity`, `DriveAuth`, `DriveCloudClient`, `DriveBackup`, `BackupArchive`, `BackupData` and `BackupJob` handle optional native Google Drive authorization, encrypted backup, validation, restore and scheduling. `CaptureActivity` is the separate in-app recorder. `Dictation` owns microphone and transcription lifecycles. `CloudApi` implements HTTPS uploads, dictionary hints, and AI cleanup. `FlowAccessibility` owns the field-aware vertical controls and Home/Finder visibility, persistent keyboard visibility, tap/hold/drag gestures, and contextual floating keyboard controls. `FloatingIcon` draws its icons without bitmap assets. `Glass` draws the floating translucent surfaces, with gradient shaders cached when bounds change. Home, Settings and Backup use the separate Daybook theme. `AccessibilityEditor` supplies Android 13+ editor access alongside another keyboard. `FlowKeyboard` is the headless voice input method with direct insertion; it has no docked input view. `BubblePlacement` keeps the mic anchored at the right as attached controls expand and clamps them to the available screen. `EditorInsertion` owns only the dictated text range; `LiveInsertion` preserves surrounding text for the older Accessibility fallback. `BubbleSize` controls the three sizes. `Vault` encrypts the provider key. `Cleaner` and `TextInsertion` contain testable text rules.
