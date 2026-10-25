import XCTest

final class NotesUITests: XCTestCase {
    private var app: XCUIApplication!
    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication(); app.launchArguments = ["--ui-testing", "--ui-fixtures"]; app.launch()
        XCTAssertTrue(app.buttons["nav.settings"].waitForExistence(timeout: 15))
    }
    override func tearDownWithError() throws {
        let capture = XCTAttachment(screenshot: app.screenshot()); capture.lifetime = .keepAlways; add(capture)
        app.terminate()
    }
    func testOnlyVoiceAndCallCardsAreAvailable() {
        XCTAssertEqual(app.staticTexts["page.title"].label, "Voice notes")
        XCTAssertTrue(app.buttons["record.toggle"].exists)
        app.buttons["nav.calls"].tap()
        XCTAssertEqual(app.staticTexts["page.title"].label, "Calls")
        XCTAssertTrue(app.buttons["audio.import"].exists)
        XCTAssertFalse(app.buttons["record.toggle"].exists)
        for id in ["nav.dictations", "nav.thoughts", "nav.clipboard", "nav.translate"] { XCTAssertFalse(app.buttons[id].exists) }
    }
    func testVoiceRecordingKeepsKindAcrossCallNavigationAndBackground() {
        app.buttons["record.toggle"].tap()
        let finish = app.buttons["record.finish"]
        XCTAssertTrue(finish.waitForExistence(timeout: 10))
        app.buttons["nav.calls"].tap()
        XCTAssertEqual(app.otherElements["record.active"].value as? String, "voiceNote")
        XCUIDevice.shared.press(.home); app.activate()
        XCTAssertTrue(finish.waitForExistence(timeout: 5))
        XCTAssertEqual(app.otherElements["record.active"].value as? String, "voiceNote")
        finish.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Add an API key to transcribe")).firstMatch.waitForExistence(timeout: 10))
    }
    func testNotesSettingsDoNotOfferDictationOrThoughtControls() {
        app.buttons["nav.settings"].tap()
        let daybook = app.buttons["settings.section.daybook"]
        for _ in 0..<6 where !daybook.isHittable { app.swipeUp() }
        XCTAssertTrue(daybook.isHittable); daybook.tap()
        XCTAssertTrue(app.switches["settings.haptics"].exists)
        XCTAssertFalse(app.switches["settings.quickThoughts"].exists)
        XCTAssertFalse(app.switches["settings.saveDictations"].exists)
        XCTAssertFalse(app.switches["settings.dailyRecaps"].exists)
    }
}
