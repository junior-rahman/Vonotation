import XCTest

final class DaybookUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--ui-fixtures"]
        app.launch()
        XCTAssertTrue(app.buttons["nav.settings"].waitForExistence(timeout: 15))
    }

    override func tearDownWithError() throws {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
    }

    private func openCard(_ id: String) {
        let button = app.buttons[id]
        let navigation = app.scrollViews.firstMatch
        // XCTest can raise an activation-point failure when isHittable is queried
        // for an offscreen SwiftUI button. Seek by native geometry before tapping.
        func isInsideNavigation() -> Bool {
            guard button.exists else { return false }
            let frame = button.frame, viewport = navigation.frame.intersection(app.frame)
            return !frame.isEmpty && frame.minX >= viewport.minX && frame.maxX <= viewport.maxX
        }
        if !isInsideNavigation() {
            for _ in 0..<3 { app.scrollViews.firstMatch.swipeRight() }
        }
        for _ in 0..<6 where !isInsideNavigation() {
            navigation.swipeLeft()
        }
        XCTAssertTrue(button.waitForExistence(timeout: 5), id)
        XCTAssertTrue(isInsideNavigation(), id)
        button.tap()
    }

    private func elements(prefix: String) -> XCUIElementQuery {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix))
    }

    private func revealVertically(_ element: XCUIElement) {
        for _ in 0..<6 where !element.isHittable { app.swipeUp() }
        XCTAssertTrue(element.isHittable)
    }

    func testNavigationAndDailyDisclosureOnNativeDeviceLayout() {
        openCard("nav.dictations")
        XCTAssertEqual(app.staticTexts["page.title"].label, "Dictation")
        let day = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "timeline.day.")).firstMatch
        XCTAssertTrue(day.waitForExistence(timeout: 5))
        let note = elements(prefix: "note.").firstMatch
        XCTAssertTrue(note.exists)
        day.tap()
        XCTAssertFalse(note.exists)
        day.tap()
        XCTAssertTrue(note.waitForExistence(timeout: 3))
        XCTAssertGreaterThan(day.frame.width, 44)
        XCTAssertLessThanOrEqual(app.staticTexts["page.title"].frame.maxX, app.frame.maxX)
        openCard("nav.calls")
        XCTAssertTrue(app.buttons["audio.import"].exists)
        XCTAssertFalse(app.buttons["record.toggle"].exists)
        openCard("nav.translate")
        XCTAssertTrue(app.textFields["translation.language"].exists)
    }

    func testThoughtDetailShowsDistinctSummaryAndOriginalTranscript() {
        openCard("nav.thoughts")
        let note = elements(prefix: "note.").firstMatch
        XCTAssertTrue(note.waitForExistence(timeout: 5))
        let originalNoteIdentifier = note.identifier
        note.tap()
        let summary = app.staticTexts["note.summary"]
        let transcript = app.staticTexts["note.transcript"]
        XCTAssertTrue(summary.waitForExistence(timeout: 5))
        app.buttons["note.detail.transcript.disclosure"].tap()
        XCTAssertTrue(transcript.exists)
        XCTAssertFalse(summary.label.isEmpty)
        XCTAssertNotEqual(summary.label, transcript.label)
        XCTAssertTrue(transcript.label.contains("UI fixture original transcript"))
        let thoughtDetail = XCTAttachment(screenshot: app.screenshot())
        thoughtDetail.name = "Thought detail before explicit deletion"
        thoughtDetail.lifetime = .keepAlways
        add(thoughtDetail)
        app.buttons["note.detail.delete"].tap()
        let deleteConfirmation = app.buttons["Delete note and source audio"]
        XCTAssertTrue(deleteConfirmation.waitForExistence(timeout: 5), "Deleting must require explicit destructive confirmation.")
        deleteConfirmation.tap()
        XCTAssertTrue(app.staticTexts["page.title"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["page.title"].label, "Thoughts")
        XCTAssertFalse(app.descendants(matching: .any)[originalNoteIdentifier].exists)
    }

    func testClipboardExpandsFullTextAndKeepsItsEnding() {
        openCard("nav.clipboard")
        XCTAssertEqual(app.staticTexts["page.title"].label, "Clipboard")
        let clip = elements(prefix: "clip.").matching(NSPredicate(format: "NOT (identifier BEGINSWITH %@)", "clip.fulltext.")).firstMatch
        XCTAssertTrue(clip.waitForExistence(timeout: 5))
        clip.tap()
        let text = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "clip.fulltext.")).firstMatch
        XCTAssertTrue(text.waitForExistence(timeout: 5))
        XCTAssertTrue(text.label.hasSuffix("END OF UI CLIPBOARD FIXTURE"))
        XCTAssertTrue(app.descendants(matching: .any)["clipboard.paste"].exists)
    }

    func testThoughtToggleHidesCardAndSettingsRemainUsable() {
        app.buttons["nav.settings"].tap()
        let daybook = app.buttons["settings.section.daybook"]
        revealVertically(daybook)
        daybook.tap()
        let thoughts = app.switches["settings.quickThoughts"]
        XCTAssertTrue(thoughts.waitForExistence(timeout: 5))
        if thoughts.value as? String == "1" { thoughts.tap() }
        XCTAssertEqual(thoughts.value as? String, "0")
        let haptics = app.switches["settings.haptics"]
        XCTAssertTrue(haptics.exists)
        let backup = app.buttons["settings.backup"]
        revealVertically(backup)
        backup.tap()
        XCTAssertTrue(app.buttons["settings.backup.export"].exists)
        XCTAssertTrue(app.buttons["settings.backup.import"].exists)
        app.buttons["settings.backup.done"].tap()
        app.buttons["settings.done"].tap()
        XCTAssertFalse(app.buttons["nav.thoughts"].exists)
        XCTAssertTrue(app.buttons["nav.settings"].isHittable)
    }

    func testRecordingRemainsDictationAcrossNavigationAndOutsideTaps() {
        openCard("nav.dictations")
        let start = app.buttons["record.toggle"]
        XCTAssertTrue(start.isHittable)
        start.tap()
        let finish = app.buttons["record.finish"]
        XCTAssertTrue(finish.waitForExistence(timeout: 10), "Native simulator microphone capture must start after simctl grants permission.")
        openCard("nav.clipboard")
        XCTAssertTrue(finish.exists)
        XCTAssertEqual(app.otherElements["record.active"].value as? String, "dictation")
        app.staticTexts["page.title"].tap()
        XCTAssertTrue(finish.exists, "An outside tap must not finish recording.")
        app.buttons["record.cancel"].tap()
        let discard = app.buttons["Discard recording"]
        XCTAssertTrue(discard.waitForExistence(timeout: 5))
        let keepRecording = app.buttons["Keep recording"]
        if keepRecording.exists {
            keepRecording.tap()
        } else {
            // Regular-size iPad confirmation popovers omit cancel-role actions.
            // The native dismiss region is outside the destructive popover.
            XCTAssertTrue(app.popovers.firstMatch.exists)
            let dismissRegion = app.otherElements["PopoverDismissRegion"]
            XCTAssertTrue(dismissRegion.exists)
            dismissRegion.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.05)).tap()
        }
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: discard)
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 5), .completed)
        XCTAssertTrue(finish.exists)
        XCTAssertEqual(app.otherElements["record.active"].value as? String, "dictation")
        app.buttons["record.cancel"].tap()
        XCTAssertTrue(discard.waitForExistence(timeout: 5))
        discard.tap()
        XCTAssertFalse(finish.waitForExistence(timeout: 2))
    }

    func testManualFinishKeepsOfflineQueueAsDictationAfterBackgrounding() {
        openCard("nav.dictations")
        app.buttons["record.toggle"].tap()
        let finish = app.buttons["record.finish"]
        XCTAssertTrue(finish.waitForExistence(timeout: 10))
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(finish.waitForExistence(timeout: 5))
        XCTAssertEqual(app.otherElements["record.active"].value as? String, "dictation")
        finish.tap()
        XCTAssertTrue(app.buttons["record.toggle"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["page.title"].label, "Dictation")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Add an API key to transcribe")).firstMatch.waitForExistence(timeout: 5))
        XCTAssertGreaterThanOrEqual(elements(prefix: "note.").count, 2)
    }
}
