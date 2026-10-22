import XCTest
@testable import PullyNative

final class NativeHomeActionsTests: XCTestCase {
    func testExternalGithubLinksRetainPathsAndAnchors() {
        let raw = "https://github.com/OctavianTocan/pulls.review/pull/79#discussion_r1"
        XCTAssertEqual(NativeGithubLink.url(raw)?.absoluteString, raw)
        XCTAssertNotNil(NativeGithubLink.url("HTTPS://GITHUB.COM:443/owner/repo"))
    }

    func testExternalNavigationRejectsCredentialsCallbacksAndOtherOrigins() {
        for value in ["http://github.com/owner/repo", "pully://github.com/owner/repo",
                      "https://github.com.evil.example/owner/repo", "https://person:secret@github.com/owner/repo",
                      "https://github.com:444/owner/repo", "https://server.example/private"] {
            XCTAssertNil(NativeGithubLink.url(value), value)
        }
    }

    func testExplicitBrowserLinksPreserveTheRequestedWebAddress() {
        for value in ["https://github.com/owner/repo/pull/79#discussion_r1",
                      "http://example.test:8080/a?state=opened#detail"] {
            XCTAssertEqual(NativeBrowserLink.url(value)?.absoluteString, value)
        }
    }

    func testExplicitBrowserRejectsNonWebSchemesMissingHostsAndCredentials() {
        for value in ["pully://pull/79", "javascript:alert(1)", "file:///tmp/secret", "https:/path",
                      "https://person:secret@github.com/owner/repo", "https://github.com/\nnext"] {
            XCTAssertNil(NativeBrowserLink.url(value), value)
        }
    }
}

@MainActor
final class NativeSceneLifecycleTests: XCTestCase {
    func testRegistrationReportsCurrentStateAndRealTransitionsExactlyOnce() async {
        let scene = NativeSceneLifecycle(phase: .foregroundActive)
        var events: [NativeSceneLifecycle.Event] = []
        let subscription = scene.observe { events.append($0) }
        XCTAssertEqual(events, [.started, .resumed])
        scene.update(.foregroundActive)
        scene.update(.foregroundInactive)
        scene.update(.foregroundActive)
        scene.update(.background)
        scene.update(.foregroundInactive)
        XCTAssertEqual(events, [.started, .resumed, .resumed, .stopped, .started])
        subscription.cancel()
        subscription.cancel()
        scene.update(.foregroundActive)
        XCTAssertEqual(events, [.started, .resumed, .resumed, .stopped, .started])
    }

    func testCancellingOneObserverKeepsOtherRegistrationsUntilDisconnect() async {
        let scene = NativeSceneLifecycle()
        var first: [NativeSceneLifecycle.Event] = []
        var second: [NativeSceneLifecycle.Event] = []
        let a = scene.observe { first.append($0) }
        let b = scene.observe { second.append($0) }
        a.cancel()
        scene.update(.foregroundActive)
        scene.disconnect()
        scene.update(.foregroundActive)
        XCTAssertEqual(first, [.stopped])
        XCTAssertEqual(second, [.stopped, .started, .resumed, .stopped])
        b.cancel()
    }

    func testObserverRegisteredDuringTransitionReceivesCurrentStateWithoutDuplicateResume() async {
        let scene = NativeSceneLifecycle()
        var events: [NativeSceneLifecycle.Event] = []
        var nested: NativeLifecycleObservation?
        let outer = scene.observe { event in
            if event == .started { nested = scene.observe { events.append($0) } }
        }
        scene.update(.foregroundActive)
        XCTAssertEqual(events, [.started, .resumed])
        nested?.cancel()
        outer.cancel()
    }

    func testReentrantBackgroundTransitionCannotResumeAnOldForegroundEvent() async {
        let scene = NativeSceneLifecycle()
        var events: [NativeSceneLifecycle.Event] = []
        let subscription = scene.observe { event in
            events.append(event)
            if event == .started { scene.update(.background) }
        }
        scene.update(.foregroundActive)
        XCTAssertEqual(events, [.stopped, .started, .stopped])
        subscription.cancel()
    }

    func testEveryObserverReceivesStartedWhenAnotherObserverMakesTheSceneInactive() async {
        let scene = NativeSceneLifecycle()
        var first: [NativeSceneLifecycle.Event] = []
        var second: [NativeSceneLifecycle.Event] = []
        let a = scene.observe { event in
            first.append(event)
            if event == .started { scene.update(.foregroundInactive) }
        }
        let b = scene.observe { event in
            second.append(event)
            if event == .started { scene.update(.foregroundInactive) }
        }
        scene.update(.foregroundActive)
        XCTAssertEqual(first, [.stopped, .started])
        XCTAssertEqual(second, [.stopped, .started])
        scene.update(.foregroundActive)
        XCTAssertEqual(first, [.stopped, .started, .resumed])
        XCTAssertEqual(second, [.stopped, .started, .resumed])
        a.cancel()
        b.cancel()
    }

    func testDisconnectFencesReentrantForegroundRequestsAndNewRegistrations() async {
        let scene = NativeSceneLifecycle(phase: .foregroundActive)
        var first: [NativeSceneLifecycle.Event] = []
        var second: [NativeSceneLifecycle.Event] = []
        let a = scene.observe { event in
            first.append(event)
            if event == .stopped { scene.update(.foregroundActive) }
        }
        let b = scene.observe { event in
            second.append(event)
            if event == .stopped { scene.update(.foregroundActive) }
        }
        scene.disconnect()
        scene.update(.foregroundActive)
        XCTAssertEqual(first, [.started, .resumed, .stopped])
        XCTAssertEqual(second, [.started, .resumed, .stopped])
        var late: [NativeSceneLifecycle.Event] = []
        let c = scene.observe { late.append($0) }
        scene.update(.foregroundInactive)
        XCTAssertEqual(late, [.stopped])
        a.cancel()
        b.cancel()
        c.cancel()
    }
}
