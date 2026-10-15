# Validation — VoiceFlow 1.9.9 rebuild

Updated October 4, 2026 UTC. Rebuilt from the final saved 1.9.7 package. The corrected 1.9.8 source/APK/raw logs were lost; earlier chat reports are not evidence for this binary. The new build is version 1.9.9/code 19, package app.voiceflow, signed with the retained custom Preview certificate.

## Current changes and source review

Native Copper + Sand/Woven Linen Daybook Home/Settings and backup presentation are implemented. History is descending, Settings uses compact direct rows, dictionary Add/Remove is native, and haptics are optional. Settings rows omit touch-mode focusability; explicit return focus uses requestFocusFromTouch(). Long-hold defers the control rebuild until Android can emit its one framework long-press feedback. Physical motor behavior is not established by source review.

API Save/Remove transactions remain byte-identical to 1.9.7. Independent comparison verified 20 backup worker/security/lifecycle methods unchanged and five consent paths unchanged after styling normalization. Credential exclusions, atomic credential/endpoint saves, draft invalidation, deletion/Clear, rejected-generation guards, cancellation and restore confirmation remain preserved. Source review is separate from runtime proof.

## Fresh build evidence

| Stage | Current result |
|---|---|
| Official JDK 17 / Android SDK 35 restoration | PASS; published checksums and tool receipts retained |
| Gradle assemble/lint tasks | BLOCKED; automatic approval review rejected the distribution redirect; no Gradle pass claimed |
| Fresh Android CLI aapt2/javac/D8/zipalign/apksigner | PASS; source hashes stable before/after compilation |
| Baseline manifest/resource/library packaging comparison | PASS; same declarations except version, 230 resource names, 223 non-app values, 80 resource payloads, 26 metadata and 10 runtime files |
| APK v2 signature / package / version | PASS; app.voiceflow, code 19, version 1.9.9, inherited Preview certificate |
| Pure Java core suites | PASS — five freshly compiled suites; CoreTest reports 29 cases plus word-count checks, other suites emit no numeric total |
| Five native fixture builds/signatures | PASS — final production classes used only as classpath; no production classes packaged |
| Standalone CLI lint | PASS — exit 0, zero errors, 26 warnings; all 33 Java files, five resource XMLs, merged manifest, generated R/classes and 34 dependencies covered; different from Gradle lintPreview |
| Installed APK identity / app launch | PASS — installed APK pulled and SHA-256 matched; cold am start -W and actual resumed MainActivity verified |
| Native Daybook initial fixture | FAILED — seven checks, zero taps; fixture disabled recap display while expecting a visible card. Corrected fixture retest pending; Settings first taps not reached |
| Credential policy / archive / history deletion | PASS — 29 / 41 / 17 fresh native checks, stage runtime health passes |
| Rejected-generation listing / same-process draft recreation | PASS — 82 / 8 fresh native checks, stage runtime health passes |
| Separate-process draft restore / Delete / Clear | PASS — seed/verify 7/9, 8/9, 8/9; actual process stop and different PID |
| Full cloud lifecycle / partial listing recovery | PASS — 165 / 29 offline checks; fake HTTPS, not Google |
| Backup screen | PASS — six native handler/UI checks; native captures exported; physical progress response not established |
| Floating controls | BLOCKED — initial microphone lookup failed while a pre-existing System UI ANR dialog covered the screen |
| Version-specific app/fixture log scan | Zero matched crashes/ANRs; the environment was contaminated by the System UI dialog, so the full interval is not a clean runtime pass |
| Corrected Daybook first-tap checks / Home and Settings captures | PENDING — contradictory fixture corrected; targeted rerun requires visible system-modal recovery |
| Real Google authorization / Drive upload / restore | NOT EXERCISED; AOSP test image lacks Google Play services |

APK SHA-256: `2bdb49cefdd5235de2670eab04bfe0b73fee51ca73a43df42bbf69e582af0270`.

Certificate SHA-1: `68:24:BC:2C:9F:CF:54:26:6D:DB:53:DA:B1:05:7D:00:17:82:3F:FC`. Exact source: Signer #1 of the new 1.9.9 APK through SDK 35.0.0 apksigner. Certificate SHA-256: `56d07d6216174d9ecaf6314131e3f4853592ab6c4f031ef368eac8b0deaef0ae`. This is a custom development Preview certificate, not a production, upload or Play certificate.

CLI lint warnings are retained: 14 in unchanged files, two inherited statements in changed files, eight new UI warnings (seven localization and one programmatic view-constructor warning), and two CLI version-model warnings. The APK version is independently verified. Intermediate cache/setup/tool-resolution failures remain recorded as tooling failures, distinct from the final successful analysis.

## Current native environment failure

The full 1.9.9 runner completed and its original failures remain retained. Its API 35 health probe used `dumpsys window windows`, which omitted global focused-window information. Actual global `dumpsys window` plus a native screenshot identifies `Application Not Responding: com.android.systemui`. DropBox records the System UI ANR at 16:55:36 UTC, before VoiceFlow installation around 17:18 UTC. Zero app log matches did not make this a clean environment. Read-only diagnostics, original receipts and screenshots are preserved in evidence/1.9.9-systemui-diagnostics. No Phone/Bluetooth disabling, global error-dialog hiding or emulator restart was performed. A screenshot-verified, exactly-once Wait tap cleared the modal at 2/10/30-second observations; no new System UI DropBox entry appeared in that bounded observation. The corrected Daybook attempt then stopped before installation or taps: its read-only app_process hierarchy helper exited 137 with only “Killed”. This is a harness blocker, not a product regression. A new global-window/native-screenshot guard is being prepared; the original helper and blocked receipt remain preserved.

