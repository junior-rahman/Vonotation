# Native iOS API contract (Swift 5, iOS 17)

All application/UI types below are internal to the VonoTation target. `AppModel` is `@MainActor final class AppModel: ObservableObject`; instantiate once with `@StateObject`, inject with `.environmentObject`.

## Models
- `enum NoteKind: String, Codable, CaseIterable, Identifiable { case voiceNote, dictation, thought, call, translation }`; `id: String`, `title: String`.
- `enum ProcessingState: String, Codable { case pending, processing, ready, failed }`.
- `struct VonoNote: Identifiable, Codable, Equatable`: `id: UUID`, `createdAt: Date`, `updatedAt: Date`, `kind: NoteKind`, `title: String`, `transcript: String`, `rawTranscript: String`, `summary: String`, `translation: String`, `audioFileName: String?`, `state: ProcessingState`, `error: String?`. Source audio is optional and secondary to text. `rawTranscript` preserves original provider text; editing modifies `transcript` only.
- `struct ClipItem: Identifiable, Codable, Equatable`: `id: UUID`, `createdAt: Date`, `expiresAt: Date`, `text: String`.
- `struct DayRecap: Identifiable, Codable, Equatable`: `id: String` (kind + local day), `kind: NoteKind`, `day: Date`, `text: String`, `fingerprint: String`.
- `struct AppSettings: Codable, Equatable`: `endpoint`, `transcriptionModel`, `cleanupModel`, `noteModel`, `translationModel`, `language`, `translationLanguage` strings; `aiCleanup`, `haptics`, `quickThoughts`, `saveDictations`, `showDailyRecaps` bools. Defaults match Android cloud service; transcription language is English (`en`); multilingual transcription is paused. API key is stored separately in Keychain.
- `struct ThoughtSummary { let title: String; let summary: String }`.

## AppModel published properties
`notes: [VonoNote]`, `clips: [ClipItem]`, `recaps: [DayRecap]`, `settings: AppSettings` (UI may bind fields directly), `isLoaded: Bool`, `isRecording: Bool`, `recordingKind: NoteKind?`, `recordingDuration: TimeInterval`, `isProcessing: Bool`, `isBackingUp: Bool`, `hasAPIKey: Bool`, `errorMessage: String?`, `statusMessage: String?`.

Read-only `recordingKind` remains fixed from Start until explicit Finish/Cancel. Navigation, scene deactivation and outside taps must not finish recording. `notes`/`clips` are newest first. Calls consist of explicitly imported audio; no system call interception. There is no system-wide iOS overlay.

## AppModel UI methods
- `func startRecording(kind: NoteKind) async` (requests microphone permission; errors published).
- `func finishRecording() async` (saves audio before networking; queue survives offline/termination).
- `func cancelRecording()` (only explicit user cancel discards active audio).
- `func importAudio(from url: URL, kind: NoteKind) async` (security scoped file URL support).
- `func retry(_ note: VonoNote) async`.
- `func updateNote(_ note: VonoNote, title: String, transcript: String)` (original raw transcript retained).
- `func deleteNote(_ note: VonoNote)`.
- `func audioURL(for note: VonoNote) -> URL?`.
- `func captureClipboard(_ text: String)` (call only after native user-initiated paste; no background polling).
- `func copyText(_ text: String)` (copies and records ordinary text in 30-day archive).
- `func deleteClip(_ clip: ClipItem)`.
- `func refreshExpiry()` (call on becoming active).
- `func saveAPIKey(_ key: String)` (empty removes; never published to UI/log/backup).
- `func generateRecap(for kind: NoteKind, on day: Date) async`.
- `func recap(for kind: NoteKind, on day: Date) -> String?`.
- `func exportBackup(password: String) async throws -> URL` (temporary `.vonobackup` file for ShareLink/fileExporter).
- `func importBackup(from url: URL, password: String) async throws` (**UI must explicitly confirm replacement before calling**; refuses active audio/processing).
- `func acknowledgeError()` / `func acknowledgeStatus()` clear transient strings.

Backup excludes API key, recordings, clipboard and cached recaps. Google Drive connection is not advertised: a separate registered iOS OAuth client is not available. Native encrypted Files export/import works without Google.

## Test seams / pure policies
`NotePolicy` Foundation-only: title from transcript, Thought eligibility, concise local gist, dayStart, summary validation. `ClipboardPolicy`: exact 30-day duration, expiry/filter, repeated-import dedup without renewing retained copy. `LocalStore` actor accepts root URL; `AppSnapshot` Codable. `BackupArchive.encrypt(json: Data,password:String)` / `decrypt(archive:Data,password:String)` use the distinct iOS VONOIOSB v1 format (not Android cross-restore). CloudAPI accepts injected URLSession. Do not seed sample data in normal app operation.
