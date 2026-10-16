import SwiftUI

@MainActor enum VonoRuntime {
    static let model = AppModel()

    static func beginThought() async throws {
        try await begin(kind: .thought)
    }

    static func begin(kind: NoteKind) async throws {
        try await waitUntilLoaded()
        guard kind != .thought || model.settings.quickThoughts else {
            throw VonoError.message("Turn on Quick Thoughts in VonoTation Settings first.")
        }
        guard !model.isRecording else {
            throw VonoError.message("Finish the current recording before starting another.")
        }
        model.acknowledgeError()
        await model.startRecording(kind: kind)
        guard model.isRecording, model.recordingKind == kind else {
            throw VonoError.message(model.errorMessage ?? "The recording could not start. Open VonoTation and check microphone permission.")
        }
    }

    static func finish() async throws {
        try await waitUntilLoaded()
        guard model.isRecording else { throw VonoError.message("There is no active VonoTation recording.") }
        model.acknowledgeError()
        await model.finishRecording()
        if let error = model.errorMessage { throw VonoError.message(error) }
    }

    private static func waitUntilLoaded() async throws {
        for _ in 0..<200 {
            if model.isLoaded { return }
            if let error = model.errorMessage { throw VonoError.message(error) }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        throw VonoError.message("VonoTation is still opening. Try the shortcut again after it finishes.")
    }
}

@main @MainActor struct VonoTationApp: App {
    @StateObject private var model: AppModel

    init() { _model = StateObject(wrappedValue: VonoRuntime.model) }

    var body: some Scene {
        WindowGroup {
            DaybookHome().environmentObject(model)
        }
    }
}
