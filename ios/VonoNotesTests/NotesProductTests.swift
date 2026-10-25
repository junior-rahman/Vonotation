import XCTest
@testable import VonoNotes

@MainActor final class NotesProductTests: XCTestCase {
    func testNotesTargetIdentityAndAllowedKinds() {
        XCTAssertEqual(AppProduct.current, .notes)
        XCTAssertEqual(AppProduct.current.bundleIdentifier, "app.vonotation.notes.ios")
        XCTAssertTrue(AppProduct.current.supports(.voiceNote)); XCTAssertTrue(AppProduct.current.supports(.call))
        XCTAssertFalse(AppProduct.current.supports(.dictation)); XCTAssertFalse(AppProduct.current.supports(.thought))
        XCTAssertFalse(AppProduct.current.supports(.translation)); XCTAssertFalse(AppProduct.current.supportsClipboard)
        XCTAssertEqual(LocalStore.defaultRoot.lastPathComponent, "VonoNotes")
    }
    func testOtherAppBackupRejectedBeforeAnyRestore() throws {
        XCTAssertThrowsError(try BackupPayload(notes: [], settings: AppSettings()).validated(for: .notes))
        XCTAssertThrowsError(try BackupPayload(product: .vonotation, notes: [], settings: AppSettings()).validated(for: .notes))
        XCTAssertNoThrow(try BackupPayload(product: .notes, notes: [], settings: AppSettings()).validated(for: .notes))
    }
    func testImportedCallSavesOfflineSourceAndDoesNotCaptureClipboardOrStartThought() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let model = AppModel(rootURL: root, credentialProvider: { _ in nil })
        for _ in 0..<200 where !model.isLoaded { try await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertTrue(model.isLoaded)
        let input = root.appendingPathComponent("import.m4a")
        let bytes = Data("SYNTHETIC IMPORT AUDIO".utf8); try bytes.write(to: input)
        await model.importAudio(from: input, kind: .call)
        let call = try XCTUnwrap(model.notes.first)
        XCTAssertEqual(call.kind, .call); XCTAssertEqual(call.state, .pending)
        XCTAssertEqual(try Data(contentsOf: XCTUnwrap(model.audioURL(for: call))), bytes)
        model.captureClipboard("Must not enter a Notes clipboard archive")
        XCTAssertTrue(model.clips.isEmpty)
        await model.startRecording(kind: .thought)
        XCTAssertFalse(model.isRecording)
        await model.importAudio(from: input, kind: .dictation)
        XCTAssertEqual(model.notes.count, 1)
        let durable = try await LocalStore(rootURL: root).load()
        XCTAssertEqual(durable.notes.first?.kind, .call)
        XCTAssertTrue(durable.clips.isEmpty)
    }
    func testOldSavedNotesDecodeWithoutNewMetadata() throws {
        let original = VonoNote(kind: .voiceNote, title: "Original", transcript: "বাংলা voice")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(original)) as? [String: Any])
        for key in ["pinned", "deletedAt", "captureWarning"] { json.removeValue(forKey: key) }
        let decoded = try JSONDecoder().decode(VonoNote.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(decoded, original)
        XCTAssertNil(decoded.deletedAt); XCTAssertNil(decoded.pinned)
    }
    func testSearchAndDeletedResponseProtection() {
        var note = VonoNote(kind: .voiceNote, title: "Café meeting", transcript: "বাংলা সিদ্ধান্ত", rawTranscript: "original wording")
        XCTAssertTrue(RecordingSearch.matches("CAFE বাংলা", note: note))
        XCTAssertTrue(RecordingSearch.matches("wording meeting", note: note))
        XCTAssertFalse(RecordingSearch.matches("missing", note: note))
        XCTAssertTrue(ProcessingPolicy.canApply(id: note.id, revision: note.revision, to: [note]))
        note.deletedAt = Date()
        XCTAssertFalse(ProcessingPolicy.canApply(id: note.id, revision: note.revision, to: [note]))
    }
    func testEditTrashRestoreAndPermanentDeletionPreserveSourceUntilConfirmed() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalStore(rootURL: root)
        try await store.prepare()
        let bytes = Data("SYNTHETIC SOURCE - retain unchanged".utf8)
        var note = VonoNote(kind: .voiceNote, title: "Original title", transcript: "Readable text", rawTranscript: "Original provider words", audioFileName: "source.m4a", state: .ready)
        let source = try XCTUnwrap(store.audioURL(fileName: "source.m4a"))
        try bytes.write(to: source)
        try await store.save(AppSnapshot(notes: [note]), generation: 1)
        let model = AppModel(rootURL: root, credentialProvider: { _ in nil })
        for _ in 0..<200 where !model.isLoaded { try await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertTrue(model.isLoaded)
        model.updateNote(note, title: "Edited title", transcript: "Edited text")
        note = try XCTUnwrap(model.notes.first)
        XCTAssertEqual(note.rawTranscript, "Original provider words")
        XCTAssertFalse(ProcessingPolicy.canApply(id: note.id, revision: 0, to: model.notes))
        model.togglePin(note)
        XCTAssertEqual(model.notes.first?.pinned, true)
        let trashed = await model.moveToTrash(note)
        XCTAssertTrue(trashed); XCTAssertEqual(model.deletedNotes.count, 1)
        XCTAssertEqual(try Data(contentsOf: source), bytes)
        let durable = try await store.load()
        XCTAssertNotNil(durable.notes.first?.deletedAt)
        let restored = await model.restoreNote(note)
        XCTAssertTrue(restored); XCTAssertTrue(model.deletedNotes.isEmpty)
        XCTAssertEqual(model.notes.first?.transcript, "Edited text")
        XCTAssertEqual(model.notes.first?.pinned, true)
        let refused = await model.permanentlyDelete(note)
        XCTAssertFalse(refused)
        let trashedAgain = await model.moveToTrash(note)
        XCTAssertTrue(trashedAgain)
        let removed = await model.permanentlyDelete(note)
        XCTAssertTrue(removed); XCTAssertTrue(model.notes.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: source.path))
        let final = try await store.load()
        XCTAssertTrue(final.notes.isEmpty)
    }

}
