import AppIntents

#if VONO_NOTES
/// Opens Vocading before starting a microphone recording.
struct RecordVoiceNoteIntent: AppIntent {
    static var title: LocalizedStringResource = "Record Voice Note"
    static var description = IntentDescription("Open Vocading and record a voice note until you finish or cancel it.")
    static var openAppWhenRun = true
    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await VonoRuntime.begin(kind: .voiceNote)
        return .result(dialog: "Voice note recording started. Finish it in Vocading.")
    }
}
#else
/// Opens the containing app: a keyboard extension cannot start microphone capture.
struct RecordQuickThoughtIntent: AppIntent {
    static var title: LocalizedStringResource = "Record Quick Thought"
    static var description = IntentDescription("Open Voitation and start a Quick Thought. Recording continues until you finish or cancel it.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await VonoRuntime.beginThought()
        return .result(dialog: "Quick Thought recording started. Finish it in Voitation or use Finish Voitation Recording.")
    }
}

#endif

struct FinishVonoRecordingIntent: AppIntent {
    #if VONO_NOTES
    static var title: LocalizedStringResource = "Finish Vocading Recording"
    #else
    static var title: LocalizedStringResource = "Finish Voitation Recording"
    #endif
    static var description = IntentDescription("Finish the active recording and save its source before transcription.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await VonoRuntime.finish()
        return .result(dialog: "Recording finished. The source is saved and its text is processed when possible.")
    }
}

// Keep each provider body free of conditional directives: Apple's compile-time
// shortcut extractor otherwise omits the record phrase from generated metadata.
#if VONO_NOTES
struct VonoShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: RecordVoiceNoteIntent(), phrases: [
            "Record a voice note with \(.applicationName)"
        ], shortTitle: "Voice note", systemImageName: "waveform")
        AppShortcut(intent: FinishVonoRecordingIntent(), phrases: [
            "Finish recording in \(.applicationName)"
        ], shortTitle: "Finish recording", systemImageName: "stop.circle")
    }
}
#else
struct VonoShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: RecordQuickThoughtIntent(), phrases: [
            "Record a thought with \(.applicationName)",
            "Start a Quick Thought in \(.applicationName)"
        ], shortTitle: "Quick Thought", systemImageName: "note.text")
        AppShortcut(intent: FinishVonoRecordingIntent(), phrases: [
            "Finish recording in \(.applicationName)"
        ], shortTitle: "Finish recording", systemImageName: "stop.circle")
    }
}
#endif
