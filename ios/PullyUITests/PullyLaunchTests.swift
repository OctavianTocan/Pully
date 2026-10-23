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

    private func fullRootJourneyApplication(scenario: String? = nil) -> XCUIApplication {
        let app = rootJourneyApplication()
        app.launchEnvironment["PULLY_UI_TEST_ROOT_JOURNEY_MODE"] = "full"
        if let scenario {
            app.launchEnvironment["PULLY_UI_TEST_ROOT_JOURNEY_SCENARIO"] = scenario
        }
        return app
    }

    private func signInToRootJourney(in app: XCUIApplication) {
        assertVisibleLabel("Fixture data", in: app, timeout: 20)
        assertVisibleLabel("Sign in to Pully", in: app)
        let email = requireInput("Email", in: app)
        email.tap()
        email.typeText("root-fixture@pully.invalid")
        requireVisible("Continue", in: app).tap()
        // A tap that lands while the keyboard is still settling the scrolled form can miss; Continue is idempotent.
        let passwordShown = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.visibleElement("Password", in: app) != nil
        }, object: nil)
        if XCTWaiter.wait(for: [passwordShown], timeout: 5) != .completed, let again = visibleElement("Continue", in: app) {
            again.tap()
        }
        let password = requireInput("Password", in: app)
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

    func testRejectedStopRemainsRunningAndCanRetryAfterProcessReopen() throws {
        let app = fullRootJourneyApplication(scenario: "stop-rejected-once")
        app.launch()
        signInToRootJourney(in: app)
        openSampleReview(in: app)
        let started = startSampleAnalysis("Standard", in: app)
        requireVisible("Tour", in: app).tap()
        assertVisibleLabel("Writing the tour", in: app)
        waitForElapsed(since: started, seconds: 1)
        requireVisible("Stop", in: app).tap()
        // The actual RPC returns HTTP 503; the real task-backed Stop must expose it without inventing cancellation.
        assertVisibleLabel("Sample stop unavailable.", in: app, timeout: 10)
        assertVisibleLabel("Writing the tour", in: app)
        XCTAssertNotNil(visibleElement("Stop", in: app))
        attachScreenshot(name: "Actual rejected Stop retained running work and retry")

        app.terminate()
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 10))
        app.launch()
        assertVisibleLabel("Fixture data", in: app, timeout: 20)
        assertHiddenLabel("Sign in to Pully", in: app)
        openSampleReview(in: app)
        requireVisible("Tour", in: app).tap()
        assertVisibleLabel("Writing the tour", in: app)
        requireVisible("Stop", in: app).tap()
        assertVisibleLabel("No tour yet", in: app)
        attachScreenshot(name: "Actual process reopen retried the previously rejected Stop")
    }

    func testFailedAnalysisReopensAsAnErrorAndFreshStandardRestoresTypedResultsAndSavedChat() throws {
        let app = fullRootJourneyApplication(scenario: "analysis-failed-once")
        app.launch()
        signInToRootJourney(in: app)
        openSampleReview(in: app)
        let failedStart = startSampleAnalysis("Standard", in: app)
        XCUIDevice.shared.press(.home)
        assertBackgrounded(app)
        app.terminate()
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 10))
        waitForSampleServerCompletion(since: failedStart)
        app.launch()
        assertVisibleLabel("Fixture data", in: app, timeout: 20)
        assertHiddenLabel("Sign in to Pully", in: app)
        openSampleReview(in: app)
        requireVisible("Tour", in: app).tap()
        assertVisibleLabel("No tour yet", in: app)
        assertVisibleRow("The tour could not be written", in: app)
        assertVisibleRow("Sample analysis failed. Try again.", in: app)
        attachScreenshot(name: "Actual failed analysis reconciled after process termination")
        leadingEdgeBack(in: app)
        assertSectionSelected("Overview", in: app)

        let restarted = startSampleAnalysis("Standard", in: app)
        XCUIDevice.shared.press(.home)
        assertBackgrounded(app)
        waitForSampleServerCompletion(since: restarted)
        app.activate()
        assertSectionSelected("Overview", in: app)
        reveal("Fixture typed Brief uses the same production reader.", contains: true, in: app)
        reveal("Fixture shared code", contains: true, in: app)
        reveal("Fixture shared state", contains: true, in: app)
        attachScreenshot(name: "Actual retried Standard completed Code and Diagram Brief readers")
        requireVisible("Tour", in: app).tap()
        assertSectionSelected("Tour", in: app)
        assertVisibleRow("Fixture tour from the shared review", in: app)
        requireVisible("Ask", in: app).tap()
        let question = requireEditor(in: app)
        question.tap()
        question.typeText("What changed in this shared review?")
        requireVisible("Send", in: app).tap()
        assertVisibleRow("The fixture keeps one shared review session and its native adapters.", in: app, timeout: 20)

        // A second cold launch must read the saved answer, rather than replay the keyboard submission.
        app.terminate()
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 10))
        app.launch()
        assertVisibleLabel("Fixture data", in: app, timeout: 20)
        assertHiddenLabel("Sign in to Pully", in: app)
        openSampleReview(in: app)
        requireVisible("Tour", in: app).tap()
        assertSectionSelected("Tour", in: app)
        // The foot bar's Ask opens an empty question (Q5); the saved conversation is the Ask sheet's, from the Tour's ⋯.
        requireVisible("More", in: app).tap()
        requireVisible("Ask about this tour", in: app).tap()
        assertVisibleRow("What changed in this shared review?", in: app)
        assertVisibleRow("The fixture keeps one shared review session and its native adapters.", in: app)
        attachScreenshot(name: "Actual second process reopen restored the saved Chat answer")
        requireVisible("Earlier chats", in: app).tap()
        assertVisibleLabel("Chats", in: app)
        requireVisible("A previous fixture question", contains: true, in: app).tap()
        assertVisibleRow("The earlier fixture conversation is restored through the real client.", in: app, timeout: 20)
        attachScreenshot(name: "Actual saved history remained usable after failed analysis and reopen")
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
        // RootJourneyWork.COMPLETION_MILLIS (48 s) plus margin.
        waitForElapsed(since: started, seconds: 49.5)
    }

    private func waitForElapsed(since started: Date, seconds: TimeInterval) {
        let deadline = started.addingTimeInterval(seconds)
        let elapsed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in Date() >= deadline }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [elapsed], timeout: seconds + 1.5), .completed)
    }

    /// Compose exposes its fields as text views whose label is the field label followed by the placeholder.
    private func requireInput(_ label: String, in app: XCUIApplication,
                              file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let named = NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, "\(label),")
        func input() -> XCUIElement? {
            (app.textFields.matching(named).allElementsBoundByIndex
                + app.secureTextFields.matching(named).allElementsBoundByIndex
                + app.textViews.matching(named).allElementsBoundByIndex).first { element in
                element.exists && element.isHittable && !element.frame.isEmpty && app.frame.intersects(element.frame)
            }
        }
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in input() != nil }, object: nil)
        guard XCTWaiter.wait(for: [ready], timeout: 10) == .completed, let field = input() else {
            attachDiagnostics(app, name: "Missing text input: \(label)")
            XCTFail("Missing text input: \(label)", file: file, line: line)
            return app.textFields[label]
        }
        return field
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
            scrollContent(in: app, from: 0.73, to: 0.32)
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
        // The Inbox is back on screen first: just after activation the accessibility tree can still hold the old toast.
        // A replayed toast holds for seconds in the sample journeys, so it would still be found here.
        assertAllSelected(in: app)
        assertHiddenLabel("Pully can't open that link", in: app, timeout: 2)
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
        // Through the system, as a link tapped in another app arrives: XCUIApplication.open relaunches the app with
        // the URL, so a running app lost its pages and a "warm" link was really a cold one.
        XCUIDevice.shared.system.open(url)
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
            scrollContent(in: app, from: 0.76, to: 0.32)
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
        // The hold sheet shows the complete pull request first; its GitHub actions sit below the fold.
        revealInSheet("Copy link", in: app)
        assertVisibleLabel("Copy link", in: app)
        XCTAssertNil(visibleElement("Start a tour", in: app))
        XCTAssertNil(visibleElement("Delete", in: app))
        requireVisible("Copy link", in: app).tap()
        assertVisibleLabel("Link copied", in: app, timeout: 5)
        // The app writes via the real UIKit adapter. Reading another process's pasteboard
        // would add a privacy prompt; native service tests verify the value boundary.
        attachScreenshot(name: "Production hold menu copy confirmation")
    }

    private func revealInSheet(_ label: String, in app: XCUIApplication) {
        _ = requireVisible("Close", in: app)
        for _ in 0..<4 where visibleElement(label, in: app) == nil {
            scrollContent(in: app, from: 0.85, to: 0.45)
        }
    }

    /// Drags the content up without letting it glide on: a Compose list still moving takes the next tap as "stop".
    private func scrollContent(in app: XCUIApplication, from start: CGFloat, to end: CGFloat) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: start))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: end)),
                   withVelocity: .slow, thenHoldForDuration: 0.2)
    }

    /// Waits until the element's frame holds still across two reads, so a tap lands on a row at rest.
    private func settled(_ element: XCUIElement) -> XCUIElement {
        var last = element.frame
        for _ in 0..<12 {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
            let now = element.frame
            if now == last { return element }
            last = now
        }
        return element
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
        // A tap while the first rows are still arriving can be lost; one more tap, then the same selected check.
        if !waitForSelected(filter, timeout: 5) { filter.tap() }
        assertAllSelected(in: app)
    }

    private func waitForSelected(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in element.exists && element.isSelected }, object: nil)
        return XCTWaiter.wait(for: [selected], timeout: timeout) == .completed
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

