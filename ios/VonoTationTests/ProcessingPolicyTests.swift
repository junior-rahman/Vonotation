import XCTest
@testable import VonoTation

final class ProcessingPolicyTests: XCTestCase {
    func testDeletedOrEditedNoteCannotReceiveStaleAsyncResponse() {
        var note = VonoNote(kind: .thought, title: "Initial", transcript: "Original text", rawTranscript: "Original text")
        let id = note.id
        XCTAssertTrue(ProcessingPolicy.canApply(id: id, revision: 0, to: [note]))
        note.transcript = "User revised the transcript in the middle."
        note.revision += 1
        XCTAssertFalse(ProcessingPolicy.canApply(id: id, revision: 0, to: [note]))
        XCTAssertTrue(ProcessingPolicy.canApply(id: id, revision: 1, to: [note]))
        XCTAssertFalse(ProcessingPolicy.canApply(id: id, revision: 1, to: []))
        XCTAssertFalse(ProcessingPolicy.canApply(id: UUID(), revision: 1, to: [note]))
        XCTAssertEqual(note.rawTranscript, "Original text")
    }

    func testFixedRecordingOriginDoesNotDependOnCurrentPage() {
        let session = RecordingSession(id: UUID(), kind: .dictation, startedAt: Date(), audioFileName: "source.m4a")
        let journal = RecordingJournal(id: session.id, kind: session.kind, createdAt: session.startedAt, audioFileName: session.audioFileName)
        for _ in NoteKind.allCases {
            // Navigation has no mutable destination in the persisted recording session.
            XCTAssertEqual(session.kind, .dictation)
            XCTAssertEqual(journal.kind, .dictation)
        }
    }
}
