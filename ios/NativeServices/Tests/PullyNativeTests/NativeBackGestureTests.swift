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

#if os(iOS)
import UIKit

@MainActor
final class NativeBackGestureContainerTests: XCTestCase {
    func testComposeDialogBackPriorityCannotPreventTheRegisteredSharedBackRecognizer() async throws {
        for rightEdge in [false, true] {
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
            let content = UIView(frame: window.bounds)
            window.addSubview(content)
            let frameworkBack = DialogBackPriorityRecognizer(target: nil, action: nil)
            content.addGestureRecognizer(frameworkBack)
            let gesture = NativeBackGesture(view: content, rightEdge: rightEdge, enabled: { true },
                                            began: {}, changed: { _ in }, cancelled: {}, committed: {})
            defer {
                gesture.cancel()
                content.removeGestureRecognizer(frameworkBack)
                content.removeFromSuperview()
            }
            let registered = try XCTUnwrap(content.gestureRecognizers?.compactMap { $0 as? UIScreenEdgePanGestureRecognizer }
                .first { $0 !== frameworkBack })
            // Compose's actual framework Back class has this prevention contract. Its
            // click-only Dialog handler deliberately does nothing after progress events.
            XCTAssertTrue(frameworkBack.canPrevent(registered))
            XCTAssertFalse(registered.canBePrevented(by: frameworkBack))
            XCTAssertTrue(registered.canPrevent(frameworkBack))
            XCTAssertFalse(frameworkBack.canBePrevented(by: registered))
            XCTAssertTrue(registered.delaysTouchesBegan)
            XCTAssertTrue(registered.delaysTouchesEnded)
            XCTAssertTrue(registered.cancelsTouchesInView)
            XCTAssertEqual(registered.edges, rightEdge ? .right : .left)
            gesture.cancel()
            XCTAssertFalse(content.gestureRecognizers?.contains(where: { $0 === registered }) == true)
            XCTAssertTrue(content.gestureRecognizers?.contains(where: { $0 === frameworkBack }) == true)
        }
    }

    func testRootAndSiblingDialogUseTheSameActualContentAncestor() async throws {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        let content = UIView(frame: window.bounds)
        let controllerRoot = UIView(frame: content.bounds)
        let rootScene = UIView(frame: controllerRoot.bounds)
        let dialogScene = UIView(frame: content.bounds)
        window.addSubview(content)
        content.addSubview(controllerRoot)
        controllerRoot.addSubview(rootScene)
        content.addSubview(dialogScene)
        defer { content.removeFromSuperview() }

        let attached = try XCTUnwrap(NativeBackGesture.contentContainer(for: controllerRoot))
        XCTAssertTrue(attached === content)
        XCTAssertTrue(rootScene.isDescendant(of: attached))
        XCTAssertTrue(dialogScene.isDescendant(of: attached))
        XCTAssertFalse(attached is UIWindow)
        XCTAssertTrue(NativeBackGesture.contentContainer(for: rootScene) === attached)
        XCTAssertTrue(NativeBackGesture.contentContainer(for: dialogScene) === attached)
        let gesture = NativeBackGesture(view: attached, rightEdge: false, enabled: { true },
                                        began: {}, changed: { _ in }, cancelled: {}, committed: {})
        let recognizer = try XCTUnwrap(attached.gestureRecognizers?.compactMap { $0 as? UIScreenEdgePanGestureRecognizer }.first)
        XCTAssertEqual(recognizer.edges, .left)
        XCTAssertTrue(recognizer.view === attached)
        XCTAssertFalse(window.gestureRecognizers?.contains(where: { $0 === recognizer }) == true)
        gesture.cancel()
        XCTAssertFalse(attached.gestureRecognizers?.contains(where: { $0 === recognizer }) == true)
    }

    func testARegistrationBeforeWindowAttachmentCanResolveTheSameRootAfterItJoins() async {
        let root = UIView(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        XCTAssertNil(NativeBackGesture.contentContainer(for: root))
        let window = UIWindow(frame: root.bounds)
        window.addSubview(root)
        XCTAssertTrue(NativeBackGesture.contentContainer(for: root) === root)
        root.removeFromSuperview()
        XCTAssertNil(NativeBackGesture.contentContainer(for: root))
        XCTAssertNil(NativeBackGesture.contentContainer(for: window))
    }
}

/// The pinned framework's prevention policy, checked beside Pully's registered recognizer.
@MainActor
private final class DialogBackPriorityRecognizer: UIScreenEdgePanGestureRecognizer {
    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { true }
}
#endif