// Additional real UIKit journeys share every existing helper and preserve the original suite verbatim.
extension PullyLaunchTests {
    @objc func testSettingsModelEffortAndCommandsKeepTheSameVerifiedWorkspace() {
        let app = fullRootJourneyApplication()
        app.launch()
        signInToRootJourney(in: app)
        selectAll(in: app)
        openJourneySettings(in: app)
        revealJourneyButton("Model", contains: true, in: app).tap()
        assertVisibleLabel("Select model", in: app)
        requireJourneyButton("Fixture alternate model", contains: true, in: app).tap()
        assertHiddenLabel("Select model", in: app)
        assertVisibleRow("Fixture alternate model", in: app)
        revealJourneyButton("Effort", contains: true, in: app).tap()
        requireJourneyButton("High", in: app).tap()
        assertVisibleRow("High", in: app)
        attachScreenshot(name: "Actual Settings saved the second model and high effort")
        leadingEdgeBack(in: app)
        assertAllSelected(in: app)

        app.terminate()
        app.launch()
        assertVisibleLabel("Fixture data", in: app, timeout: 20)
        assertHiddenLabel("Sign in to Pully", in: app)
        openJourneySettings(in: app)
        revealJourneyButton("Model", contains: true, in: app)
        assertVisibleRow("Fixture alternate model", in: app)
        assertVisibleRow("High", in: app)
        attachScreenshot(name: "Actual native storage restored model and effort")
        leadingEdgeBack(in: app)
        assertAllSelected(in: app)

        pullToOpenJourneyCommands(in: app)
        let search = requireEditor(in: app)
        search.typeText("Send feedback")
        assertEditorValue("Send feedback", editor: search, in: app)
        requireJourneyButton("Send feedback", in: app).tap()
        assertHiddenLabel("Commands", in: app)
        assertVisibleRow("Fixture feedback from before this launch.", in: app)
        attachScreenshot(name: "Actual Commands search opened the shared Feedback sheet")
        leadingEdgeBack(in: app)
        assertSoftwareKeyboard(false, in: app)
        assertAllSelected(in: app)
        assertVisibleRow("Share the existing Pully review interface", in: app)
        assertHiddenLabel("Back", in: app)
    }

