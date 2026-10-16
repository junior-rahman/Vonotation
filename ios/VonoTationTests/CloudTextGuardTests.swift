import XCTest
@testable import VonoTation

final class CloudTextGuardTests: XCTestCase {
    func testCleanupCannotAlterNumbersCurrencyPercentOrMeaningBearingSymbols() {
        for (source, changed) in [("Pay $5 today", "Pay -5 today"), ("Use 5% of it", "Use 5 of it"), ("cats & dogs", "cats dogs"), ("Pay €20", "Pay 20"), ("The change is -5", "The change is 5")] {
            XCTAssertFalse(CloudTextGuard.cleanupAccepted(original: source, candidate: changed), "\(source) → \(changed)")
        }
        XCTAssertTrue(CloudTextGuard.cleanupAccepted(original: "pay $5 today", candidate: "Pay $5 today."))
        XCTAssertTrue(CloudTextGuard.cleanupAccepted(original: "uh I need more chairs", candidate: "I need more chairs."))
        XCTAssertFalse(CloudTextGuard.cleanupAccepted(original: "How are you doing?", candidate: "I am fine, thank you."))
        XCTAssertFalse(CloudTextGuard.cleanupAccepted(original: "Please call the manager", candidate: "I cannot help with that."))
    }

    func testSummaryMustCondenseSupportedContentAndKeepQualifiers() {
        let source = "We need to repaint the kitchen before Friday. The old paint is peeling near the window and the room needs a brighter color for better light."
        XCTAssertTrue(CloudTextGuard.summaryAccepted(original: source, candidate: "Repaint the kitchen before Friday."))
        XCTAssertFalse(CloudTextGuard.summaryAccepted(original: source, candidate: source))
        XCTAssertFalse(CloudTextGuard.summaryAccepted(original: source, candidate: "Buy bananas."))
        let tentative = "The manager might approve 200 dollars for chairs. The budget is not approved yet and we need to ask before ordering anything."
        XCTAssertFalse(CloudTextGuard.summaryAccepted(original: tentative, candidate: "The manager approved 200 dollars for chairs."))
        XCTAssertFalse(CloudTextGuard.summaryAccepted(original: tentative, candidate: "Order chairs for 900 dollars."))
    }
}
