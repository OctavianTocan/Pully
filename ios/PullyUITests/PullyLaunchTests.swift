import XCTest

final class PullyLaunchTests: XCTestCase {
    private var appForDiagnostics: XCUIApplication?

    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    override func tearDownWithError() throws {
        if let app = appForDiagnostics {
            attachDiagnostics(app, name: "Shared preview final state")
        }
        appForDiagnostics = nil
        XCUIDevice.shared.orientation = .portrait
    }

    func testSharedPreviewSurvivesRotationAndRelaunch() throws {
        let app = XCUIApplication()
        appForDiagnostics = app
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launchEnvironment["PULLY_UI_TEST_APPEARANCE"] = "light"
        app.launch()
        assertVisibleLabel("Pully internal preview", in: app, timeout: 20)
        assertVisibleLabel("Share the existing Pully review interface", in: app, timeout: 10)
        attachScreenshot(name: "Shared preview light portrait")

        XCUIDevice.shared.orientation = .landscapeLeft
        let landscape = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in app.frame.width > app.frame.height },
            object: nil
        )
        XCTAssertEqual(XCTWaiter.wait(for: [landscape], timeout: 10), .completed)
        assertVisibleLabel("Pully internal preview", in: app, timeout: 10)
        attachScreenshot(name: "Shared preview light landscape")

        app.terminate()
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        assertVisibleLabel("Pully internal preview", in: app, timeout: 20)

        app.terminate()
        app.launchEnvironment["PULLY_UI_TEST_APPEARANCE"] = "dark"
        app.launch()
        assertVisibleLabel("Pully internal preview", in: app, timeout: 20)
        assertVisibleLabel("Share the existing Pully review interface", in: app, timeout: 10)
        attachScreenshot(name: "Shared preview dark portrait")
    }

    func testProductionRowsUseBundledSpanishAndEnglishFallbackMessages() throws {
        let app = XCUIApplication()
        appForDiagnostics = app
        app.launchEnvironment["PULLY_UI_TEST_APPEARANCE"] = "light"
        app.launchArguments = ["-AppleLanguages", "(es)", "-AppleLocale", "es_ES"]
        app.launch()
        assertVisibleLabel("Share the existing Pully review interface", in: app, timeout: 20)
        assertVisibleLabel("Todas las comprobaciones pasaron", in: app, timeout: 10)
        // This existing catalog key remains "draft" in every source locale.
        assertVisibleLabel("draft", in: app, timeout: 10)
        attachScreenshot(name: "Production rows Spanish")

        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(nl)", "-AppleLocale", "nl_NL"]
        app.launch()
        assertVisibleLabel("Share the existing Pully review interface", in: app, timeout: 20)
        assertVisibleLabel("All checks have passed", in: app, timeout: 10)
        attachScreenshot(name: "Production rows unsupported locale English fallback")
    }

    private func assertVisibleLabel(
        _ label: String,
        in app: XCUIApplication,
        timeout: TimeInterval = 10,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        // Compose maps heading() to UIKit's Header trait, not StaticText. Preserve that
        // semantic role while checking the exact accessible label and visible geometry.
        let element = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", label)).firstMatch
        guard element.waitForExistence(timeout: timeout) else {
            attachDiagnostics(app, name: "Missing accessible label: \(label)")
            XCTFail("Missing accessible label: \(label)", file: file, line: line)
            return
        }
        let frame = element.frame
        guard !frame.isEmpty, !frame.isNull, app.frame.intersects(frame) else {
            attachDiagnostics(app, name: "Offscreen accessible label: \(label)")
            XCTFail("Accessible label has no visible frame: \(label), frame: \(frame)", file: file, line: line)
            return
        }
    }

    private func attachDiagnostics(_ app: XCUIApplication, name: String) {
        let tree = app.debugDescription
        print("\(name)\n\(tree)")
        let attachment = XCTAttachment(string: tree)
        attachment.name = "\(name) accessibility tree"
        attachment.lifetime = .keepAlways
        add(attachment)
        attachScreenshot(name: name)
    }

    private func attachScreenshot(name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