## User-reported phone follow-up

The user reports Google backup working on their phone; this is not independent live Google verification. The user also reports the old floating microphone, absent haptics/animation and unclear Settings/backup progress. The 1.9.9 floating graphic still uses the older neutral microphone. These requested UX corrections are queued for the next preview and are not claimed in this APK.

## Limits

No lost 1.9.8 results are assigned to this rebuilt binary. A native UI PASS requires both a completed fresh fixture receipt and a clean version-specific crash/ANR interval. A second-tap workaround is never a first-tap pass. The actual historical 1.9.6 input-dispatch ANR remains unresolved; later successful checks would not prove its cause was fixed.

Real Google consent, Drive persistence, another-phone restore, grant renewal, OEM editors, actual Niagara, real speech accuracy, physical haptics and dark-mode visual QA remain unverified. Console setup remains user-reported. AuthorizationClient-only, opt-in encrypted backup, exclusion of credentials/audio/private dictations, and restore overwrite confirmation are preserved. No billing, Google project/client creation, release or OAuth publication occurred.

The bundled prebuilt APK now matches the rebuilt 1.9.9 binary. Historical evidence below remains labelled by its original version.

# Validation — VoiceFlow 1.9.7

Updated October 4, 2026 UTC. Signed 1.9.5 and 1.9.6 APK/source checkpoints remain preserved separately, including their test limits at the time of delivery.

## Changes and review

Version **1.9.7**, code **17**, package **app.voiceflow**, addresses the additional source-review findings without replacing the Google integration or chosen design direction.

- Deleting a history item or clearing History removes a retained draft matching the deleted text/raw text under the same `Store.class` lock used by backup snapshotting. Unrelated unsaved text is retained. Recovery and Capture now save history and update the draft atomically. Old remote backups are not deleted by local history actions.
- Drive discovery returns valid older generations plus structured rejected-entry warnings. The query includes future schemas. Zero-size or unsupported-schema committed entries block upload, password adoption and automatic backup; they cannot make an account appear empty. Explicit older restore remains available and keeps uploads paused. Strict legacy listing callers still receive a typed rejection. Invalid entries are never deleted, malformed responses/pagination failures remain fatal, and other-account discovery does not pause the saved account.
- Draft state now has process-session/revision metadata plus a local invalidation epoch. Restore, history deletion and Clear update that epoch in the same preference editor as the data mutation; older Bundles are rejected after process restart as well. Matching fresh saved state remains recoverable. No extra durable draft text is stored and the epoch is excluded from backups. The frozen 1.9.6 native test confirmed the same-process bug with six checks: the stopped parent was recreated using its exact pre-restore Bundle A and overwrote restored draft B. This used local synthetic restoration, not Google or remote Drive. The persisted extension now passes all three actual process-stop/new-PID restore/delete/Clear replay cases.
- Credential exclusion, local same-endpoint key retention, legacy-key stripping, cancellation classification and atomic API connection saving from 1.9.6 remain in place. Source peer review found no additional material blocker in these paths.

## Build checks

Final 1.9.7 Gradle **assemblePreview** and full **lintPreview**: **PASS** in 16 seconds, with 0 errors and 24 existing warnings. All four final portable fixture builds pass against these classes; DEX inspection confirms zero bundled production classes and matching preview signatures. Independent test review strengthened older-data replacement and clean-snapshot rollback assertions. Test packages use production classes as classpath inputs.

Android build-tools 35 `aapt2 dump badging`: **app.voiceflow**, **1.9.7 / 17**, minimum API **26**, target/compile API **35**. `apksigner verify --verbose --print-certs`: **PASS**, v2 signature, existing custom development/preview signer.

APK SHA-256: `6c2ded08d7e43f058bbde4a5d549c2729ba80eefb59b96326ccb58757314d476`.

Certificate SHA-1: `68:24:BC:2C:9F:CF:54:26:6D:DB:53:DA:B1:05:7D:00:17:82:3F:FC`. Exact source: Signer #1 of the signed 1.9.7 delivery APK via `apksigner`. Certificate SHA-256: `56d07d6216174d9ecaf6314131e3f4853592ab6c4f031ef368eac8b0deaef0ae`. It is a preview certificate, not a production/upload/Play certificate.

## Native results in progress

The corrected deadline-only emulator wait recovered boot at **572.6 seconds**. Package health passed. A targeted Phone/Bluetooth isolation attempt was limited to this disposable AOSP API 35 AVD. Disabling Phone left its restarting process without its provider and caused additional system failures; that test-environment change was reversed. Error dialogs were not globally suppressed.

