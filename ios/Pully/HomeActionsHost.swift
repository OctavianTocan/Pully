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
final class HomeActionsHost: NSObject, NativeHomeActions {
    private let lifecycle: NativeSceneLifecycle
    private final class BackRegistration {
        let id = UUID()
        let rightEdge: Bool
        let observer: HomeBackObserver
        var gesture: NativeBackGesture?
        init(rightEdge: Bool, observer: HomeBackObserver) { self.rightEdge = rightEdge; self.observer = observer }
    }
    private weak var rootView: UIView?
    private var back: BackRegistration?
    private var sceneActive = false
    private var disconnected = false

    init(scene: UIWindowScene) {
        let phase: NativeSceneLifecycle.Phase
        switch scene.activationState {
        case .foregroundActive: phase = .foregroundActive
        case .foregroundInactive: phase = .foregroundInactive
        default: phase = .background
        }
        lifecycle = NativeSceneLifecycle(phase: phase)
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

    /// A Compose registration can precede UIView creation; bind it before the window is interactive.
    func attach(to view: UIView) {
        guard !disconnected, rootView !== view else { return }
        rootView = view
        if let registration = back {
            let previous = registration.gesture
            registration.gesture = nil
            previous?.cancel()
            installBack(registration)
        }
    }

    private func installBack(_ registration: BackRegistration) {
        guard back === registration, let rootView else { return }
        let observer = registration.observer
        registration.gesture = NativeBackGesture(
            view: rootView, rightEdge: registration.rightEdge,
            enabled: { [weak self] in self?.sceneActive == true && observer.enabled() },
            began: { observer.began() }, changed: { observer.changed(progress: $0) },
            cancelled: { observer.cancelled() }, committed: { observer.committed() }
        )
    }

    private func removeBack(_ id: UUID) {
        guard let registration = back, registration.id == id else { return }
        back = nil
        registration.gesture?.cancel()
    }

    func update(_ phase: NativeSceneLifecycle.Phase) {
        guard !disconnected else { return }
        sceneActive = phase == .foregroundActive
        if !sceneActive { back?.gesture?.interrupt() }
        lifecycle.update(phase)
    }
    func disconnect() {
        guard !disconnected else { return }
        disconnected = true
        sceneActive = false
        let previous = back
        back = nil
        rootView = nil
        previous?.gesture?.cancel()
        lifecycle.disconnect()
    }
}
