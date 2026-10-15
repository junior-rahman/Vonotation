# Features awaiting discussion

## Banglish output — implemented in the 1.9.12 preview; quality testing pending

Requested October 3, 2026. The resumed 1.9.12 work adds an optional output preference, default Off, independently of cleanup. Current details and limitations are in VALIDATION-1.9.12.md. The discussion below records earlier planning.

The user wants Bengali speech written using Latin letters, preserving Bengali meaning rather than translating into English. Example: `আমি আজ বাড়ি যাব` → `Ami aj bari jabo`.

Discuss an optional Bengali-script/Banglish output preference, preservation of English words in mixed speech, personal spelling preferences, names, and numerals. Prefer a transcript in the spoken language followed by a separate transliteration step; investigate the reported English translation in the existing speech/language/cleanup path before selecting the implementation. No transliteration model, prompt, or API behavior has been changed.

Proposed defaults for discussion: natural everyday Banglish rather than academic transliteration, unchanged English words in mixed speech, and remembered spellings such as `bhalo`/`valo`. Treat output script independently from stylistic cleanup. Evaluate whether conversion can share an existing cleanup request before adding another model call. Retain the original transcript only under the existing normal-history preference; keep the current private-field storage exclusions. Model quality and spelling consistency require speech samples before choosing a model.

## Design pass — decisions and queued work

Reviewed October 4, 2026 (UTC). These are design decisions and requested changes for the next design pass; the 1.9.4 control update removes floating Delete/Enter buttons and uses one bubble with the regular keyboard for editing.

- **Texture locked:** Woven Linen.
- **Selected design:** Daybook Home and Settings, Copper and Sand, Woven Linen. The interactive preview includes responsive settings choices, dictionary editing, daily expansion and descending history; native layout/palette implementation remains queued.
- **Dictation glyph:** Choose a distinctive original glyph that is not a microphone. Remove keyboard logos from the proposed controls while preserving accessible keyboard-state feedback and the working hold-to-toggle gesture.
- **Model choices:** Use friendly, accessible names. Remove unsupported Instant/Versatile preset choices in the planned settings pass, while preserving valid saved provider/custom-model settings. Keep recap generation on the selected model, but remove model numbers from the recap heading.
- **Home and settings:** Apply the selected Daybook direction with readable descending daily timelines. Keep Google Drive backup visibly accessible and preserve the existing gear/back navigation.
- **Interaction polish:** Refine press feedback, expansion/collapse animation and gentle haptics while keeping controls minimal and honoring system motion preferences.

No palette/texture/glyph/model-picker/transliteration production code has been changed for these queued requests.