The frozen **1.9.5** follow-up **PASSED 58 offline cloud lifecycle checks**, including the worker/busy/interruption case, remote failure preservation, retry/reconnect, pinned-account isolation, disconnect and older-archive recovery. Its Backup UI **PASSED 6 native checks** again, and two valid captures exported. The worker did not exceed the former 60-second diagnostic threshold in this run. The original latch failure's exact cause remains unproven; its original failed record is retained below. The floating-control rerun stopped at its initial microphone lookup; a real screenshot identifies a System UI ANR modal. This remains an inconclusive app regression result. This is not a full-suite rerun of 1.9.5 and is not live Google testing.

The frozen **1.9.6** APK installed and launched. Its full cloud fixture **PASSED 165** offline production transport/lifecycle checks, its credential-policy fixture **PASSED 29**, its archive fixture **PASSED 41**, and Backup UI **PASSED 6**. The exact pre-restore saved-state fixture **REPRODUCED** the stale-draft bug in six native checks. After the targeted provider/System UI recovery, SidePreview's behavioral checks passed. Its first saved receipt was truncated despite a complete PASS in session output; a second run obtained a complete receipt with adb/tee exit 0. Runtime health remains a separate failure: the 1.9.6 system interval records an actual `app.voiceflow` input-dispatch ANR, PID 10483 at guest 03:36:18, waiting 5004 ms for touch UP at (372,464). No VoiceFlow fatal crash was found. Phone failures do not establish the cause of that app ANR; the retained trace was extracted later and its timing limitation is recorded.

The final **1.9.7** APK and all four matching fixtures installed; native MainActivity recreation launched the app. All frozen-version stages completed:

| Native stage | Result |
|---|---|
| Rejected-generation listing/protocol | **PASS — 82** offline checks, including zero-size/schema-2 generations beside valid older data and strict safety controls |
| Encrypted archive/schema | **PASS — 41** |
| Credential backup/restore policy | **PASS — 29** |
| History Delete/Clear | **PASS — 17**, including actual newly created encrypted archive round trips without deleted text |
| Same-process PRE-restore Bundle and fresh recreation | **PASS — 8**, stale Bundle does not overwrite B; ordinary recreation keeps B |
| Separate-process restore/delete/Clear | **PASS — seed 7/verify 9, seed 8/verify 9, seed 8/verify 9**; actual force-stop and verified different PID for each pair |
| Full cloud lifecycle | **PASS — 165** offline production checks |
| Partial-listing backend recovery | **PASS — 29**, including distinct current/archived data replacement and clean-snapshot reconnect rollback preserving the upload pause |
| Backup UI | **PASS — 6** native checks; disconnected and missing-Google-services captures exported |
| Floating controls | **PASS** complete physical tap/hold/drag, keyboard persistence, actual typing Delete/Return, recording/finish/insertion, password and focus-loss assertions |
| Installed APK identity | **PASS**, pulled installed APK matches the final signed delivery SHA-256 |
| Version-specific runtime health | **PASS for the collected 1.9.7 interval**: zero VoiceFlow or fixture crash/ANR matches; historical 1.9.6 ANR remains unresolved |

Real Google authorization, actual Drive persistence/upload/restore, another-phone restore, seven-day grant renewal and Google-enabled automatic jobs remain **not exercised / blocked** by missing Google Play services in the emulator. The Console setup retains its user-reported provenance. Billing remains off and no OAuth app or production release was published.

The retained 1.9.6 ANR trace was captured after 1.9.7 installation but identifies the original PID/time and `anr_2026-10-04-03-36-17-763` file. The sampled main stack is Android `InputEventReceiver.nativeFinishInputEvent`/`ViewRootImpl` event acknowledgement, without a VoiceFlow callback or held app mutex. Sampling completed after the timeout, so it cannot reconstruct the earlier stall. CPU pressure was 92.48% and total CPU 99% in the reported period, supporting system-load involvement as an inference only. No precise source fix is established. The 1.9.6 interval remains behavioral Side PASS plus confirmed unresolved app ANR; it is not a clean runtime pass.

Process-restart seed phases flush their preference mutation before stopping the app; they do not simulate physical storage failure of inherited Delete/Clear `apply()`. Valid matching-epoch/legacy saved-state recovery in a new PID is source-reviewed; the default native fixture verifies the matching fresh-state control in the same process. Restored draft text remains ephemeral until a newer Bundle recovers it.

# Preserved checkpoint — VoiceFlow 1.9.6

Updated October 4, 2026 UTC. The signed 1.9.5 APK/source checkpoint is preserved separately; its exact failed and blocked stages remain below.

## Changes and audit

Version **1.9.6**, code **16**, package **app.voiceflow**, retains AuthorizationClient-only Drive app-data access and the existing native design. Daybook remains the chosen design direction; its native implementation is queued and was not replaced by this backup/testing update.

New encrypted backup payloads omit provider API keys and no longer read Vault. Legacy schema 1 archives may be decoded, but validation strips their keys before confirmation and restore. Provider/model/endpoint settings, history, dictionary and retained normal draft remain portable. A local provider credential is retained only when the complete validated API base, including its path, matches the restored base. Changed or invalid endpoints clear the key and IV in the same preference commit as the restored settings. Failed commits restore the prior preference map and draft. A new phone needs provider reconnection.

API connection Save now prepares the encrypted key and writes it with the endpoint/models/engine in one preference snapshot under `Store.class`. Removing a key also clears its envelope and switches speech/cleanup mode in one snapshot. This removes the separate-write restart window that could pair a new key with an old provider endpoint. The independent focused review found no remaining material credential-policy blocker; this is source review, not physical power-loss verification.

