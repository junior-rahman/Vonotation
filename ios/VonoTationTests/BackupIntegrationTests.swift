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
    private func fixture(_ note: VonoNote) -> VonoNote {
        var value = note
        value.createdAt = Date(timeIntervalSince1970: floor(Date().timeIntervalSince1970))
        value.updatedAt = value.createdAt
        return value
    }
    private func clipboardFixture(_ text: String) -> ClipItem {
        ClipItem(text: text, createdAt: Date(timeIntervalSince1970: floor(Date().timeIntervalSince1970)))
    }
    private func loaded(_ snapshot: AppSnapshot) async throws -> AppModel {
        let store = LocalStore(rootURL: root)
        try await store.save(snapshot, generation: 1)
        let value = AppModel(rootURL: root, credentialProvider: { _ in nil }, product: .vonotation)
        model = value
        for _ in 0..<200 {
            if value.isLoaded { return value }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        throw VonoError.message("Isolated backup model did not load.")
    }

    func testExportIncludesCompletedTextAndExcludesClipboardAudioAndPendingWork() async throws {
        let complete = fixture(VonoNote(kind: .thought, title: "Saved thought", transcript: "Completed original words", rawTranscript: "Completed original words", summary: "Completed thought", audioFileName: "saved.m4a", state: .ready))
        let pending = VonoNote(kind: .dictation, title: "Queued offline", audioFileName: "pending.m4a", state: .pending)
        let clip = clipboardFixture("PRIVATE CLIPBOARD MUST STAY LOCAL")
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
        let clip = clipboardFixture("Local Clipboard remains")
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
        let original = fixture(VonoNote(kind: .dictation, title: "Keep this", transcript: "Keep all original text", rawTranscript: "Keep all original text", state: .ready))
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
    func testSplitRestorePreservesInactiveCompletedRecordingAndItsSource() async throws {
        let previous = fixture(VonoNote(kind: .voiceNote, title: "Previous voice", transcript: "Original voice text", rawTranscript: "Original voice text", audioFileName: "previous.m4a", state: .ready))
        let active = fixture(VonoNote(kind: .dictation, title: "Active text", transcript: "Replace this text", state: .ready))
        let value = try await loaded(AppSnapshot(notes: [previous, active]))
        let store = LocalStore(rootURL: root)
        let source = try XCTUnwrap(store.audioURL(fileName: "previous.m4a"))
        let bytes = Data("PREVIOUS RECORDING SOURCE".utf8); try bytes.write(to: source)
        let imported = fixture(VonoNote(kind: .thought, title: "Imported thought", transcript: "Imported full transcript", state: .ready))
        let payload = BackupPayload(product: .vonotation, notes: [imported], settings: AppSettings())
        let password = "Split restore test password"
        let archive = try BackupArchive.encrypt(json: LocalStore.encoder().encode(payload), password: password)
        let input = root.appendingPathComponent("split.vonobackup"); try archive.write(to: input)
        try await value.importBackup(from: input, password: password)
        XCTAssertEqual(Set(value.notes.map(\.id)), Set([previous.id, imported.id]))
        XCTAssertEqual(value.previousNotes, [previous])
        XCTAssertEqual(try Data(contentsOf: source), bytes)
        let durable = try await LocalStore(rootURL: root).load()
        XCTAssertEqual(Set(durable.notes.map(\.id)), Set([previous.id, imported.id]))
    }

    func testUnmarkedCombinedBackupCannotCloneAConflictingLegacyAudioSource() async throws {
        let previous = fixture(VonoNote(kind: .voiceNote, title: "Local recording", transcript: "Current local voice text", rawTranscript: "Current local voice source", summary: "Local summary", audioFileName: "legacy.m4a", state: .ready))
        let active = fixture(VonoNote(kind: .dictation, title: "Active text", transcript: "Old active text", state: .ready))
        let value = try await loaded(AppSnapshot(notes: [previous, active]))
        let store = LocalStore(rootURL: root)
        let source = try XCTUnwrap(store.audioURL(fileName: "legacy.m4a"))
        let bytes = Data("LEGACY AUDIO MUST HAVE ONLY ONE NOTE OWNER".utf8)
        try bytes.write(to: source)

        var olderCopy = previous
        olderCopy.title = "Older cloud recording"
        olderCopy.transcript = "Different older voice transcript"
        olderCopy.rawTranscript = "Different older voice source"
        olderCopy.audioFileName = nil
        let imported = fixture(VonoNote(kind: .dictation, title: "Imported active text", transcript: "New active transcript", state: .ready))
        // No product marker: this is a pre-split combined-app archive.
        let payload = BackupPayload(notes: [olderCopy, imported], settings: AppSettings())
        let password = "Legacy restore conflict passphrase"
        let archive = try BackupArchive.encrypt(json: LocalStore.encoder().encode(payload), password: password)
        let input = root.appendingPathComponent("legacy-combined.vonobackup")
        try archive.write(to: input)

        // A repeat restore must also remain idempotent for the retained recording.
        for _ in 0..<2 {
            try await value.importBackup(from: input, password: password)
            XCTAssertEqual(value.notes.count, 2)
            XCTAssertEqual(Set(value.notes.map(\.id)), Set([previous.id, imported.id]))
            XCTAssertEqual(value.previousNotes, [previous])
            XCTAssertEqual(value.notes.filter { $0.audioFileName == "legacy.m4a" }.count, 1)
            XCTAssertEqual(try Data(contentsOf: source), bytes)
            let durable = try await LocalStore(rootURL: root).load()
            XCTAssertEqual(durable.notes.count, 2)
            XCTAssertEqual(durable.notes.first(where: { $0.id == previous.id }), previous)
            XCTAssertEqual(durable.notes.filter { $0.audioFileName == "legacy.m4a" }.count, 1)
        }
    }
}