    @objc func testAgentKeyCreationRetryNativePasteCopyOnceAndNestedBack() {
        let app = fullRootJourneyApplication(scenario: "settings-retry-once")
        app.launch()
        signInToRootJourney(in: app)
        selectAll(in: app)
        openJourneySettings(in: app)
        revealJourneyButton("Agent keys", contains: true, in: app).tap()
        assertVisibleLabel("No keys yet.", in: app)
        let name = requireEditor(in: app)
        name.tap()
        name.typeText("Replace this name")
        assertEditorValue("Replace this name", editor: name, in: app)
        selectAllNativeText(in: name, app: app)
        copyAndVerifyNativeSelection("Replace this name", editor: name, app: app)
        name.typeText("Fixture UIKit agent")
        assertEditorValue("Fixture UIKit agent", editor: name, in: app)
        requireJourneyButton("Make key", in: app).tap()
        assertVisibleRow("Sample key creation unavailable. Try again.", in: app)
        assertEditorValue("Fixture UIKit agent", editor: name, in: app)
        requireJourneyButton("Make key", in: app).tap()
        let key = "pully_sample_key_not_a_real_credential"
        assertVisibleRow(key, in: app)
        revealJourneyButton("Copy key", in: app).tap()
        assertVisibleLabel("Copied the key", in: app)
        let clearedName = requireEditor(in: app)
        assertEditorValue("", editor: clearedName, in: app)
        pasteNativeText(into: clearedName, app: app)
        assertEditorValue(key, editor: clearedName, in: app)
        attachScreenshot(name: "Actual native key copy pasted through the OS edit menu")

        // The keyboard is open: one physical Back leaves this nested page, never the underlying Inbox.
        assertSoftwareKeyboard(true, in: app)
        leadingEdgeBack(in: app)
        assertSoftwareKeyboard(false, in: app)
        assertHiddenLabel("Make key", in: app)
        revealJourneyButton("Agent keys", contains: true, in: app).tap()
        assertVisibleLabel("Make key", in: app)
        assertHiddenLabel(key, in: app)
        assertEditorValue("", editor: requireEditor(in: app), in: app)
        requireJourneyButton("Fixture UIKit agent", contains: true, in: app).tap()
        assertVisibleLabel("Revoke key", in: app)
        assertVisibleLabel("Never", in: app)
        leadingEdgeBack(in: app)
        assertVisibleLabel("Make key", in: app)
        requireJourneyButton("Fixture UIKit agent", contains: true, in: app).tap()
        requireJourneyButton("Revoke key", in: app).tap()
        assertVisibleLabel("No keys yet.", in: app)
        assertHiddenLabel("Fixture UIKit agent", in: app)
        attachScreenshot(name: "Actual nested key Back and revoke returned to the same account list")
        leadingEdgeBack(in: app)
        leadingEdgeBack(in: app)
        assertAllSelected(in: app)
        assertHiddenLabel("Back", in: app)
    }