The audit found that interruption during latest-archive decoding was classified as a password failure, incorrectly pausing automatic backups. `DriveBackup.validateLatest` now propagates `InterruptedIOException`, and checks the interruption flag before wrapping other I/O failures. Cancellation remains non-retryable and preserves password readiness; an uninterrupted wrong-password failure still requires password setup. Actual socket cancellation can remain bounded by connection/read timeouts rather than completing immediately.

Automatic backup remains opt-in. Restore retains explicit overwrite confirmation. Audio, recordings, private password/PIN dictation, Google tokens and connection state are excluded. No API credentials or Web client secret are embedded in the APK. No billing, Google project/client or OAuth publishing change was made.

## Completed build checks

- Gradle offline **assemblePreview** and full **lintPreview**: **PASS**, 0 errors and 24 existing warnings. An initial local build command omitted the SDK environment and failed before compilation; rerunning with the existing SDK path succeeded.
- Package metadata: **app.voiceflow**, **1.9.6 / 16**, minimum API **26**, target/compile API **35**.
- Android build-tools 35 `apksigner verify --verbose --print-certs`: **PASS**, v2 signature; the existing custom preview certificate is unchanged.
- Portable archive/credential, Drive transport/lifecycle and single-bubble fixture builds: **PASS** against 1.9.6 compiled production classes. Test DEX excludes production classes.

APK SHA-256: `b594651a61f88d818988d0a0bd932d8344c3b1715485e713255023b8062307f4`.

Certificate SHA-1: `68:24:BC:2C:9F:CF:54:26:6D:DB:53:DA:B1:05:7D:00:17:82:3F:FC`. Source: Signer #1 from `apksigner` on the signed 1.9.6 delivery APK. Certificate type: custom development/preview, not Play/release/upload. Certificate SHA-256: `56d07d6216174d9ecaf6314131e3f4853592ab6c4f031ef368eac8b0deaef0ae`.

## Native status when this checkpoint was delivered

This preserved 1.9.6 checkpoint has not yet been installed or launched in the ongoing native retry, and its native fixtures have not executed. The corrected deadline-only emulator wait recovered startup after 572.6 seconds; package-manager health passed and the frozen 1.9.5 APK/fixtures installed successfully. Targeted Phone/Bluetooth isolation was verified in this disposable AVD. The 1.9.5 follow-up is running, with 1.9.6 tests staged afterward. Boot recovery is environment evidence, not a 1.9.6 runtime pass. An optional system UI XML export failed with a permission error; it is not treated as a successful capture.

The new independent source-review findings remain **pending** at this checkpoint: deleted history may survive in retained draft; invalid generation metadata may stop discovery of older valid backups; stale Activity saved state may overwrite a restored draft. They are not represented as fixed in this frozen APK/source. The recreation candidate needs native confirmation.

Source checkpoint packaging checks: CRC, per-file bytes, executable script modes and bundled APK identity pass. Build/cache output and local SDK paths are excluded.

Real Google consent, token renewal, Drive upload/download persistence, another-phone restore and scheduled backup on Google Play services remain **blocked / not exercised**. The emulator lacks Google Play services; fake HTTPS and synthetic recognition cannot establish live Google or speech accuracy. Console settings retain their user-reported provenance in `GOOGLE_SETUP.md`.

# Historical checkpoint — VoiceFlow 1.9.5

Verified October 4, 2026 UTC. Native emulator logs may use local Android timestamps.

## Google Drive integration

Version **1.9.5**, code **15**, package **app.voiceflow**, adds Google Identity Services `AuthorizationClient` and direct Drive v3 app-data backup. The native connection screen offers Connect Google, account switching/reconnection, Back up now and Restore from Google Drive. Automatic backup defaults to Off; Daily/Weekly and unmetered-network settings require a configured password and account. Legacy document-picker backups remain available only for migration.

Archives retain authenticated password encryption. A new upload remains pending until its downloaded bytes match the encrypted source and Drive acknowledges the commit. Prior committed generations are preserved until that point. Existing backups must be unlocked before enabling uploads for an account; restoration preserves this phone's automatic-backup consent. Older valid archives can restore without enabling uploads when the latest archive cannot be unlocked. Tokens remain in memory, and the only requested scope is `drive.appdata`.

The read-only audit identified and corrected cached-token invalidation, foreground token reuse, interrupted password ownership, restore interruption/rollback, background retry classification, and older-generation recovery. The current native appearance remains the neutral glass design; the selected Daybook design remains queued for native implementation.

## Build and source checks

- Gradle **assemblePreview** and the signed `build-apk.sh` pass against Android SDK/build-tools 35 and Java 17.
- Full **lintPreview** passes with **0 errors and 24 existing warnings**. These are not presented as a zero-warning result.
- API annotations were added only after checking the guarded call sites. Microphone foreground-service handling now uses the API 30 type guard; API 27 navigation styling is isolated in a versioned resource; the headless IME calls its superclass while retaining no input panel.
- The portable `tests/build-drive-fixture.sh` builds the two current offline fixtures against the frozen Gradle preview classes. Production classes are classpath-only, not bundled again in the fixture APK.
- A source-only scan found no embedded Groq/Google API credential or PEM private-key patterns. The existing preview development signing keystore remains part of the source distribution, as before.

