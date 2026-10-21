import XCTest

final class PullyLaunchTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    override func tearDownWithError() throws {
        XCUIDevice.shared.orientation = .portrait
    }

    func testSharedPreviewSurvivesRotationAndRelaunch() throws {
        let app = XCUIApplication()
        app.launchEnvironment["PULLY_UI_TEST_APPEARANCE"] = "light"
        app.launch()
        XCTAssertTrue(app.staticTexts["Pully internal preview"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Share the existing Pully review interface"].waitForExistence(timeout: 10))
        attachScreenshot(name: "Shared preview light portrait")

        XCUIDevice.shared.orientation = .landscapeLeft
        let landscape = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in app.frame.width > app.frame.height },
            object: nil
        )
        XCTAssertEqual(XCTWaiter.wait(for: [landscape], timeout: 10), .completed)
        XCTAssertTrue(app.staticTexts["Pully internal preview"].waitForExistence(timeout: 10))
        attachScreenshot(name: "Shared preview light landscape")

        app.terminate()
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.staticTexts["Pully internal preview"].waitForExistence(timeout: 20))

        app.terminate()
        app.launchEnvironment["PULLY_UI_TEST_APPEARANCE"] = "dark"
        app.launch()
        XCTAssertTrue(app.staticTexts["Pully internal preview"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Share the existing Pully review interface"].waitForExistence(timeout: 10))
        attachScreenshot(name: "Shared preview dark portrait")
    }

    private func attachScreenshot(name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
