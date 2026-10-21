import XCTest
@testable import PullyNative

final class NativeBackGestureTests: XCTestCase {
    func testProgressUsesTheSharedLeadingEdgeAndStaysWithinThePage() {
        XCTAssertEqual(NativeBackGestureMetrics.progress(translation: 100, width: 400, rightEdge: false), 0.25)
        XCTAssertEqual(NativeBackGestureMetrics.progress(translation: -100, width: 400, rightEdge: true), 0.25)
        XCTAssertEqual(NativeBackGestureMetrics.progress(translation: -100, width: 400, rightEdge: false), 0)
        XCTAssertEqual(NativeBackGestureMetrics.progress(translation: 900, width: 400, rightEdge: false), 1)
        XCTAssertEqual(NativeBackGestureMetrics.progress(translation: 100, width: 0, rightEdge: false), 0)
    }

    func testCommitRequiresProgressOrAnInwardFlickAndHonorsReversal() {
        XCTAssertFalse(NativeBackGestureMetrics.commits(progress: 0.3, inwardVelocity: 0))
        XCTAssertTrue(NativeBackGestureMetrics.commits(progress: 0.6, inwardVelocity: 0))
        XCTAssertTrue(NativeBackGestureMetrics.commits(progress: 0.1, inwardVelocity: 600))
        XCTAssertFalse(NativeBackGestureMetrics.commits(progress: 0, inwardVelocity: 600))
        XCTAssertFalse(NativeBackGestureMetrics.commits(progress: 0.8, inwardVelocity: -600))
        XCTAssertFalse(NativeBackGestureMetrics.commits(progress: .nan, inwardVelocity: 600))
    }
}

@MainActor
final class NativeBackGestureSessionTests: XCTestCase {
    func testDisabledNavigationAtTheFinalProgressCancelsInsteadOfCommitting() async {
        var enabled = true
        var cancelled = 0
        var committed = 0
        let session = NativeBackGestureSession(
            enabled: { enabled }, began: {}, changed: { if $0 >= 0.8 { enabled = false } },
            cancelled: { cancelled += 1 }, committed: { committed += 1 }
        )
        session.begin(progress: 0)
        session.end(progress: 0.8, inwardVelocity: 700)
        session.end(progress: 0.9, inwardVelocity: 700)
        session.interrupt()
        XCTAssertEqual(cancelled, 1)
        XCTAssertEqual(committed, 0)
    }

    func testRouteResetBeforeReleaseCannotCommitAnOldDrag() async {
        var enabled = true
        var cancelled = 0
        var committed = 0
        let session = NativeBackGestureSession(
            enabled: { enabled }, began: {}, changed: { _ in },
            cancelled: { cancelled += 1 }, committed: { committed += 1 }
        )
        session.begin(progress: 0)
        session.change(progress: 0.8)
        enabled = false
        session.end(progress: 0.9, inwardVelocity: 700)
        XCTAssertEqual(cancelled, 1)
        XCTAssertEqual(committed, 0)
    }

    func testReentrantRemovalDuringFinalProgressCancelsExactlyOnce() async {
        var cancelled = 0
        var committed = 0
        var session: NativeBackGestureSession!
        session = NativeBackGestureSession(
            enabled: { true }, began: {}, changed: { if $0 >= 0.8 { session.remove() } },
            cancelled: { cancelled += 1 }, committed: { committed += 1 }
        )
        session.begin(progress: 0)
        session.end(progress: 0.8, inwardVelocity: 700)
        session.remove()
        session.begin(progress: 0)
        XCTAssertEqual(cancelled, 1)
        XCTAssertEqual(committed, 0)
        session = nil
    }

    func testInterruptedDragCanStartFreshWithoutTheOldReleaseFinishingIt() async {
        var cancelled = 0
        var committed = 0
        var replaced = false
        var session: NativeBackGestureSession!
        session = NativeBackGestureSession(
            enabled: { true }, began: {}, changed: { progress in
                if progress >= 0.8 && !replaced {
                    replaced = true
                    session.interrupt()
                    session.begin(progress: 0)
                }
            }, cancelled: { cancelled += 1 }, committed: { committed += 1 }
        )
        session.begin(progress: 0)
        session.end(progress: 0.8, inwardVelocity: 700)
        XCTAssertEqual(cancelled, 1)
        XCTAssertEqual(committed, 0)
        session.end(progress: 0.8, inwardVelocity: 700)
        XCTAssertEqual(committed, 1)
        session = nil
    }
}