## Current native verification

The signed production APK and fixture were **installed and launched** on a fresh AOSP API 35 emulator. This checkpoint records actual results, including failures; the focused follow-up is still in progress.

| Stage | Exact checkpoint result |
|---|---|
| Drive transport/discovery | Completed successfully against fake HTTPS: fixed host/scope, bounds, managed-file metadata, pagination, ordering and typed failures. No live Google request. |
| Encrypted lifecycle before interruption case | Completed assertions for password adoption, configuration/data rollback, active-dictation refusal, validated restore, schedule consent and prior-generation preservation on upload/commit/media failures. |
| Full DriveCloudTest | **Failed** at `Backup did not enter the blocked remote upload`: the fixture's 60-second latch wait expired. It did not expose the worker cause; slow software crypto is a hypothesis, not an established production defect. Remaining assertions after that point were not executed in this run. |
| Backup connection screen | **PASS — 6 native checks:** Connect Google/Restore actions, disconnected upload/cadence state, Off default, missing-Play-services message, no external requests and no preference mutation. |
| Native Backup screenshots | Two actual app captures exported and visually reviewed; both valid PNGs are included in `previews/`. |
| Floating-control SidePreview | **Failed** at the first microphone lookup while system/modal windows were focused. Repeated `com.android.phone` ANRs and Bluetooth native aborts were captured. This is an inconclusive app regression result; targeted emulator-service isolation and a focused rerun are pending. Failed capture files are not treated as screenshots. |
| Logs | System crash/ANR events were present. No `app.voiceflow` crash or ANR was found in the collected interval; this is not a claim of an empty crash buffer. |
| Live Google consent/upload/restore | **Not exercised; blocked** by the AOSP image having no Google Play services. Console settings remain user-reported. |

Earlier attempts failed before installation due to ADB routing or stuck emulator snapshot state. A new disposable AVD resolved startup and allowed the run above. The next fixture version surfaces early worker errors and a 60-second stack diagnostic, allows a bounded 180-second upload-latch wait and reports narrower `follow_up` coverage explicitly. The follow-up does not erase the original failure without a supported explanation and a successful rerun.

## Signed package

Android build-tools 35.0.0 `aapt2 dump badging` confirms **app.voiceflow**, version **1.9.5**, code **15**, minimum API **26** and target API **35**. `apksigner verify --verbose --print-certs` verifies the APK's **v2** signature.

Full certificate SHA-1: `68:24:BC:2C:9F:CF:54:26:6D:DB:53:DA:B1:05:7D:00:17:82:3F:FC`.

Certificate SHA-256: `56d07d6216174d9ecaf6314131e3f4853592ab6c4f031ef368eac8b0deaef0ae`.

APK SHA-256: `98af7f88760cb69a469813abab9d947913e6cfb85be485e9cb9a41a84d9a1920`.

The certificate is the existing custom **development/preview** certificate, `CN=VoiceFlow Preview, OU=Development, O=VoiceFlow, C=US`; it is not the default Gradle debug key. No production release, upload or Play app-signing certificate was available. Build, staged delivery, bundled prebuilt and pulled installed APKs are byte-identical. The installed APK was collected at `toolchain/tested-clean-195.apk` and checked with SHA-256.


Source checkpoint archive: CRC, per-file byte comparison, executable script modes and the bundled APK hash were verified when packaging the signed 1.9.5 checkpoint. Generated build/cache files and local SDK paths are excluded.

## Credential policy pending after this checkpoint

This preserved 1.9.5 build includes provider API keys **inside the password-encrypted backup archive** and restores them through Android Keystore. It does not embed real API keys, Google tokens or a Web client secret in the APK. The latest handoff leaves credential backup unresolved and recommends provider/model/endpoint settings plus reconnecting providers. The optional preference question received no answer, so the next implementation will default to excluding credentials; including them requires an explicit user choice. Do not treat this checkpoint's credential-backup policy as approved for release.

## Remaining real-device checks

The isolated AOSP API 35 emulator has no Google Play services. Offline fake HTTPS tests exercise production code but cannot independently verify Console registration, real Google account consent, token renewal, Drive persistence, revoked-access reconnection, automatic backup on Google Play services or restore on another phone. The project identity and user-reported Console settings retain their distinct provenance in `GOOGLE_SETUP.md`. No Console/Firebase configuration was changed by this implementation.

Android may delay scheduled jobs, and an OS force-stop can interrupt an operation. Rollback handles detected failures; changes spanning connection, secret and portable-data preference stores do not have a crash journal across those stores. Abandoned pending uploads after an interrupted or ambiguous request are hidden from restoration but can accumulate in private app-data storage; committed-generation retention does not clean them. Actual Samsung/Android 17, Niagara, OEM editors and real speech accuracy remain device checks. These checks do not guarantee a bug-free app or establish production readiness.

## Historical validation — VoiceFlow 1.9.4

## Single-bubble update

Version **1.9.4**, code **14**, package **app.voiceflow**, removes floating Delete and Enter controls. Idle and finished dictation use one bubble. During recording, the waveform/timer appears beside the Finish control; Finish returns to the single bubble. Holding the bubble still toggles the usual typing keyboard, with its saved visibility choice retained. Text editing, deletion, newlines and input actions belong to that regular keyboard.

