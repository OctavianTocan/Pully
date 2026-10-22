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

    private func fixtureApplication() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["PULLY_UI_TEST_FIXTURES"] = "1"
        return app
    }

    private func rootJourneyApplication() -> XCUIApplication {
        let app = XCUIApplication()
        appForDiagnostics = app
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        // The UUID keeps this real Keychain service/preferences apart from every other test and ordinary launch.
        app.launchEnvironment["PULLY_UI_TEST_ROOT_JOURNEY"] = UUID().uuidString
        app.launchEnvironment["PULLY_UI_TEST_APPEARANCE"] = "light"
        return app
    }

    private func fullRootJourneyApplication() -> XCUIApplication {
        let app = rootJourneyApplication()
        app.launchEnvironment["PULLY_UI_TEST_ROOT_JOURNEY_MODE"] = "full"
        return app
    }

    private func signInToRootJourney(in app: XCUIApplication) {
        assertVisibleLabel("Fixture data", in: app, timeout: 20)
        assertVisibleLabel("Sign in to Pully", in: app)
        let email = app.textFields["Email"]
        XCTAssertTrue(email.waitForExistence(timeout: 10))
        email.tap()
        email.typeText("root-fixture@pully.invalid")
        requireVisible("Continue", in: app).tap()
        let password = app.secureTextFields["Password"]
        XCTAssertTrue(password.waitForExistence(timeout: 10))
        password.tap()
        password.typeText("sample-root-password")
        requireVisible("Sign in", in: app).tap()
        assertHiddenLabel("Sign in to Pully", in: app)
    }

    func testActualRootSignInReviewDiffNativeBackAndKeychainRestore() throws {
        let app = rootJourneyApplication()
        app.launch()
        signInToRootJourney(in: app)
        selectAll(in: app)
        let title = "Share the existing Pully review interface"
        requireVisible(title, contains: true, in: app).tap()
        assertVisibleRow("Actual shared review fixture description. This is sample data.", in: app)
        assertSectionSelected("Overview", in: app)
        attachScreenshot(name: "Actual root signed in shared Overview")

        // The labelled production segment opens the SAME ReviewSession and PrDiffScreen.
        requireVisible("Changes", in: app).tap()
        assertSectionSelected("Changes", in: app)
        assertVisibleRow("fun review() = \"shared\"", in: app)
        assertVisibleRow("Review.kt", in: app)
        attachScreenshot(name: "Actual root shared Diff")

        // Nested review Back consumes the same UIKit edge observer before the app route is popped.
        leadingEdgeBack(in: app)
        assertSectionSelected("Overview", in: app)
        assertVisibleRow("Actual shared review fixture description. This is sample data.", in: app)
        leadingEdgeBack(in: app)
        assertAllSelected(in: app)
        assertVisibleRow(title, in: app)
        assertHiddenLabel("Back", in: app)
        attachScreenshot(name: "Actual root Native back restored Inbox")

        // Termination removes the in-memory AccountRuntime. The genuine Keychain token must be verified on relaunch.
        app.terminate()
        app.launch()
        assertVisibleLabel("Fixture data", in: app, timeout: 20)
        assertHiddenLabel("Sign in to Pully", in: app)
        selectAll(in: app)
        assertVisibleRow(title, in: app)
        requireVisible(title, contains: true, in: app).tap()
        assertVisibleRow("Actual shared review fixture description. This is sample data.", in: app)
        attachScreenshot(name: "Actual root restored verified private account")
    }

    func testCompleteReviewStandardBackgroundTerminationTourBriefAndChatHistory() throws {
        let app = fullRootJourneyApplication()
        app.launch()
        signInToRootJourney(in: app)
        openSampleReview(in: app)
        requireVisible("Changes", in: app).tap()
        assertSectionSelected("Changes", in: app)

        // Tap the actual code row to select its addition, then type in the production comment pill.
        requireVisible("fun review() = \"shared\"", contains: true, in: app).tap()
        let comment = requireEditor(in: app)
        comment.tap()
        comment.typeText("Fixture line comment.")
        requireVisible("Send comment", in: app).tap()
        assertVisibleLabel("Comment added to your pending review", in: app, timeout: 10)
        attachScreenshot(name: "Actual selected-line pending comment")
        leadingEdgeBack(in: app)
        assertSectionSelected("Overview", in: app)
        requireVisible("More", in: app).tap()
        requireVisible("Submit comments", contains: true, in: app).tap()
        assertVisibleLabel("Review changes", in: app)
        let summary = requireEditor(in: app)
        summary.tap()
        summary.typeText("Fixture review summary.")
        reveal("Submit", in: app).tap()
        assertHiddenLabel("Review changes", in: app)
        attachScreenshot(name: "Actual pending review submitted")

        let started = startSampleAnalysis("Standard", in: app)
        XCUIDevice.shared.press(.home)
        assertBackgrounded(app)
        app.terminate()
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 10))
        waitForSampleServerCompletion(since: started)
        app.launch()
        assertVisibleLabel("Fixture data", in: app, timeout: 20)
        assertHiddenLabel("Sign in to Pully", in: app)
        openSampleReview(in: app)
        reveal("Fixture typed Brief uses the same production reader.", contains: true, in: app)
        reveal("Fixture shared code", contains: true, in: app)
        attachScreenshot(name: "Actual process reopen completed typed Brief")

        requireVisible("Tour", in: app).tap()
        assertSectionSelected("Tour", in: app)
        assertVisibleRow("Fixture tour from the shared review", in: app)
        assertVisibleRow("One shared session", in: app)
        attachScreenshot(name: "Actual process reopen completed Tour")

        // The Tour's actual Ask bar hands this question to the shared Chat composer and task request.
        requireVisible("Ask", in: app).tap()
        let question = requireEditor(in: app)
        question.tap()
        question.typeText("What changed in this shared review?")
        requireVisible("Send", in: app).tap()
        assertVisibleRow("The fixture keeps one shared review session and its native adapters.", in: app, timeout: 20)
        attachScreenshot(name: "Actual shared Chat saved answer")
        requireVisible("Earlier chats", in: app).tap()
        assertVisibleLabel("Chats", in: app)
        requireVisible("A previous fixture question", contains: true, in: app).tap()
        assertVisibleRow("The earlier fixture conversation is restored through the real client.", in: app, timeout: 20)
        attachScreenshot(name: "Actual saved Chat history restore")
        requireVisible("New chat", in: app).tap()
        assertHiddenLabel("The earlier fixture conversation is restored through the real client.", in: app)
        attachScreenshot(name: "Actual new Chat cleared immediately")
    }

    func testDeepAnalysisReconcilesItsParallelReviewAndTypedResultsOnForeground() throws {
        let app = fullRootJourneyApplication()
        app.launch()
        signInToRootJourney(in: app)
        openSampleReview(in: app)
        let started = startSampleAnalysis("Deep", in: app)
        XCUIDevice.shared.press(.home)
        assertBackgrounded(app)
        waitForSampleServerCompletion(since: started)
        app.activate()
        assertSectionSelected("Overview", in: app)
        reveal("Fixture typed Brief uses the same production reader.", contains: true, in: app)
        reveal("Read the source ownership guard", contains: true, in: app).tap()
        assertSectionSelected("Changes", in: app)
        assertVisibleRow("fun review() = \"shared\"", in: app)
        attachScreenshot(name: "Actual Deep critique opened its displayed source")
        leadingEdgeBack(in: app)
        requireVisible("Tour", in: app).tap()
        assertSectionSelected("Tour", in: app)
        assertVisibleRow("Fixture tour from the shared review", in: app)
        attachScreenshot(name: "Actual Deep foreground reconciled complete results")
    }

    func testExplicitStopSurvivesProcessReopenAndANewStandardAnalysisCanFinish() throws {
        let app = fullRootJourneyApplication()
        app.launch()
        signInToRootJourney(in: app)
        openSampleReview(in: app)
        let stoppedStart = startSampleAnalysis("Standard", in: app)
        requireVisible("Tour", in: app).tap()
        assertVisibleLabel("Writing the tour", in: app)
        // Respect the production grace period that prevents the start tap from also stopping its task.
        waitForElapsed(since: stoppedStart, seconds: 1)
        requireVisible("Stop", in: app).tap()
        assertVisibleLabel("No tour yet", in: app)
        attachScreenshot(name: "Actual explicit Stop retired only its server task")
        leadingEdgeBack(in: app)

        XCUIDevice.shared.press(.home)
        assertBackgrounded(app)
        app.terminate()
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 10))
        waitForSampleServerCompletion(since: stoppedStart)
        app.launch()
        assertVisibleLabel("Fixture data", in: app, timeout: 20)
        assertHiddenLabel("Sign in to Pully", in: app)
        openSampleReview(in: app)
        requireVisible("Tour", in: app).tap()
        assertVisibleLabel("No tour yet", in: app)
        attachScreenshot(name: "Actual process reopen retained the explicitly stopped chain")
        leadingEdgeBack(in: app)

        let restarted = startSampleAnalysis("Standard", in: app)
        XCUIDevice.shared.press(.home)
        assertBackgrounded(app)
        waitForSampleServerCompletion(since: restarted)
        app.activate()
        reveal("Fixture typed Brief uses the same production reader.", contains: true, in: app)
        requireVisible("Tour", in: app).tap()
        assertSectionSelected("Tour", in: app)
        assertVisibleRow("Fixture tour from the shared review", in: app)
        attachScreenshot(name: "Actual fresh Standard finished after explicit Stop")
    }

    private func openSampleReview(in app: XCUIApplication) {
        selectAll(in: app)
        requireVisible("Share the existing Pully review interface", contains: true, in: app).tap()
        assertSectionSelected("Overview", in: app)
    }

    @discardableResult
    private func startSampleAnalysis(_ preset: String, in app: XCUIApplication) -> Date {
        requireVisible("More", in: app).tap()
        requireVisible("Analyze", in: app).tap()
        // Waiting for the actual catalog also proves Deep uses this model's real high-effort choice.
        assertVisibleRow("Fixture review model", in: app, timeout: 20)
        requireVisible(preset, contains: true, in: app).tap()
        assertVisibleLabel("\(preset) analysis started. It keeps running if you leave.", in: app, timeout: 10)
        let started = Date()
        attachScreenshot(name: "Actual \(preset) accepted task")
        return started
    }

    private func assertBackgrounded(_ app: XCUIApplication) {
        let background = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.state == .runningBackground || app.state == .runningBackgroundSuspended
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [background], timeout: 10), .completed)
    }

    private func waitForSampleServerCompletion(since started: Date) {
        // The UI runner waits while the app is backgrounded or dead. It never advances fixture state or calls an app hook.
        waitForElapsed(since: started, seconds: 11.5)
    }

    private func waitForElapsed(since started: Date, seconds: TimeInterval) {
        let deadline = started.addingTimeInterval(seconds)
        let elapsed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in Date() >= deadline }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [elapsed], timeout: seconds + 1.5), .completed)
    }

    private func requireEditor(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        func editor() -> XCUIElement? {
            (app.textFields.allElementsBoundByIndex + app.textViews.allElementsBoundByIndex).first { element in
                element.exists && element.isHittable && !element.frame.isEmpty && app.frame.intersects(element.frame)
            }
        }
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in editor() != nil }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: 10) == .completed, let field = editor() else {
            attachDiagnostics(app, name: "Missing actual shared text editor")
            XCTFail("The actual shared field was not available for keyboard input", file: file, line: line)
            return app.textFields.firstMatch
        }
        return field
    }

    @discardableResult
    private func reveal(_ label: String, contains: Bool = false, in app: XCUIApplication,
                        file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        for _ in 0..<8 {
            if let element = visibleElement(label, contains: contains, in: app), element.isHittable { return element }
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.73))
                .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.32)))
        }
        return requireVisible(label, contains: contains, in: app, file: file, line: line)
    }

    func testSystemDeliveredWarmAndColdLinksKeepTheSharedReviewAndBackStack() throws {
        let app = rootJourneyApplication()
        app.launch()
        signInToRootJourney(in: app)
        selectAll(in: app)
        requireVisible("mobile-fixtures", in: app).tap()
        assertVisibleLabel("Pull requests", in: app)

        let pull = "https://github.com/pully/mobile-fixtures/pull/76"
        openSystemLink(customLink(pull), in: app)
        assertVisibleRow("Actual shared review fixture description. This is sample data.", in: app)
        assertSectionSelected("Overview", in: app)
        // SHA256 of the exact UTF-8 path src/Review.kt, the existing shared GitHub anchor policy.
        let files = pull + "/files#diff-53259ec17662dee09f6fa100315d96c65435f26fa0861870b9033a099df74830R1"
        openSystemLink(customLink(files), in: app)
        assertSectionSelected("Changes", in: app)
        assertVisibleRow("fun review() = \"shared\"", in: app)
        attachScreenshot(name: "System warm link reused shared Diff")
        leadingEdgeBack(in: app)
        assertSectionSelected("Overview", in: app)
        leadingEdgeBack(in: app)
        // A second link to the same PR must not have pushed a second Review route above Repo.
        assertVisibleLabel("Pull requests", in: app)
        leadingEdgeBack(in: app)
        assertAllSelected(in: app)
        assertHiddenLabel("Back", in: app)

        openSystemLink(customLink(pull + String(repeating: "9", count: 100)), in: app)
        assertVisibleLabel("Pully can't open that link", in: app)
        assertHiddenLabel("Pully can't open that link", in: app)
        XCUIDevice.shared.press(.home)
        app.activate()
        assertHiddenLabel("Pully can't open that link", in: app, timeout: 2)
        assertAllSelected(in: app)
        assertHiddenLabel("Back", in: app)

        app.terminate()
        // This is an OS URL launch, not a direct app test hook. The same genuine private
        // Keychain account must restore and verify before the deferred link opens its route.
        openSystemLink(customLink(files), in: app)
        assertVisibleLabel("Fixture data", in: app, timeout: 20)
        assertHiddenLabel("Sign in to Pully", in: app)
        assertSectionSelected("Changes", in: app)
        assertVisibleRow("fun review() = \"shared\"", in: app)
        attachScreenshot(name: "System cold link waited for verified private account")
        leadingEdgeBack(in: app)
        assertSectionSelected("Overview", in: app)
        leadingEdgeBack(in: app)
        selectAll(in: app)
        assertHiddenLabel("Back", in: app)
    }

    private func customLink(_ target: String) -> URL {
        var value = URLComponents()
        value.scheme = "pully"
        value.host = "open"
        value.queryItems = [URLQueryItem(name: "url", value: target)]
        return value.url!
    }

    private func openSystemLink(_ url: URL, in app: XCUIApplication) {
        app.open(url)
        let confirmation = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if confirmation.waitForExistence(timeout: 2) { confirmation.tap() }
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
    }

    func testSharedPreviewSurvivesRotationAndRelaunch() throws {
        let app = fixtureApplication()
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
        let app = fixtureApplication()
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
        let app = fixtureApplication()
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
        let app = fixtureApplication()
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

    private func assertSectionSelected(_ section: String, in app: XCUIApplication,
                                       file: StaticString = #filePath, line: UInt = #line) {
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.visibleElement(section, in: app)?.isSelected == true
        }, object: nil)
        guard XCTWaiter.wait(for: [selected], timeout: 10) == .completed else {
            attachDiagnostics(app, name: "Shared review section not selected: \(section)")
            XCTFail("The production review segment did not select \(section)", file: file, line: line)
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
