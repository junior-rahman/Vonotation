import AppIntents

#if VONO_NOTES
/// Opens Vono Notes before starting a microphone recording.
struct RecordVoiceNoteIntent: AppIntent {
    static var title: LocalizedStringResource = "Record Voice Note"
    static var description = IntentDescription("Open Vono Notes and record a voice note until you finish or cancel it.")
    static var openAppWhenRun = true
    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await VonoRuntime.begin(kind: .voiceNote)
        return .result(dialog: "Voice note recording started. Finish it in Vono Notes.")
    }
}
#else
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

#endif

struct FinishVonoRecordingIntent: AppIntent {
    #if VONO_NOTES
    static var title: LocalizedStringResource = "Finish Vono Notes Recording"
    #else
    static var title: LocalizedStringResource = "Finish VonoTation Recording"
    #endif
    static var description = IntentDescription("Finish the active recording and save its source before transcription.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await VonoRuntime.finish()
        return .result(dialog: "Recording finished. The source is saved and its text is processed when possible.")
    }
}

struct VonoShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        #if VONO_NOTES
        AppShortcut(intent: RecordVoiceNoteIntent(), phrases: [
            "Record a voice note with \(.applicationName)"
        ], shortTitle: "Voice note", systemImageName: "waveform")
        #else
        AppShortcut(intent: RecordQuickThoughtIntent(), phrases: [
            "Record a thought with \(.applicationName)",
            "Start a Quick Thought in \(.applicationName)"
        ], shortTitle: "Quick Thought", systemImageName: "note.text")
        #endif
        AppShortcut(intent: FinishVonoRecordingIntent(), phrases: [
            "Finish recording in \(.applicationName)"
        ], shortTitle: "Finish recording", systemImageName: "stop.circle")
    }
}