Package metadata, the visible Settings footer and the API client User-Agent identify version 1.9.4. Woven Linen, color/glyph/model-label changes and Banglish output remain in the feature queue for the design/language pass.

## 1.9.4 verification

Production and fixture builds pass. The current native **SidePreview** instrumentation passes on Android 15/API 35: one bubble at idle and after Finish; no floating Delete, Enter or menu controls; waveform beside the sole recording Finish control; direct insertion; physical tap/hold/drag without accidental recording; accessible hold toggling; stable placement above the typing keyboard and clear of the composer; saved typing visibility across fields and activity recreation; password typing with an explicit side mic and no automatic recording; and hiding after input focus is lost. The actual AOSP typing keyboard's Delete and Return keys physically edit `Keep X` to `Keep `, then `Keep \n`. Another dictation with typing visible also passes.

Four current native screenshots—idle, recording, finished and keyboard-visible—were captured from the tested app and visually reviewed. The History and Settings previews are unchanged from the 1.9.3 baseline. The current crash buffer is empty (0 bytes), and the checked logcat has no application ANR or FATAL EXCEPTION entries. These checks use an isolated emulator and fixture recognition; they do not verify the user's Samsung phone, actual Niagara, Google Drive cloud synchronization or real-world transcription accuracy.

### Current release package

The build and installed package agree on **app.voiceflow**, version **1.9.4**, code **14**, minimum API **26** and target API **35**. Apksigner verifies v2/v3 signatures with the existing preview certificate.

Certificate SHA-256: `56d07d6216174d9ecaf6314131e3f4853592ab6c4f031ef368eac8b0deaef0ae`.

APK SHA-256: `905db954dea6ec3f750e9004c549b9670256d0dafd7000a369b003342936e244`.

The delivered, build, bundled prebuilt and pulled installed APKs are byte-identical. The source package contains **85 entries** and passes CRC, per-file comparison and embedded-APK checks. It includes the current documentation, feature queue, fixture sources and preview development signing keystore, excluding generated fixture APKs, build output and user account credentials. The final archive was regenerated and checked after the documentation freeze.

The **19-suite** record below documents the **1.9.3** release and its former floating editing controls. It is retained as historical evidence; it is not a rerun or a current-interface assertion for 1.9.4.

## Historical validation — VoiceFlow 1.9.3

Verified October 4, 2026 (UTC). Native emulator logs may use October 3 local time.

### Completed implementation

Version **1.9.3**, code **13**, package **app.voiceflow**, keeps the existing signing identity and the neutral native glass design. The native app and fixture build against Android SDK 35; minimum API 26, target API 35. Production Java and the current instrumentation fixtures compile successfully. There are no third-party runtime dependencies.

- **History and navigation:** History is the landing screen. The Speak dashboard and bottom navigation are removed. A fixed top-right Settings gear and Settings back control have 48 dp touch targets. History and Settings retain separate reading positions, including saved instance state; expanded and collapsed days also survive activity recreation.
- **Descending timeline:** Days and dictations are newest first. Recap input remains chronological and the existing fingerprint values remain compatible, preserving cached recaps when only display order changes. Recaps continue to use the selected Cleanup model, endpoint and local timezone.
- **Focused controls:** Tap to record, then tap Finish to keep the words. There is no floating Cancel button or nested Keyboard/Close menu. Hold the idle bubble to toggle the usual typing keyboard directly. The bubble shows and announces the observed keyboard state. Delete and Enter appear after Finish and do not collapse on a timer.
- **Typing and protected fields:** The saved normal-field keyboard choice survives inputs, apps and service restarts. Email and web-email inputs temporarily open ordinary typing while keeping dictation available. A hold may hide typing for that email visit. Password and PIN inputs also open ordinary typing automatically and retain the side mic for an explicit tap. Merely focusing an input never starts recording. These temporary changes do not replace the normal-field preference.
- **Private credentials:** Password/PIN dictation commits the final recognition result once through the original native connection without reading existing secret text or replacing the whole field. Partial speech does not modify the input. Original-input, selection-revision and manual-edit checks guard that commit. Individual PIN digits/digit names normalize without losing leading zeros; unrecognized number phrases are not guessed. Private capture bypasses basic/AI cleanup, dictionary replacement and transcription dictionary hints, and skips History/retained draft. Failed or unconfirmed insertion uses a clipboard marked sensitive, without storing a private recovery entry. The VoiceFlow IME provides this path from API 26; direct private insertion alongside another IME requires the Accessibility input connection on API 33+.
- **Editor audit fixes:** Accessibility insertion and deletion finish an active composing span before editing. Dictation range ownership protects surrounding text and manual edits. Spacing handles a word inserted after sentence punctuation, closing quotes and brackets while retaining tight opening punctuation.
- **Speech audit fixes:** Empty partial/final recognition callbacks no longer replace useful partial words. Completed segments remain committed across chained recognition sessions and timeout recovery. Cloud recording interruption still finishes captured audio; a result that cannot safely return to the original field is recovered to the clipboard and History when enabled.
- **Cloud recorder worker:** Setup/start/stop, amplitude polling, release and file cleanup are serialized on a background worker; status/result callbacks run on the main thread. Ownership and cancellation checks prevent UI teardown from overtaking setup or stopping/releasing the hardware from another thread. Startup reports Starting microphone until native start completes, and Finish remains available during startup. The native worker-build recovery test now passes real startup/stop/cancellation, prompt UI return, main-thread callbacks, exactly-once delivery and temporary-audio cleanup.
- **Optional backup:** A visible Settings action opens Google Drive backup. Android's document picker grants access to the selected file only. Archives use authenticated password encryption and contain portable history, dictionary, settings, API connection and retained draft; audio, recap cache and device-bound permissions/secrets are excluded. Automatic backup defaults to Off, with Daily/Weekly and unmetered-network choices. Restore validates the archive before explicit replacement confirmation and refuses an active dictation. Restore/API updates and backup snapshots coordinate through the same data lock so keys and endpoints cannot be combined from different updates. Failed writes keep local app data; staged files are cleaned and prior provider bytes are restored when possible.
- **Pending-result teardown:** Service destruction recovers a completed result that is waiting for native acknowledgement, for both ordinary and private sessions; a successful acknowledged insert avoids unnecessary clipboard recovery. Private teardown retains the same History/draft exclusions. In-flight transcription remains detached to the existing recovery listener rather than canceled.
- **Existing safeguards:** Idle Home remains hidden while opened Finder allows insertion. Held Delete stops on release, slide-away and field changes. The VoiceFlow IME reserves no bottom panel. Input loss, recorder dismissal and screen lock preserve captured words through the existing recovery path; successful direct insertion leaves the clipboard unchanged.

