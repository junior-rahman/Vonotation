import Foundation

enum NoteKind: String, Codable, CaseIterable, Identifiable {
    case voiceNote, dictation, thought, call, translation
    var id: String { rawValue }
    var title: String {
        switch self {
        case .voiceNote: return "Voice notes"
        case .dictation: return "Dictation"
        case .thought: return "Thoughts"
        case .call: return "Call notes"
        case .translation: return "Translate"
        }
    }
}

enum ProcessingState: String, Codable { case pending, processing, ready, failed }

struct VonoNote: Identifiable, Codable, Equatable {
    var id = UUID()
    var createdAt = Date()
    var updatedAt = Date()
    var kind: NoteKind
    var title: String
    var transcript = ""
    var rawTranscript = ""
    var summary = ""
    var translation = ""
    var audioFileName: String?
    var state: ProcessingState = .pending
    var error: String?
    // A user edit always wins over an older asynchronous transcription response.
    var revision: Int = 0
    var needsSummary = false
    // Only a newly recorded Thought explicitly finished by the user can be
    // discarded by the two-line rule. Interrupted/imported/edited sources fail open.
    var mayFilterShortThought = false
    var userEditedTranscript = false
    var userEditedTitle = false
}

struct ClipItem: Identifiable, Codable, Equatable {
    var id = UUID()
    var createdAt: Date
    var expiresAt: Date
    var text: String
    init(text: String, createdAt: Date = Date()) {
        self.text = text
        self.createdAt = createdAt
        self.expiresAt = createdAt.addingTimeInterval(ClipboardPolicy.retention)
    }
}

struct DayRecap: Identifiable, Codable, Equatable {
    var id: String
    var kind: NoteKind
    var day: Date
    var text: String
    var fingerprint: String
}

struct AppSettings: Codable, Equatable {
    var endpoint = "https://api.groq.com/openai/v1"
    var transcriptionModel = "whisper-large-v3"
    var cleanupModel = "openai/gpt-oss-20b"
    var noteModel = "openai/gpt-oss-120b"
    var translationModel = "openai/gpt-oss-120b"
    var language = "en"
    var translationLanguage = "English"
    var aiCleanup = true
    var haptics = true
    var quickThoughts = true
    var saveDictations = true
    var showDailyRecaps = false
}

struct ThoughtSummary { let title: String; let summary: String }

struct DeletedContent: Identifiable, Codable, Equatable {
    var id = UUID()
    var deletedAt = Date()
    var note: VonoNote?
    var clip: ClipItem?
    var expiresAt: Date { min(deletedAt.addingTimeInterval(86400), clip?.expiresAt ?? .distantFuture) }
    var title: String { note?.title ?? clip?.text ?? "Deleted item" }
}

struct AppSnapshot: Codable, Equatable {
    var formatVersion = 1
    var notes: [VonoNote] = []
    var clips: [ClipItem] = []
    var recaps: [DayRecap] = []
    var settings = AppSettings()
    // Optional for compatibility with all existing version-1 stores. Never exported in backups.
    var deleted: [DeletedContent]?
}

enum VonoError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let message) = self { return message }; return nil }
}

struct RecordingSession: Equatable {
    let id: UUID
    let kind: NoteKind
    let startedAt: Date
    let audioFileName: String
}
struct RecordingJournal: Codable {
    let id: UUID
    let kind: NoteKind
    let createdAt: Date
    let audioFileName: String
}
enum ProcessingPolicy {
    static func canApply(id: UUID, revision: Int, to notes: [VonoNote]) -> Bool {
        notes.contains { $0.id == id && $0.revision == revision }
    }
}
