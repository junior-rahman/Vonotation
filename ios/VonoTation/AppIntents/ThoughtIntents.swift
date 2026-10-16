import AppIntents

/// Opens the containing app: a keyboard extension cannot start microphone capture.
struct RecordQuickThoughtIntent: AppIntent {
    static var title: LocalizedStringResource = "Record Quick Thought"
    static var description = IntentDescription("Open VonoTation and start a Quick Thought. Recording continues until you finish or cancel it.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await VonoRuntime.beginThought()
        return .result(dialog: "Quick Thought recording started. Finish it in VonoTation or use Finish VonoTation Recording.")
    }
}

struct FinishVonoRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Finish VonoTation Recording"
    static var description = IntentDescription("Finish the active recording and save its source before transcription.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await VonoRuntime.finish()
        return .result(dialog: "Recording finished. VonoTation saves the source and processes the text when it can.")
    }
}

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