### Current verification

All **19 suites pass**: five pure suites, seven native reruns on the final recorder-worker APK, and seven retained native results covering unchanged paths. The worker APK was rerun through RecoveryTest, ApiRequestTest, SidePreview, AuthFieldTest, InputRecoveryTest, DeleteHoldTest and NoDockTest. The pure text/history sources and the retained UI/backup/editor/launcher/keyboard-state paths did not change in the recorder-worker rebuild. The labels below make that distinction explicit. Native package collection, signature/identity checks and source-package verification also passed.

| Suite | Result and coverage |
|---|---|
| CoreTest | **Pass:** 29 text cases and word-count checks |
| LiveInsertionTest | **Pass:** live revisions, selected ranges, Bengali spacing and manual-edit/cursor guards |
| EditorInsertionTest | **Pass:** sentence/quote/bracket spacing, selected/reversed ranges, Unicode, long text, missing offsets, cursor changes and original-field ownership |
| BubblePlacementTest | **Pass:** stable vertical-control anchor across states and sizes; drag, screen and keyboard clamping |
| HistoryDaysTest | **Pass:** descending days/entries, local midnight and repeated DST hour, unchanged chronological recap text and pre-change cache digest, source-list preservation, word totals and Unicode-safe chunks |
| BackupArchiveTest | **Pass (retained unchanged path):** 40 archive/schema checks; authenticated encryption, random archives, password/tamper/version/size rejection, Unicode, immutable validated data, unsafe settings/endpoint rejection and bounded unique history |
| BackupIntegrationTest | **Pass (retained unchanged path):** actual persistable document grants and provider write/read; portable history/settings/API-key restore, configuration rollback, busy-operation exclusion, interrupted-write recovery, scheduling/network constraints, failed writes, revoked/missing documents, staging cleanup and disconnect |
| LauncherTest | **Pass (retained unchanged path):** stale Home focus hidden; opened Finder inserts text; closing/reopening works with typing shown/hidden; saved keyboard choice persists and ordinary app dictation returns |
| AuthFieldTest | **Pass on recorder-worker APK:** email/password/PIN auto typing with an explicit side mic; one final native credential commit without surrounding reads or whole-field replacement; raw case/prefix/selection/suffix preserved, leading-zero digit normalization, selection-change sensitive clipboard fallback; mismatched private-node/ordinary-EditorInfo refusal and blind native Delete; saved normal typing choice preserved |
| ComposeDeleteTest | **Pass (retained unchanged path):** real Accessibility InputConnection ends composing spans before dictation revision/deletion; surrounding text, selected ranges and complete Unicode characters preserved |
| DeleteHoldTest | **Pass on recorder-worker APK:** physical tap/hold, Unicode deletion and measured acceleration (4 → 6 deletions per 700 ms on the software emulator); release, slide-away and verified input-focus changes stop deletion |
| BackupUiTest | **Pass (retained unchanged path):** 57 native checks for visible frequency/Wi-Fi controls, no automatic default opt-in, preference updates, empty/mismatched passwords, setup/file-picker cancellation, one restore password, explicit replacement confirmation, cancellation preserving current data and accessible Back (offline provider) |
| KeyboardStateTest | **Pass (retained unchanged path):** real IME windows match visible/hidden state; hold toggles directly without a menu; Back updates the indicator and hold reopens typing; shown/hidden choices survive field changes and activity restart |
| SidePreview | **Pass on recorder-worker APK:** physical tap/hold/drag and accessible hold; stable right-side rail and one recording/editing window; waveform left of the sole Finish control; direct insertion/retained words; persistent Delete/Enter across keyboard toggles; keyboard/composer avoidance, state indication and saved choice; controls hide on input loss |
| RecoveryTest | **Pass on recorder-worker APK:** real native startup, early Finish and startup cancellation return promptly with main-thread callbacks; exactly-once delivery or safe cancellation and deleted audio files; M4A/mock Whisper dismissal/lock recovery; useful timeout/empty-result words; private detached capture omits dictionary/AI cleanup, recovers to sensitive clipboard only and preserves History/draft sentinels |
| InputRecoveryTest | **Pass on recorder-worker APK:** original-field ownership, changed input/manual edits, successful insertion leaves clipboard unchanged; ordinary/private final words recover exactly once during service teardown with native acknowledgement withheld |
| ApiRequestTest | **Pass on recorder-worker APK:** ordinary transcript/cleanup and selected recap models; private transcript omits dictionary context while preserving model, language and whitespace; offline guard and distinct History IDs |
| AppScreensTest | **Pass (unchanged MainActivity path):** History landing, fixed gear/back route, no bottom navigation, native descending row order, backup action, preserved settings and recap-cache invalidation |
| NoDockTest | **Pass on recorder-worker APK:** VoiceFlow IME with floating access disabled reserves no input panel |

