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
}
