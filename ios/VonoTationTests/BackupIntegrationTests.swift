import XCTest
@testable import VonoTation

@MainActor
final class BackupIntegrationTests: XCTestCase {
    private var root: URL!
    private var model: AppModel?
    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("VonoTation-Backup-\(UUID().uuidString)", isDirectory: true)
    }
    override func tearDownWithError() throws {
        model = nil
        try? FileManager.default.removeItem(at: root)
    }
    private func loaded(_ snapshot: AppSnapshot) async throws -> AppModel {
        let store = LocalStore(rootURL: root)
        try await store.save(snapshot, generation: 1)
        let value = AppModel(rootURL: root, credentialProvider: { _ in nil })
        model = value
        for _ in 0..<200 {
            if value.isLoaded { return value }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        throw VonoError.message("Isolated backup model did not load.")
    }

    func testExportIncludesCompletedTextAndExcludesClipboardAudioAndPendingWork() async throws {
        let complete = VonoNote(kind: .thought, title: "Saved thought", transcript: "Completed original words", rawTranscript: "Completed original words", summary: "Completed thought", audioFileName: "saved.m4a", state: .ready)
        let pending = VonoNote(kind: .dictation, title: "Queued offline", audioFileName: "pending.m4a", state: .pending)
        let clip = ClipItem(text: "PRIVATE CLIPBOARD MUST STAY LOCAL")
        let value = try await loaded(AppSnapshot(notes: [complete, pending], clips: [clip]))
        let password = "Test backup passphrase 89"
        let archiveURL = try await value.exportBackup(password: password)
        defer { try? FileManager.default.removeItem(at: archiveURL) }
        let plain = try BackupArchive.decrypt(archive: Data(contentsOf: archiveURL), password: password)
        let payload = try LocalStore.decoder().decode(BackupPayload.self, from: plain).validated()
        XCTAssertEqual(payload.notes.count, 1)
        XCTAssertEqual(payload.notes.first?.id, complete.id)
        XCTAssertEqual(payload.notes.first?.rawTranscript, complete.rawTranscript)
        XCTAssertNil(payload.notes.first?.audioFileName)
        XCTAssertFalse(String(decoding: plain, as: UTF8.self).contains(clip.text))
        XCTAssertFalse(String(decoding: plain, as: UTF8.self).contains("pending.m4a"))
        XCTAssertEqual(value.notes.count, 2)
        XCTAssertEqual(value.clips, [clip])
    }

    func testRestoreKeepsPendingAudioAndClipboardWhileReplacingCompletedText() async throws {
        let pending = VonoNote(kind: .thought, title: "Offline source", audioFileName: "queued.m4a", state: .pending)
        let old = VonoNote(kind: .dictation, title: "Old text", transcript: "Old completed text", rawTranscript: "Old completed text", state: .ready)
        let clip = ClipItem(text: "Local Clipboard remains")
        let value = try await loaded(AppSnapshot(notes: [pending, old], clips: [clip]))
        let store = LocalStore(rootURL: root)
        let source = try XCTUnwrap(store.audioURL(fileName: "queued.m4a"))
        let sourceData = Data("UNFINISHED AUDIO SOURCE".utf8)
        try sourceData.write(to: source)
        let restored = VonoNote(kind: .thought, title: "From backup", transcript: "Restored complete transcript", rawTranscript: "Restored complete transcript", summary: "Restored summary", state: .ready)
        let payload = BackupPayload(notes: [restored], settings: AppSettings())
        let password = "A restore passphrase 89"
        let archive = try BackupArchive.encrypt(json: LocalStore.encoder().encode(payload), password: password)
        let input = root.appendingPathComponent("fixture.vonobackup")
        try archive.write(to: input)
        try await value.importBackup(from: input, password: password)
        XCTAssertEqual(Set(value.notes.map(\.id)), Set([pending.id, restored.id]))
        XCTAssertFalse(value.notes.contains { $0.id == old.id })
        XCTAssertEqual(value.clips, [clip])
        XCTAssertEqual(try Data(contentsOf: source), sourceData)
        let persisted = try await LocalStore(rootURL: root).load()
        XCTAssertEqual(Set(persisted.notes.map(\.id)), Set([pending.id, restored.id]))
    }

    func testWrongPasswordLeavesPublishedAndDurableDataUnchanged() async throws {
        let original = VonoNote(kind: .dictation, title: "Keep this", transcript: "Keep all original text", rawTranscript: "Keep all original text", state: .ready)
        let value = try await loaded(AppSnapshot(notes: [original]))
        let payload = BackupPayload(notes: [], settings: AppSettings())
        let archive = try BackupArchive.encrypt(json: LocalStore.encoder().encode(payload), password: "Correct passphrase")
        let file = root.appendingPathComponent("wrong-password.vonobackup")
        try archive.write(to: file)
        do {
            try await value.importBackup(from: file, password: "Incorrect passphrase")
            XCTFail("Wrong password must fail")
        } catch { }
        XCTAssertEqual(value.notes, [original])
        let durable = try await LocalStore(rootURL: root).load()
        XCTAssertEqual(durable.notes, [original])
    }
}