The final native runner completed successfully with all seven recorder-worker APK reruns passing. Retained unchanged-path results are distinguished above. The runner verifies that ordinary test editors are not running under the default Home package; LauncherTest alone temporarily selects its synthetic Home fixture and restores the original launcher. The updated AuthFieldTest covers explicit private dictation and mismatched credential metadata, rather than relying on the earlier typed-only assertions.

Core suites ran on Java 17 using the ECJ compiler. Native checks use Android 15/API 35 at 412 × 892 with an isolated emulator, synthetic editor/history data and a fake Android recognizer. Backup integration uses an offline document provider with real Android URI grants, not Google Drive. Cloud checks intercept HTTPS and use fixture keys, not the user's account. Native screenshots must come from the tested app, not generated mockups. The source archive includes fixture Java/manifest files, but not the external standalone QA build/setup scripts; these fixtures are not an automated Gradle test target. Their emulator/service/IME/provider setup is part of the validation environment.

### Release package

The native package-collection script completed successfully. Aapt and the installed emulator package agree on **app.voiceflow**, version **1.9.3**, code **13**, minimum API **26** and target API **35**. Apksigner verifies the APK's v2/v3 signatures with the existing preview certificate, allowing an update over the earlier preview installation.

Certificate SHA-256: `56d07d6216174d9ecaf6314131e3f4853592ab6c4f031ef368eac8b0deaef0ae`.

APK SHA-256: `1849084870d11e6395ff07ee849f1cd148ca4bbdad0a6002406a80e2e5b3798e`.

The APK pulled from the tested emulator, the delivered APK and the bundled prebuilt APK are byte-identical. The original Home was independently confirmed restored to `com.android.launcher3/.uioverrides.QuickstepLauncher`.

The final crash buffer is empty (0 bytes). Main/system logs covering **10-03 22:49:55.122–23:00:25.959** contain **0 application ANRs** and **0 FATAL EXCEPTION** entries. Android's `lastanr` retains the earlier CaptureActivity ANR at **10-03 22:36:46**. This historical event predates the recorder-worker fix and the tested log interval. These timestamps are the native Android log timestamps.

Six updated native preview files were pulled from the tested app and visually reviewed. They use synthetic data; obsolete Speak/nested-menu previews were removed.

The source package has **84 entries** and passes ZIP CRC and per-file byte comparison checks. Its embedded APK matches the delivered/tested APK. It includes the feature queue, current fixture Java/manifest sources and the preview development signing keystore; generated fixture APKs, build output and user account credentials are excluded. README and this validation record are included alongside the frozen source.

### Practical limits

These checks reduce regression risk; they do not guarantee a bug-free app. The user's Samsung phone running Android 17, the actual Niagara launcher, OEM editors and older Android releases were not available for this validation. A fixture temporarily selected as default Home reproduces retained Finder focus, but real Niagara behavior still needs a phone check. Folded/unfolded phone layout and OEM keyboard transitions also require device testing.

Actual Google Drive account selection, upload/synchronization, offline-to-online retry and restore on a second phone remain phone checks. A successful document-provider write confirms the local provider accepted the file; it does not prove Drive finished cloud upload. Android can delay scheduled jobs, and document providers are not guaranteed transactional. Keep another copy of important backups and retain the archive password.

Cloud speech produces text after Finish; live partial insertion uses Android/on-device recognition. Network/provider failure or an OS force-stop can still prevent transcription completion. Private storage exclusions do not make recognition offline: the selected Android/cloud provider still processes spoken credentials. On-device recognition requires a supported local model. No real-world speech-accuracy or Wispr Flow parity benchmark was performed for this release. Fixtures do not establish accuracy on the user's speech or account/model availability.

### Historical baseline

The released 1.9.1 build passed its 13 core/native suites, including recovery, Home/Finder behavior, direct insertion, held Delete and headless keyboard checks. The 1.9.2 ordering-only update passed all five core suites and preserved the earlier recap digest. Those historical results explain the baseline; the table above tracks the 1.9.3 reruns separately.

Previous 1.9.2 APK SHA-256: `667dba48e87fa7e6559ad511eb01f0246f05fc30b17403e957e86c54a5caa5b8`.
