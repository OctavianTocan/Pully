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
        selectAll(in: app)
        assertVisibleRow("Share the existing Pully review interface", in: app, timeout: 10)
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
        selectAll(in: app)
        assertVisibleRow("Share the existing Pully review interface", in: app, timeout: 10)
        attachScreenshot(name: "Shared preview dark portrait")
    }

    func testProductionRowsUseBundledSpanishAndEnglishFallbackMessages() throws {
        let app = XCUIApplication()
        appForDiagnostics = app
        app.launchEnvironment["PULLY_UI_TEST_APPEARANCE"] = "light"
        app.launchArguments = ["-AppleLanguages", "(es)", "-AppleLocale", "es_ES"]
        app.launch()
        selectAll(in: app)
        assertVisibleRow("Share the existing Pully review interface", in: app, timeout: 20)
        assertVisibleRow("Todas las comprobaciones pasaron", in: app, timeout: 10)
        // This existing catalog key remains "draft" in every source locale.
        assertVisibleRow("draft", in: app, timeout: 10)
        attachScreenshot(name: "Production rows Spanish")

        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(nl)", "-AppleLocale", "nl_NL"]
        app.launch()
        selectAll(in: app)
        assertVisibleRow("Share the existing Pully review interface", in: app, timeout: 20)
        assertVisibleRow("All checks have passed", in: app, timeout: 10)
        attachScreenshot(name: "Production rows unsupported locale English fallback")
    }

    func testRepositoryBackAndForegroundPreserveTheSameInboxFilterAndScroll() throws {
        let app = XCUIApplication()
        appForDiagnostics = app
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        assertVisibleLabel("Pully internal preview", in: app, timeout: 20)
        selectAll(in: app)
        let lastRow = "Fixture row kept after repository navigation"
        for _ in 0..<14 {
            if visibleElement(lastRow, contains: true, in: app) != nil { break }
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.76))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.32)))
        }
        assertVisibleRow(lastRow, in: app)
        attachScreenshot(name: "Shared Inbox All filter scrolled")

        // The pinned production group name opens Repo through the SAME AppPages stack.
        let repository = requireVisible("mobile-fixtures", in: app)
        repository.tap()
        assertVisibleLabel("Pull requests", in: app)
        assertVisibleLabel("Back", in: app)
        attachScreenshot(name: "Shared repository route")

        // This crosses UIKit's actual leading-edge recognizer; no test-only back hook.
        leadingEdgeBack(in: app)
        assertAllSelected(in: app)
        assertVisibleRow(lastRow, in: app)
        assertHiddenLabel("Back", in: app)
        attachScreenshot(name: "Native back restored shared Inbox scroll")

        XCUIDevice.shared.press(.home)
        app.activate()
        assertAllSelected(in: app)
        assertVisibleRow(lastRow, in: app)
        attachScreenshot(name: "Foreground retained shared Inbox state")
    }

    func testUnsupportedReviewIsExplicitAndCopyLinkUsesTheProductionHoldMenu() throws {
        let app = XCUIApplication()
        appForDiagnostics = app
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        selectAll(in: app)
        let title = "Share the existing Pully review interface"
        requireVisible(title, contains: true, in: app).tap()
        assertVisibleLabel("Internal preview limitation", in: app)
        attachScreenshot(name: "Review capability rejected before navigation")
        // The same native gesture consumes the sheet before the root route stack.
        leadingEdgeBack(in: app)
        assertHiddenLabel("Internal preview limitation", in: app)
        assertAllSelected(in: app)
        requireVisible(title, contains: true, in: app).press(forDuration: 1)
        assertVisibleLabel("Copy link", in: app)
        XCTAssertNil(visibleElement("Start a tour", in: app))
        XCTAssertNil(visibleElement("Delete", in: app))
        requireVisible("Copy link", in: app).tap()
        assertVisibleLabel("Link copied", in: app, timeout: 5)
        // The app writes via the real UIKit adapter. Reading another process's pasteboard
        // would add a privacy prompt; native service tests verify the value boundary.
        attachScreenshot(name: "Production hold menu copy confirmation")
    }

    private func leadingEdgeBack(in app: XCUIApplication) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.005, dy: 0.5))
            .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.78, dy: 0.5)))
    }

    private func assertHiddenLabel(_ label: String, in app: XCUIApplication, timeout: TimeInterval = 10,
                                   file: StaticString = #filePath, line: UInt = #line) {
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.visibleElement(label, in: app) == nil
        }, object: nil)
        guard XCTWaiter.wait(for: [hidden], timeout: timeout) == .completed else {
            attachDiagnostics(app, name: "Accessible label did not leave: \(label)")
            XCTFail("Accessible label did not leave after native Back: \(label)", file: file, line: line)
            return
        }
    }

    private func selectAll(in app: XCUIApplication) {
        let filter = allFilter(in: app)
        let hittable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            filter.exists && filter.isHittable
        }, object: nil)
        guard XCTWaiter.wait(for: [hittable], timeout: 20) == .completed else {
            attachDiagnostics(app, name: "Production All filter not hittable")
            XCTFail("The production All filter did not become hittable")
            return
        }
        filter.tap()
        assertAllSelected(in: app)
    }

    private func allFilter(in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "inbox.filter.all").firstMatch
    }

    private func assertAllSelected(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            let filter = self.allFilter(in: app)
            return filter.exists && filter.isHittable && filter.isSelected
        }, object: nil)
        guard XCTWaiter.wait(for: [selected], timeout: 10) == .completed else {
            attachDiagnostics(app, name: "Inbox All filter not selected")
            XCTFail("The production All filter did not retain selected semantics", file: file, line: line)
            return
        }
    }

    private func assertVisibleRow(_ text: String, in app: XCUIApplication, timeout: TimeInterval = 10,
                                  file: StaticString = #filePath, line: UInt = #line) {
        // Production SwipeRow is clickable and merges title/meta semantics. Keep the
        // exact expected text within that accessible row, independent of UIKit role.
        _ = requireVisible(text, contains: true, in: app, timeout: timeout, file: file, line: line)
    }

    private func assertVisibleLabel(_ label: String, in app: XCUIApplication, timeout: TimeInterval = 10,
                                    file: StaticString = #filePath, line: UInt = #line) {
        _ = requireVisible(label, in: app, timeout: timeout, file: file, line: line)
    }

    @discardableResult
    private func requireVisible(_ label: String, contains: Bool = false, in app: XCUIApplication,
                                timeout: TimeInterval = 10, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        // Compose heading() maps to UIKit's Header trait, not StaticText. Check labels
        // across roles and wait for visible geometry through actual page animations.
        let visible = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.visibleElement(label, contains: contains, in: app) != nil
        }, object: nil)
        guard XCTWaiter.wait(for: [visible], timeout: timeout) == .completed,
              let element = visibleElement(label, contains: contains, in: app) else {
            attachDiagnostics(app, name: "Missing visible accessible label: \(label)")
            XCTFail("Missing visible accessible label: \(label)", file: file, line: line)
            return app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
        }
        return element
    }

    private func visibleElement(_ label: String, contains: Bool = false, in app: XCUIApplication) -> XCUIElement? {
        let predicate = NSPredicate(format: contains ? "label CONTAINS %@" : "label == %@", label)
        return app.descendants(matching: .any).matching(predicate).allElementsBoundByIndex.first { element in
            let frame = element.frame
            return element.exists && !frame.isEmpty && !frame.isNull && app.frame.intersects(frame)
        }
    }

    private func attachDiagnostics(_ app: XCUIApplication, name: String) {
        let filters = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@ OR identifier BEGINSWITH %@", "All", "inbox.filter."))
            .allElementsBoundByIndex.map { element in
                "id=\(element.identifier), label=\(element.label), type=\(element.elementType.rawValue), frame=\(element.frame), hittable=\(element.isHittable), selected=\(element.isSelected)"
            }.joined(separator: "\n")
        let tree = "Inbox filter candidates:\n\(filters)\n\n\(app.debugDescription)"
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
