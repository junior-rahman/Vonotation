import SwiftUI

@MainActor enum VonoRuntime {
    static let model = AppModel()

    static func beginThought() async throws {
        try await begin(kind: .thought)
    }

    static func begin(kind: NoteKind) async throws {
        try await waitUntilLoaded()
        guard model.product.supports(kind) else { throw VonoError.message("This recording belongs in the other app.") }
        guard kind != .thought || model.settings.quickThoughts else {
            throw VonoError.message("Turn on Quick Thoughts in Voitation Settings first.")
        }
        guard !model.isRecording else {
            throw VonoError.message("Finish the current recording before starting another.")
        }
        model.acknowledgeError()
        await model.startRecording(kind: kind)
        guard model.isRecording, model.recordingKind == kind else {
            throw VonoError.message(model.errorMessage ?? "The recording could not start. Open \(AppProduct.current.displayName) and check microphone permission.")
        }
    }

    static func finish() async throws {
        try await waitUntilLoaded()
        guard model.isRecording else { throw VonoError.message("There is no active \(AppProduct.current.displayName) recording.") }
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
        throw VonoError.message("\(AppProduct.current.displayName) is still opening. Try the shortcut again after it finishes.")
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
