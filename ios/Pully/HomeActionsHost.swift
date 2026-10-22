import PullyNative
import PullyShared
import UIKit

@MainActor
private final class HomeLifecycleHandle: NSObject, HomeLifecycleSubscription {
    private let observation: NativeLifecycleObservation
    init(_ observation: NativeLifecycleObservation) { self.observation = observation }
    nonisolated func cancel() { MainActor.assumeIsolated { observation.cancel() } }
}

@MainActor
private final class HomeBackHandle: NSObject, HomeLifecycleSubscription {
    private var cancellation: (() -> Void)?
    init(_ cancellation: @escaping () -> Void) { self.cancellation = cancellation }
    nonisolated func cancel() {
        MainActor.assumeIsolated {
            let action = cancellation
            cancellation = nil
            action?()
        }
    }
}

/// Required shared-screen services. Kotlin calls this boundary synchronously on its UI thread.
@MainActor
final class HomeActionsHost: NSObject, NativeHomeActions, NativeBackReadiness, NativePersonalForegroundActions {
    private let lifecycle: NativeSceneLifecycle
    private let personalForeground: NativeSceneActivity
    private final class BackRegistration {
        let id = UUID()
        let rightEdge: Bool
        let observer: HomeBackObserver
        var gesture: NativeBackGesture?
        weak var container: UIView?
        init(rightEdge: Bool, observer: HomeBackObserver) { self.rightEdge = rightEdge; self.observer = observer }
    }
    private weak var rootView: UIView?
    private var back: BackRegistration?
    private var sceneActive = false
    private var disconnected = false
    private var phaseEpoch: UInt64 = 0

    init(scene: UIWindowScene) {
        let phase: NativeSceneLifecycle.Phase
        switch scene.activationState {
        case .foregroundActive: phase = .foregroundActive
        case .foregroundInactive: phase = .foregroundInactive
        default: phase = .background
        }
        lifecycle = NativeSceneLifecycle(phase: phase)
        personalForeground = NativeSceneActivity(active: phase == .foregroundActive)
        sceneActive = phase == .foregroundActive
        super.init()
    }

    nonisolated func doCopyText(text: String) {
        MainActor.assumeIsolated { NativeHomeSystemActions.copyText(text) }
    }

    nonisolated func openOnGithub(url: String) {
        MainActor.assumeIsolated { _ = NativeHomeSystemActions.openOnGithub(url) }
    }

    nonisolated func openInBrowser(url: String) {
        MainActor.assumeIsolated {
            guard sceneActive, !disconnected, let controller = rootView?.window?.rootViewController else { return }
            _ = NativeHomeSystemActions.openInBrowser(url, presenting: controller)
        }
    }

    nonisolated func isSharedBackActive() -> Bool {
        MainActor.assumeIsolated { !disconnected && sharedContentIsActive }
    }

    nonisolated func observeLifecycle(observer: HomeLifecycleObserver) -> HomeLifecycleSubscription {
        MainActor.assumeIsolated {
            HomeLifecycleHandle(lifecycle.observe { event in
                switch event {
                case .started: observer.started()
                case .resumed: observer.resumed()
                case .stopped: observer.stopped()
                }
            })
        }
    }

    nonisolated func observePersonalForeground(observer: NativePersonalForegroundObserver) -> HomeLifecycleSubscription {
        MainActor.assumeIsolated {
            let observation = personalForeground.observe { observer.changed(active: $0) }
            return HomeBackHandle { observation.cancel() }
        }
    }

    nonisolated func observeBack(rightEdge: Bool, observer: HomeBackObserver) -> HomeLifecycleSubscription {
        MainActor.assumeIsolated {
            guard !disconnected else { return HomeBackHandle {} }
            let registration = BackRegistration(rightEdge: rightEdge, observer: observer)
            let previous = back
            back = registration
            previous?.gesture?.cancel()
            installBack(registration)
            let id = registration.id
            return HomeBackHandle { [weak self] in self?.removeBack(id) }
        }
    }

    /// A Compose registration can precede UIView creation; bind once the root has its scene window.
    func attach(to view: UIView) {
        guard !disconnected else { return }
        let replacedRoot = rootView !== view
        rootView = view
        if let registration = back {
            if replacedRoot {
                let previous = registration.gesture
                registration.gesture = nil
                registration.container = nil
                previous?.cancel()
            }
            installBack(registration)
        }
    }

    private func installBack(_ registration: BackRegistration) {
        guard !disconnected, back === registration, let rootView,
              let container = NativeBackGesture.contentContainer(for: rootView) else { return }
        if registration.gesture != nil, registration.container === container { return }
        let previous = registration.gesture
        registration.gesture = nil
        registration.container = nil
        previous?.cancel()
        guard !disconnected, back === registration, self.rootView === rootView,
              registration.gesture == nil,
              NativeBackGesture.contentContainer(for: rootView) === container else { return }
        let observer = registration.observer
        registration.container = container
        registration.gesture = NativeBackGesture(
            // Match Compose 1.12's own edge recognizer attachment: the root and dialog
            // scenes share this container, while UIWindow is outside its content hierarchy.
            view: container, rightEdge: registration.rightEdge,
            enabled: { [weak self] in self?.sharedContentIsActive == true && observer.enabled() },
            began: { observer.began() }, changed: { observer.changed(progress: $0) },
            cancelled: { observer.cancelled() }, committed: { observer.committed() }
        )
    }

    private var sharedContentIsActive: Bool {
        guard sceneActive, let rootView, let controller = rootView.window?.rootViewController,
              controller.viewIfLoaded === rootView else { return false }
        // A native browser or system presentation owns its gestures until it leaves.
        return controller.presentedViewController == nil
    }

    private func removeBack(_ id: UUID) {
        guard let registration = back, registration.id == id else { return }
        back = nil
        registration.gesture?.cancel()
    }

    func update(_ phase: NativeSceneLifecycle.Phase) {
        guard !disconnected else { return }
        phaseEpoch &+= 1
        let opening = phaseEpoch
        sceneActive = phase == .foregroundActive
        personalForeground.update(active: sceneActive)
        guard !disconnected, phaseEpoch == opening else { return }
        if !sceneActive { back?.gesture?.interrupt() }
        else if let registration = back { installBack(registration) }
        guard !disconnected, phaseEpoch == opening else { return }
        lifecycle.update(phase)
    }
    func disconnect() {
        guard !disconnected else { return }
        disconnected = true
        sceneActive = false
        personalForeground.close()
        let previous = back
        back = nil
        rootView = nil
        previous?.gesture?.cancel()
        lifecycle.disconnect()
    }
}
