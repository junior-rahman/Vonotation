import XCTest
@testable import VonoTation

@MainActor final class AppProductTests: XCTestCase {
    func testOriginalAndNotesHaveDisjointCaptureKindsAndStorageIdentities() {
        XCTAssertEqual(AppProduct.current, .vonotation)
        XCTAssertEqual(AppProduct.vonotation.bundleIdentifier, "app.vonotation.ios")
        XCTAssertEqual(AppProduct.vonotation.storageDirectory, "VonoTation")
        XCTAssertEqual(AppProduct.vonotation.keychainService, "app.voiceflow.ios.provider")
        XCTAssertNotEqual(AppProduct.notes.bundleIdentifier, AppProduct.vonotation.bundleIdentifier)
        XCTAssertNotEqual(AppProduct.notes.storageDirectory, AppProduct.vonotation.storageDirectory)
        XCTAssertNotEqual(AppProduct.notes.keychainService, AppProduct.vonotation.keychainService)
        for kind in NoteKind.allCases {
            XCTAssertFalse(AppProduct.notes.supports(kind) && AppProduct.vonotation.supports(kind))
        }
        XCTAssertTrue(AppProduct.vonotation.supports(.dictation))
        XCTAssertTrue(AppProduct.vonotation.supports(.thought))
        XCTAssertTrue(AppProduct.vonotation.supportsClipboard)
        XCTAssertFalse(AppProduct.notes.supportsClipboard)
    }
    func testProductBackupMarkersRejectOtherAppAndUnmarkedLegacyOnlyOpensInOriginal() throws {
        let legacy = BackupPayload(notes: [], settings: AppSettings())
        XCTAssertNoThrow(try legacy.validated(for: .vonotation))
        XCTAssertThrowsError(try legacy.validated(for: .notes))
        let notes = BackupPayload(product: .notes, notes: [], settings: AppSettings())
        XCTAssertThrowsError(try notes.validated(for: .vonotation))
        XCTAssertNoThrow(try notes.validated(for: .notes))
        let dictation = VonoNote(kind: .dictation, title: "Text", transcript: "Full transcript", state: .ready)
        XCTAssertThrowsError(try BackupPayload(product: .notes, notes: [dictation], settings: AppSettings()).validated(for: .notes))
    }
    func testLegacyVoiceAndCallStaySavedAndCannotBeMutatedOrAutomaticallyProcessed() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let voice = VonoNote(kind: .voiceNote, title: "Previous voice", transcript: "Original words", audioFileName: "source.m4a", state: .pending)
        let call = VonoNote(kind: .call, title: "Previous call", transcript: "Call words", state: .ready)
        let store = LocalStore(rootURL: root)
        try await store.save(AppSnapshot(notes: [voice, call]), generation: 1)
        let source = try XCTUnwrap(store.audioURL(fileName: "source.m4a"))
        let bytes = Data("LOCAL SOURCE AUDIO".utf8); try bytes.write(to: source)
        let model = AppModel(rootURL: root, credentialProvider: { _ in nil })
        for _ in 0..<200 where !model.isLoaded { try await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertTrue(model.isLoaded)
        XCTAssertEqual(Set(model.previousNotes.map(\.id)), [voice.id, call.id])
        model.updateNote(voice, title: "Changed", transcript: "Changed")
        XCTAssertFalse(model.deleteNote(voice)); await model.retry(voice)
        await model.startRecording(kind: .voiceNote)
        XCTAssertFalse(model.isRecording)
        XCTAssertEqual(model.notes.first(where: { $0.id == voice.id })?.transcript, voice.transcript)
        XCTAssertEqual(try Data(contentsOf: source), bytes)
        let durable = try await store.load()
        XCTAssertEqual(durable.notes.count, 2)
    }
}
