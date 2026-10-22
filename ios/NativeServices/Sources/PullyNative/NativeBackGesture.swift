import Foundation

/// Native recognition geometry only. Shared PageMotion and navigation own the resulting transition.
public enum NativeBackGestureMetrics {
    public static func progress(translation: Double, width: Double, rightEdge: Bool) -> Float {
        guard translation.isFinite, width.isFinite, width > 0 else { return 0 }
        let value = translation * (rightEdge ? -1 : 1) / width
        return Float(min(1, max(0, value)))
    }

    public static func commits(progress: Float, inwardVelocity: Double) -> Bool {
        guard progress.isFinite, inwardVelocity.isFinite, inwardVelocity > -500 else { return false }
        return progress >= 0.5 || (progress >= 0.02 && inwardVelocity >= 500)
    }
}

/// The recognizer's actual callback state, kept independent of UIKit for lifecycle regressions.
@MainActor
final class NativeBackGestureSession {
    private let enabled: () -> Bool
    private let began: () -> Void
    private let changed: (Float) -> Void
    private let cancelled: () -> Void
    private let committed: () -> Void
    private var tracking = false
    private var removed = false
    private var generation: UInt64 = 0

    init(enabled: @escaping () -> Bool, began: @escaping () -> Void, changed: @escaping (Float) -> Void,
         cancelled: @escaping () -> Void, committed: @escaping () -> Void) {
        self.enabled = enabled
        self.began = began
        self.changed = changed
        self.cancelled = cancelled
        self.committed = committed
    }

    func canBegin() -> Bool { !removed && !tracking && enabled() && !removed && !tracking }

    func begin(progress: Float) {
        guard canBegin() else { return }
        generation &+= 1
        let current = generation
        tracking = true
        began()
        if validate(current) { change(progress: progress) }
    }

    func change(progress: Float) {
        let current = generation
        guard validate(current) else { return }
        changed(progress)
        _ = validate(current)
    }

    func end(progress: Float, inwardVelocity: Double) {
        let current = generation
        change(progress: progress)
        guard validate(current) else { return }
        tracking = false
        generation &+= 1
        if NativeBackGestureMetrics.commits(progress: progress, inwardVelocity: inwardVelocity) { committed() }
        else { cancelled() }
    }

    func interrupt() {
        guard tracking else { return }
        tracking = false
        generation &+= 1
        cancelled()
    }

    func remove() { removed = true; interrupt() }

    private func validate(_ current: UInt64) -> Bool {
        guard tracking, !removed, generation == current else { return false }
        let allowed = enabled()
        guard tracking, !removed, generation == current else { return false }
        if !allowed { interrupt(); return false }
        return true
    }
}

#if os(iOS)
import UIKit

/// Match Compose 1.12.2's UIKitBackGestureRecognizer priority on the same content container.
/// Dialog's framework Back handler consumes progress without dismissing, so it must not
/// prevent the recognizer which dispatches to Pully's shared sheet/edit/route owner.
@MainActor
private final class NativeBackEdgePanGestureRecognizer: UIScreenEdgePanGestureRecognizer {
    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { true }
}

/// One leading-edge recognizer. It never transforms a view or owns a navigation stack.
@MainActor
public final class NativeBackGesture: NSObject, UIGestureRecognizerDelegate {
    private weak var view: UIView?
    private let recognizer = NativeBackEdgePanGestureRecognizer(target: nil, action: nil)
    private let rightEdge: Bool
    private let session: NativeBackGestureSession
    private var removed = false

    /// Compose's root and dialog layers share the content container immediately below UIWindow.
    public static func contentContainer(for root: UIView) -> UIView? {
        guard let window = root.window, root !== window else { return nil }
        var container = root
        while let parent = container.superview, parent !== window { container = parent }
        return container.superview === window ? container : nil
    }

    public init(view: UIView, rightEdge: Bool, enabled: @escaping () -> Bool,
                began: @escaping () -> Void, changed: @escaping (Float) -> Void,
                cancelled: @escaping () -> Void, committed: @escaping () -> Void) {
        self.view = view
        self.rightEdge = rightEdge
        session = NativeBackGestureSession(enabled: enabled, began: began, changed: changed,
                                           cancelled: cancelled, committed: committed)
        super.init()
        recognizer.edges = rightEdge ? .right : .left
        recognizer.delaysTouchesBegan = true
        recognizer.delaysTouchesEnded = true
        recognizer.cancelsTouchesInView = true
        recognizer.delegate = self
        recognizer.addTarget(self, action: #selector(handlePan(_:)))
        view.addGestureRecognizer(recognizer)
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard !removed, gestureRecognizer === recognizer, let view, view.window != nil, session.canBegin() else { return false }
        guard !removed else { return false }
        let velocity = recognizer.velocity(in: view)
        return velocity.x * (rightEdge ? -1 : 1) > 0 && abs(velocity.x) > abs(velocity.y)
    }

    /// Resigning the scene cancels a drag, while keeping the registration ready for a later gesture.
    public func interrupt() {
        guard !removed else { return }
        session.interrupt()
        guard !removed else { return }
        recognizer.isEnabled = false
        recognizer.isEnabled = true
    }

    public func cancel() {
        guard !removed else { return }
        removed = true
        view?.removeGestureRecognizer(recognizer)
        recognizer.removeTarget(self, action: #selector(handlePan(_:)))
        recognizer.delegate = nil
        session.remove()
    }

    @objc private func handlePan(_ sender: UIScreenEdgePanGestureRecognizer) {
        guard !removed, let view else { return }
        let progress = NativeBackGestureMetrics.progress(
            translation: Double(sender.translation(in: view).x), width: Double(view.bounds.width), rightEdge: rightEdge
        )
        switch sender.state {
        case .began:
            session.begin(progress: progress)
        case .changed:
            session.change(progress: progress)
        case .ended:
            let velocity = Double(sender.velocity(in: view).x) * (rightEdge ? -1 : 1)
            session.end(progress: progress, inwardVelocity: velocity)
        case .cancelled, .failed:
            session.interrupt()
        default: break
        }
    }
}
#endif