    @objc func testStatsRetryRangesAndFeedbackDraftSurviveKeyboardBackAndSendFailure() {
        let app = fullRootJourneyApplication(scenario: "settings-retry-once")
        app.launch()
        signInToRootJourney(in: app)
        selectAll(in: app)
        openJourneySettings(in: app)
        revealJourneyButton("Stats", in: app).tap()
        assertVisibleRow("Couldn't load the stats.", in: app)
        requireJourneyButton("Retry", in: app).tap()
        assertVisibleRow("73", in: app)
        requireJourneyButton("30 days", in: app).tap()
        assertVisibleRow("303", in: app)
        requireJourneyButton("90 days", in: app).tap()
        assertVisibleRow("903", in: app)
        attachScreenshot(name: "Actual Stats retried and loaded each selected RPC range")
        leadingEdgeBack(in: app)
        revealJourneyButton("Send feedback", in: app).tap()
        assertVisibleRow("Fixture feedback from before this launch.", in: app)
        let editor = requireEditor(in: app)
        editor.tap()
        editor.typeText("Replace this draft")
        selectAllNativeText(in: editor, app: app)
        let note = "Fixture feedback after native text replacement."
        editor.typeText(note)
        assertEditorValue(note, editor: editor, in: app)
        requireJourneyButton("Send feedback", in: app).tap()
        assertVisibleRow("Sample feedback unavailable. Try again.", in: app)
        assertEditorValue(note, editor: editor, in: app)
        editor.tap()
        assertSoftwareKeyboard(true, in: app)
        leadingEdgeBack(in: app)
        assertSoftwareKeyboard(false, in: app)
        assertHiddenLabel("Sample feedback unavailable. Try again.", in: app)
        revealJourneyButton("Send feedback", in: app).tap()
        assertEditorValue(note, editor: requireEditor(in: app), in: app)
        attachScreenshot(name: "Actual nested Feedback retained its draft after failed send and keyboard Back")
        requireJourneyButton("Send feedback", in: app).tap()
        assertVisibleLabel("Feedback sent", in: app)
        assertSoftwareKeyboard(false, in: app)
        revealJourneyButton("Send feedback", in: app).tap()
        assertEditorValue("", editor: requireEditor(in: app), in: app)
        assertVisibleRow(note, in: app)
        attachScreenshot(name: "Actual confirmed feedback came back through the account history RPC")
        leadingEdgeBack(in: app)
        leadingEdgeBack(in: app)
        assertAllSelected(in: app)
        assertHiddenLabel("Back", in: app)
    }

