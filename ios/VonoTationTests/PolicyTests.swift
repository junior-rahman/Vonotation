import XCTest
@testable import VonoTation

final class PolicyTests: XCTestCase {
    func testClipboardKeepsFullUnicodeTextAndExpiresAtExactThirtyDays() throws {
        let copied = Date(timeIntervalSince1970: 1_800_000_000)
        let fullText = String(repeating: "বাংলা thought 🧠 — complete text\n", count: 12_000)
        let item = ClipItem(text: fullText, createdAt: copied)
        XCTAssertEqual(item.text, fullText)
        XCTAssertEqual(item.expiresAt.timeIntervalSince(copied), 30 * 24 * 60 * 60)
        XCTAssertFalse(ClipboardPolicy.isExpired(item, now: item.expiresAt.addingTimeInterval(-0.001)))
        XCTAssertTrue(ClipboardPolicy.isExpired(item, now: item.expiresAt))
        XCTAssertEqual(ClipboardPolicy.retained([item], now: item.expiresAt), [])
        let encoded = try LocalStore.encoder().encode(item)
        XCTAssertEqual(try LocalStore.decoder().decode(ClipItem.self, from: encoded), item)
    }

    func testRepeatedPasteDoesNotRefreshExistingCopyAndCountDoesNotEvict() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let items = (0..<100).map { ClipItem(text: "Copy \($0)", createdAt: start.addingTimeInterval(Double($0))) }
        let now = start.addingTimeInterval(1_000)
        XCTAssertEqual(ClipboardPolicy.retained(items, now: now).count, 100)
        XCTAssertEqual(ClipboardPolicy.retained(items, now: now).first?.text, "Copy 99")
        XCTAssertFalse(ClipboardPolicy.shouldAppend("Copy 99", to: items, now: now))
        XCTAssertTrue(ClipboardPolicy.shouldAppend("Copy 98", to: items, now: now))
        XCTAssertFalse(ClipboardPolicy.shouldAppend(" \n ", to: items, now: now))
        XCTAssertTrue(ClipboardPolicy.shouldAppend("Copy 99", to: items, now: start.addingTimeInterval(ClipboardPolicy.retention + 100)))
        XCTAssertEqual(items.last?.expiresAt, start.addingTimeInterval(99 + ClipboardPolicy.retention))
    }

    func testThoughtSummaryCannotBeTranscriptOrInventDatesAndPreservesUncertainty() {
        let source = "I might move the meeting to Friday. The budget is 200 dollars and is not approved. We need to ask the manager before ordering the chairs."
        XCTAssertFalse(NotePolicy.summaryAccepted(source: source, candidate: source))
        XCTAssertFalse(NotePolicy.summaryAccepted(source: source, candidate: "Approved budget of 900 dollars."))
        XCTAssertFalse(NotePolicy.summaryAccepted(source: source, candidate: "The meeting moves to Friday."))
        let supported = "We need to repaint the kitchen before Friday. The old paint is peeling near the window and the room needs a brighter color for better light."
        XCTAssertTrue(NotePolicy.summaryAccepted(source: supported, candidate: "Repaint the kitchen before Friday."))
        let gist = NotePolicy.localGist(source)
        XCTAssertNotEqual(gist, source)
        if !gist.isEmpty {
            XCTAssertTrue(NotePolicy.summaryAccepted(source: source, candidate: gist))
            XCTAssertLessThan(NotePolicy.words(gist).count, NotePolicy.words(source).count)
        }
        XCTAssertEqual(NotePolicy.localGist("This is a short thought."), "")
    }

    func testTinyThoughtsAreExcludedAndSourceFieldsRemainSeparate() throws {
        XCTAssertFalse(NotePolicy.thoughtEligible("Hi."))
        XCTAssertFalse(NotePolicy.thoughtEligible("one\ntwo"))
        XCTAssertTrue(NotePolicy.thoughtEligible("I need to prepare the presentation tomorrow.\nThe manager requested a short explanation of the sales figures."))
        let original = "Original raw speech, including its wording."
        var thought = VonoNote(kind: .thought, title: "Original", transcript: original, rawTranscript: original, summary: "A concise explanation.")
        thought.transcript = "An explicitly edited transcript."
        let decoded = try LocalStore.decoder().decode(VonoNote.self, from: LocalStore.encoder().encode(thought))
        XCTAssertEqual(decoded.rawTranscript, original)
        XCTAssertEqual(decoded.transcript, "An explicitly edited transcript.")
        XCTAssertEqual(decoded.summary, "A concise explanation.")
    }

    func testDayGroupingUsesLocalDayAcrossDaylightSavingTime() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let formatter = ISO8601DateFormatter()
        let early = try XCTUnwrap(formatter.date(from: "2026-11-01T05:30:00Z"))
        let repeated = try XCTUnwrap(formatter.date(from: "2026-11-01T06:30:00Z"))
        XCTAssertEqual(NotePolicy.dayID(early, kind: .dictation, calendar: calendar), NotePolicy.dayID(repeated, kind: .dictation, calendar: calendar))
        XCTAssertNotEqual(NotePolicy.dayID(early, kind: .thought, calendar: calendar), NotePolicy.dayID(early, kind: .dictation, calendar: calendar))
    }
}
