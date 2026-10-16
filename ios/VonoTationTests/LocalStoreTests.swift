import XCTest
@testable import VonoTation

final class LocalStoreTests: XCTestCase {
    private var root: URL!
    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("VonoTation-Unit-\(UUID().uuidString)", isDirectory: true)
    }
    override func tearDownWithError() throws {
        if let root { try? FileManager.default.removeItem(at: root) }
        root = nil
    }

    private func fixture(_ note: VonoNote) -> VonoNote {
        var value = note
        // JSON milliseconds intentionally do not preserve Date() submicrosecond bits.
        // Exact model equality uses an exactly representable timestamp fixture.
        value.createdAt = Date(timeIntervalSince1970: floor(Date().timeIntervalSince1970))
        value.updatedAt = value.createdAt
        return value
    }

    func testLateOlderSaveCannotReplaceNewerSnapshot() async throws {
        let store = LocalStore(rootURL: root)
        let old = AppSnapshot(notes: [fixture(VonoNote(kind: .dictation, title: "Old", transcript: "Old data"))])
        let current = AppSnapshot(notes: [fixture(VonoNote(kind: .dictation, title: "New", transcript: "Latest data"))])
        try await store.save(current, generation: 12)
        try await store.save(old, generation: 11)
        let restored = try await store.load()
        XCTAssertEqual(restored.notes, current.notes)
    }

    func testOfflinePendingAudioAndFullTranscriptSurviveRelaunch() async throws {
        let store = LocalStore(rootURL: root)
        try await store.prepare()
        let file = try XCTUnwrap(store.audioURL(fileName: "queued-audio.m4a"))
        let audio = Data([1, 8, 3, 5, 13, 21])
        try audio.write(to: file)
        let source = String(repeating: "A complete long transcript 🗒️\n", count: 500)
        let pending = fixture(VonoNote(kind: .thought, title: "Waiting for connection", transcript: source, rawTranscript: source, audioFileName: "queued-audio.m4a", state: .pending))
        let snapshot = AppSnapshot(notes: [pending])
        try await store.save(snapshot, generation: 1)
        let relaunched = LocalStore(rootURL: root)
        let restored = try await relaunched.load()
        XCTAssertEqual(restored.notes, snapshot.notes)
        XCTAssertEqual(try Data(contentsOf: file), audio)
    }

    func testInterruptedProcessingBecomesRetryableWithoutLosingSource() async throws {
        let store = LocalStore(rootURL: root)
        let pending = VonoNote(kind: .dictation, title: "Interrupted", rawTranscript: "Original words", audioFileName: "source.m4a", state: .processing)
        try await store.save(AppSnapshot(notes: [pending]), generation: 1)
        let restored = try await store.load()
        XCTAssertEqual(restored.notes.first?.state, .pending)
        XCTAssertEqual(restored.notes.first?.audioFileName, "source.m4a")
        XCTAssertEqual(restored.notes.first?.rawTranscript, "Original words")
        XCTAssertFalse(restored.notes.first?.error?.isEmpty ?? true)
    }

    func testCorruptStoreIsReportedAndOriginalFileRemainsIntact() async throws {
        let store = LocalStore(rootURL: root)
        try await store.prepare()
        let path = root.appendingPathComponent("daybook.json")
        let corrupt = Data("{corrupt".utf8)
        try corrupt.write(to: path)
        do {
            _ = try await store.load()
            XCTFail("Corrupt data must not load as an empty store")
        } catch {
            XCTAssertEqual(try Data(contentsOf: path), corrupt)
        }
    }

    func testAudioPathCannotEscapePrivateDirectory() throws {
        let store = LocalStore(rootURL: root)
        for path in ["../secret", "/tmp/secret", "nested/audio.m4a", "nested\\audio.m4a", ".hidden", ""] {
            XCTAssertNil(store.audioURL(fileName: path), path)
        }
        let valid = try XCTUnwrap(store.audioURL(fileName: "চিন্তা-🧠.m4a"))
        XCTAssertEqual(valid.deletingLastPathComponent(), store.audioDirectory)
    }

    func testEarlierCaptureCannotClearNewerRecoveryJournal() async throws {
        let store = LocalStore(rootURL: root)
        let first = RecordingJournal(id: UUID(), kind: .dictation, createdAt: Date(), audioFileName: "first.m4a")
        let second = RecordingJournal(id: UUID(), kind: .thought, createdAt: Date(), audioFileName: "second.m4a")
        try await store.saveJournal(first)
        try await store.saveJournal(second)
        try await store.clearJournal(expectedID: first.id)
        let retained = try await store.loadJournal()
        XCTAssertEqual(retained?.id, second.id)
        XCTAssertEqual(retained?.kind, .thought)
        try await store.clearJournal(expectedID: second.id)
        let cleared = try await store.loadJournal()
        XCTAssertNil(cleared)
    }
}