    @objc func testBriefCodeCopyPastesThroughTheOSIntoTheActualFeedbackField() {
        let app = fullRootJourneyApplication()
        app.launch()
        signInToRootJourney(in: app)
        openSampleReview(in: app)
        let started = startSampleAnalysis("Standard", in: app)
        XCUIDevice.shared.press(.home)
        assertBackgrounded(app)
        waitForSampleServerCompletion(since: started)
        app.activate()
        openJourneySettings(in: app)
        revealJourneyButton("Send feedback", in: app).tap()
        let clipboardEditor = requireEditor(in: app)
        assertEditorValue("", editor: clipboardEditor, in: app)
        clipboardEditor.tap()
        let clipboardSeed = "Different native clipboard value before code copy."
        clipboardEditor.typeText(clipboardSeed)
        assertEditorValue(clipboardSeed, editor: clipboardEditor, in: app)
        selectAllNativeText(in: clipboardEditor, app: app)
        copyAndVerifyNativeSelection(clipboardSeed, editor: clipboardEditor, app: app)
        clipboardEditor.typeText(XCUIKeyboardKey.delete.rawValue)
        assertEditorValue("", editor: clipboardEditor, in: app)
        leadingEdgeBack(in: app)
        assertSoftwareKeyboard(false, in: app)
        leadingEdgeBack(in: app)
        assertSectionSelected("Overview", in: app)
        reveal("Fixture shared code", in: app)
        revealJourneyButton("Copy the code", in: app).tap()
        assertVisibleLabel("Code copied", in: app)
        openJourneySettings(in: app)
        revealJourneyButton("Send feedback", in: app).tap()
        let editor = requireEditor(in: app)
        assertEditorValue("", editor: editor, in: app)
        pasteNativeText(into: editor, app: app)
        let code = "fun review() = \"shared\""
        assertEditorValue(code, editor: editor, in: app)
        attachScreenshot(name: "Actual Brief code copied and pasted with the native menu")
        requireJourneyButton("Send feedback", in: app).tap()
        assertVisibleLabel("Feedback sent", in: app)
        revealJourneyButton("Send feedback", in: app).tap()
        assertVisibleRow(code, in: app)
        assertEditorValue("", editor: requireEditor(in: app), in: app)
        leadingEdgeBack(in: app)
        leadingEdgeBack(in: app)
        assertSectionSelected("Overview", in: app)
        reveal("Fixture typed Brief uses the same production reader.", contains: true, in: app)
        attachScreenshot(name: "Actual nested Settings Back retained the same completed review")
    }

    private func openJourneySettings(in app: XCUIApplication) {
        requireJourneyButton("Settings", in: app).tap()
        _ = revealJourneyButton("Agent keys", contains: true, in: app)
    }

