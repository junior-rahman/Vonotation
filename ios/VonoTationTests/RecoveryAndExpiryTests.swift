import XCTest
@testable import VonoTation

@MainActor
final class RecoveryAndExpiryTests: XCTestCase {
    private var directory: URL!
    override func setUpWithError() throws { directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: directory) }
    private func load(_ snapshot: AppSnapshot) async throws -> AppModel {
        try await LocalStore(rootURL: directory).save(snapshot, generation: 0)
        let model = AppModel(rootURL: directory, credentialProvider: { _ in nil }, product: .vonotation)
        for _ in 0..<200 {
            if model.isLoaded { return model }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        throw VonoError.message("Model failed to load")
    }
    func testOldVersionOneSnapshotWithoutUndoFieldStillLoads() throws {
        let encoded = try LocalStore.encoder().encode(AppSnapshot())
        let decoded = try LocalStore.decoder().decode(AppSnapshot.self, from: encoded)
        XCTAssertNil(decoded.deleted)
    }
    func testForegroundClipboardExpiresWithoutSceneChange() async throws {
        var clip = ClipItem(text: "Expiring while the app remains open")
        clip.expiresAt = Date().addingTimeInterval(0.6)
        let model = try await load(AppSnapshot(clips: [clip]))
        XCTAssertEqual(model.clips.count, 1)
        try await Task.sleep(nanoseconds: 850_000_000)
        XCTAssertTrue(model.clips.isEmpty)
    }
    func testUndoKeepsClipboardOriginalExpiryAndCompleteText() async throws {
        let clip = ClipItem(text: String(repeating: "Original complete text. ", count: 1000))
        let model = try await load(AppSnapshot(clips: [clip]))
        model.deleteClip(clip)
        XCTAssertTrue(model.clips.isEmpty)
        let deleted = try XCTUnwrap(model.deleted.first)
        model.restoreDeleted(deleted.id)
        XCTAssertEqual(model.clips.first, clip)
        model.copyClip(clip.id)
        XCTAssertEqual(model.clips, [clip], "Copy must not capture a new item or renew expiry")
    }
    func testUndoRetainsAudioAndRevokesOldProcessingRevision() async throws {
        let note = VonoNote(kind: .thought, title: "Keep the source", transcript: "Saved words", audioFileName: "source.m4a", state: .ready, revision: 7)
        let model = try await load(AppSnapshot(notes: [note]))
        let audio = directory.appendingPathComponent("Audio/source.m4a")
        try Data("source recording".utf8).write(to: audio)
        XCTAssertTrue(model.deleteNote(note))
        let deleted = try XCTUnwrap(model.deleted.first)
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertTrue(FileManager.default.fileExists(atPath: audio.path))
        model.restoreDeleted(deleted.id)
        XCTAssertEqual(model.notes.first?.transcript, note.transcript)
        XCTAssertGreaterThan(try XCTUnwrap(model.notes.first?.revision), note.revision)
        XCTAssertFalse(ProcessingPolicy.canApply(id: note.id, revision: note.revision, to: model.notes))
    }
}
