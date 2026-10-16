import XCTest
@testable import VonoTation

final class BackupArchiveTests: XCTestCase {
    private let password = "A separate backup passphrase 42"

    func testEncryptedBackupRoundTripPreservesLongUnicodeJSONAndRandomizesArchive() throws {
        let text = String(repeating: "বাংলা original transcript 🔐\n", count: 10_000)
        let input = try JSONSerialization.data(withJSONObject: ["formatVersion": 1, "text": text], options: [.sortedKeys])
        let first = try BackupArchive.encrypt(json: input, password: password)
        let second = try BackupArchive.encrypt(json: input, password: password)
        XCTAssertNotEqual(first, input)
        XCTAssertNotEqual(first, second)
        XCTAssertNil(first.range(of: Data("বাংলা original transcript".utf8)))
        XCTAssertEqual(try BackupArchive.decrypt(archive: first, password: password), input)
    }

    func testWrongPasswordAndTamperingCannotProduceRestoreData() throws {
        let input = Data("{\"notes\":[\"Do not lose this note\"]}".utf8)
        let archive = try BackupArchive.encrypt(json: input, password: password)
        XCTAssertThrowsError(try BackupArchive.decrypt(archive: archive, password: "A different passphrase"))
        var changed = archive
        changed[changed.count - 1] ^= 0x01
        XCTAssertThrowsError(try BackupArchive.decrypt(archive: changed, password: password))
        XCTAssertThrowsError(try BackupArchive.decrypt(archive: Data(archive.prefix(12)), password: password))
        XCTAssertThrowsError(try BackupArchive.decrypt(archive: Data("VFLOWBKP".utf8), password: password))
    }

    func testTypedRestoreRejectsUnfinishedDuplicateAndUnsafeProviderPayloads() throws {
        var note = VonoNote(kind: .thought, title: "Preserved thought", transcript: "Completed original transcript", rawTranscript: "Completed original transcript", summary: "A concise summary", state: .ready)
        let valid = BackupPayload(notes: [note], settings: AppSettings())
        XCTAssertNoThrow(try valid.validated())
        var duplicate = valid
        duplicate.notes.append(note)
        XCTAssertThrowsError(try duplicate.validated())
        note.state = .pending; note.audioFileName = "queued.m4a"
        XCTAssertThrowsError(try BackupPayload(notes: [note], settings: AppSettings()).validated())
        var wrongProvider = valid
        wrongProvider.settings.endpoint = "https://username:password@example.com/api"
        XCTAssertThrowsError(try wrongProvider.validated())
        wrongProvider.settings.endpoint = "http://example.com/api"
        XCTAssertThrowsError(try wrongProvider.validated())
        var wrongFormat = valid
        wrongFormat.format = "Android"
        XCTAssertThrowsError(try wrongFormat.validated())
    }

    func testRestoredThoughtWithoutSummaryRemainsRetryableOnNewDevice() throws {
        let thought = VonoNote(kind: .thought, title: "Restored original", transcript: "These original words must survive even while their summary is waiting.", rawTranscript: "These original words must survive even while their summary is waiting.", state: .ready)
        let portable = try BackupPayload(notes: [thought], settings: AppSettings()).validated()
        let merged = BackupMergePolicy.merge(imported: portable.notes, local: [])
        let restored = try XCTUnwrap(merged.first)
        XCTAssertEqual(restored.id, thought.id)
        XCTAssertEqual(restored.rawTranscript, thought.rawTranscript)
        XCTAssertEqual(restored.state, .pending)
        XCTAssertTrue(restored.needsSummary)
        XCTAssertFalse(restored.mayFilterShortThought)
        XCTAssertFalse(restored.error?.isEmpty ?? true)
    }
}