    private func pullToOpenJourneyCommands(in app: XCUIApplication) {
        // Actual nested-scroll input passes the production 84dp threshold; no test-side palette action.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.33))
            .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.83)))
        assertVisibleLabel("Commands", in: app)
        assertSoftwareKeyboard(true, in: app)
    }

    private func selectAllNativeText(in editor: XCUIElement, app: XCUIApplication) {
        editor.press(forDuration: 1.2)
        requireNativeEditAction("Select All", in: app).tap()
    }

    private func copyAndVerifyNativeSelection(_ expected: String, editor: XCUIElement, app: XCUIApplication) {
        requireNativeEditAction("Copy", in: app).tap()
        // Compose ends the selection after Copy, as it does on Android, so Delete would take one letter; select the
        // words again first so Delete clears them.
        selectAllNativeText(in: editor, app: app)
        editor.typeText(XCUIKeyboardKey.delete.rawValue)
        assertEditorValue("", editor: editor, in: app)
        pasteNativeText(into: editor, app: app)
        assertEditorValue(expected, editor: editor, in: app)
        selectAllNativeText(in: editor, app: app)
    }

    private func pasteNativeText(into editor: XCUIElement, app: XCUIApplication) {
        // A tap on the caret of a focused, empty field offers Paste at once (a first tap may only focus it), and a long
        // press there closes the menu again on release.
        for _ in 0..<2 {
            editor.tap()
            if let paste = nativeEditAction("Paste", in: app, timeout: 3) {
                paste.tap()
                return
            }
        }
        editor.press(forDuration: 1.2)
        requireNativeEditAction("Paste", in: app).tap()
    }

    private func nativeEditAction(_ title: String, in app: XCUIApplication, timeout: TimeInterval) -> XCUIElement? {
        func action() -> XCUIElement? {
            (app.menuItems.matching(NSPredicate(format: "label == %@", title)).allElementsBoundByIndex +
             app.buttons.matching(NSPredicate(format: "label == %@", title)).allElementsBoundByIndex)
                .first { $0.exists && $0.isHittable && !$0.frame.isEmpty && app.frame.intersects($0.frame) }
        }
        let available = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in action() != nil }, object: nil)
        return XCTWaiter.wait(for: [available], timeout: timeout) == .completed ? action() : nil
    }

    private func requireNativeEditAction(_ title: String, in app: XCUIApplication) -> XCUIElement {
        // Both menu roles are exported by supported iOS text-edit menus; accept no app text/container proxy.
        guard let item = nativeEditAction(title, in: app, timeout: 10) else {
            attachDiagnostics(app, name: "Missing native text edit action: \(title)")
            XCTFail("The actual OS text edit menu did not offer \(title)")
            return app.menuItems[title]
        }
        return item
    }

    @discardableResult
    private func revealJourneyButton(_ title: String, contains: Bool = false, in app: XCUIApplication) -> XCUIElement {
        for _ in 0..<8 {
            if let button = visibleJourneyButton(title, contains: contains, in: app) { return settled(button) }
            scrollContent(in: app, from: 0.73, to: 0.32)
        }
        return settled(requireJourneyButton(title, contains: contains, in: app))
    }

    private func visibleJourneyButton(_ title: String, contains: Bool = false, in app: XCUIApplication) -> XCUIElement? {
        app.buttons.matching(NSPredicate(format: contains ? "label CONTAINS %@" : "label == %@", title)).allElementsBoundByIndex
            .first { $0.exists && $0.isHittable && !$0.frame.isEmpty && app.frame.intersects($0.frame) }
    }

    private func requireJourneyButton(_ title: String, contains: Bool = false, in app: XCUIApplication) -> XCUIElement {
        let available = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            self.visibleJourneyButton(title, contains: contains, in: app) != nil
        }, object: nil)
        guard XCTWaiter.wait(for: [available], timeout: 10) == .completed,
              let item = visibleJourneyButton(title, contains: contains, in: app) else {
            attachDiagnostics(app, name: "Missing actual button: \(title)")
            XCTFail("The actual button did not become hittable: \(title)")
            return app.buttons[title]
        }
        return item
    }

    private func assertEditorValue(_ expected: String, editor: XCUIElement, in app: XCUIApplication,
                                   file: StaticString = #filePath, line: UInt = #line) {
        let matches = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            editor.exists && editor.isHittable && (editor.value as? String) == expected
        }, object: nil)
        guard XCTWaiter.wait(for: [matches], timeout: 10) == .completed else {
            attachDiagnostics(app, name: "Actual editor value mismatch")
            XCTFail("The actual editor did not contain the expected value", file: file, line: line)
            return
        }
    }

    private func assertSoftwareKeyboard(_ visible: Bool, in app: XCUIApplication,
                                        file: StaticString = #filePath, line: UInt = #line) {
        let keyboard = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.keyboards.firstMatch.exists == visible
        }, object: nil)
        guard XCTWaiter.wait(for: [keyboard], timeout: 10) == .completed else {
            attachDiagnostics(app, name: visible ? "Expected actual software keyboard" : "Software keyboard did not leave")
            XCTFail("The actual software keyboard did not reach the expected state", file: file, line: line)
            return
        }
    }
}
