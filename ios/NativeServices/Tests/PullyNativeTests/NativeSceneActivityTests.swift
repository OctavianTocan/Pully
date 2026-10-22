import XCTest
@testable import PullyNative

@MainActor
final class NativeSceneActivityTests: XCTestCase {
    func testActiveReplayAndInactivityAreSynchronousWithoutChangingHomeLifecycle() async {
        let activity = NativeSceneActivity(active: true)
        var events: [Bool] = []
        let observation = activity.observe { events.append($0) }
        XCTAssertEqual(events, [true])
        activity.update(active: false)
        XCTAssertEqual(events, [true, false])
        activity.update(active: false)
        activity.update(active: true)
        XCTAssertEqual(events, [true, false, true])
        observation.cancel(); observation.cancel()
        activity.update(active: false)
        XCTAssertEqual(events, [true, false, true])
    }

    func testAReentrantInactiveTransitionCannotDeliverAnObsoleteActiveEvent() async {
        let activity = NativeSceneActivity(active: false)
        var first: [Bool] = []
        var second: [Bool] = []
        let a = activity.observe { active in first.append(active); if active { activity.update(active: false) } }
        let b = activity.observe { active in second.append(active); if active { activity.update(active: false) } }
        activity.update(active: true)
        XCTAssertEqual(first.last, false)
        XCTAssertEqual(second.last, false)
        XCTAssertEqual(first.filter { $0 }.count + second.filter { $0 }.count, 1)
        a.cancel(); b.cancel()
    }

    func testRegistrationDuringAnEventImmediatelySeesTheCurrentState() async {
        let activity = NativeSceneActivity(active: false)
        var nested: NativeSceneActivityObservation?
        var events: [Bool] = []
        let outer = activity.observe { active in
            if active { nested = activity.observe { events.append($0) } }
        }
        activity.update(active: true)
        XCTAssertEqual(events, [true])
        activity.update(active: false)
        XCTAssertEqual(events, [true, false])
        outer.cancel(); nested?.cancel()
    }

    func testCloseRevokesActiveObserversAndCannotBeReenteredOrReopened() async {
        let activity = NativeSceneActivity(active: true)
        var events: [Bool] = []
        let observation = activity.observe { active in
            events.append(active)
            if !active { activity.update(active: true) }
        }
        activity.close()
        activity.update(active: true)
        XCTAssertEqual(events, [true, false])
        var late: [Bool] = []
        let last = activity.observe { late.append($0) }
        XCTAssertEqual(late, [false])
        observation.cancel(); last.cancel()
    }

    func testCloseInsideAnEventPausesEveryExistingObserverBeforeReturning() async {
        let activity = NativeSceneActivity(active: false)
        var first = false
        var second = false
        let a = activity.observe { active in
            first = active
            if active { activity.close(); XCTAssertFalse(first); XCTAssertFalse(second) }
        }
        let b = activity.observe { active in
            second = active
            if active { activity.close(); XCTAssertFalse(first); XCTAssertFalse(second) }
        }
        activity.update(active: true)
        XCTAssertFalse(first)
        XCTAssertFalse(second)
        a.cancel(); b.cancel()
    }
}
