import Foundation

/// Separate application targets share implementation, never an application container.
enum AppProduct: String, Codable, CaseIterable {
    case vonotation, notes
    static var current: AppProduct {
        #if VONO_NOTES
        return .notes
        #else
        return .vonotation
        #endif
    }
    var displayName: String { self == .vonotation ? "Voitation" : "Vocading" }
    var bundleIdentifier: String { self == .vonotation ? "app.vonotation.ios" : "app.vonotation.notes.ios" }
    var storageDirectory: String { self == .vonotation ? "VonoTation" : "VonoNotes" }
    // Preserve the original service so its app update retains the saved key.
    var keychainService: String { self == .vonotation ? "app.voiceflow.ios.provider" : "app.vonotation.notes.ios.provider" }
    var urlScheme: String { self == .vonotation ? "vonotation" : "vononotes" }
    var supportsClipboard: Bool { self == .vonotation }
    func supports(_ kind: NoteKind) -> Bool {
        switch self {
        case .vonotation: return kind == .dictation || kind == .thought
        case .notes: return kind == .voiceNote || kind == .call
        }
    }
}
