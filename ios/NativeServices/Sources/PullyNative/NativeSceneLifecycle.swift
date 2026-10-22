import Foundation

/// Scene events only. The shared presentation owns polling, state and server work.
@MainActor
public final class NativeSceneLifecycle {
    public enum Phase: Equatable { case background, foregroundInactive, foregroundActive }
    public enum Event: Equatable { case started, resumed, stopped }
    private enum Delivered: Equatable { case stopped, started, resumed }
    private final class Registration {
        let observer: (Event) -> Void
        var delivered: Delivered?
        init(_ observer: @escaping (Event) -> Void) { self.observer = observer }
    }
    private var phase: Phase
    private var observers: [UUID: Registration] = [:]
    private var reconciling = false
    private var disconnected = false

    public init(phase: Phase = .background) { self.phase = phase }

    /// Registration reports the current state synchronously, before returning its cancellation handle.
    public func observe(_ observer: @escaping (Event) -> Void) -> NativeLifecycleObservation {
        guard !disconnected else {
            observer(.stopped)
            return NativeLifecycleObservation {}
        }
        let id = UUID()
        observers[id] = Registration(observer)
        resolve(id)
        reconcile()
        return NativeLifecycleObservation { [weak self] in self?.observers.removeValue(forKey: id) }
    }

    public func update(_ next: Phase) {
        guard !disconnected && phase != next else { return }
        phase = next
        reconcile()
    }

    private var desired: Delivered {
        switch phase {
        case .background: return .stopped
        case .foregroundInactive: return .started
        case .foregroundActive: return .resumed
        }
    }

    private func reconcile() {
        guard !reconciling else { return }
        reconciling = true
        defer { reconciling = false }
        // A callback can change the scene phase after another registration was processed.
        while observers.values.contains(where: { $0.delivered != desired }) {
            for id in Array(observers.keys) { resolve(id) }
        }
    }

    private func resolve(_ id: UUID) {
        while let registration = observers[id] {
            let target = desired
            guard registration.delivered != target else { return }
            let event: Event?
            switch target {
            case .stopped:
                registration.delivered = .stopped
                event = .stopped
            case .started:
                event = registration.delivered == .resumed ? nil : .started
                registration.delivered = .started
            case .resumed:
                if registration.delivered == .started {
                    registration.delivered = .resumed
                    event = .resumed
                } else {
                    registration.delivered = .started
                    event = .started
                }
            }
            // Commit delivery first, then recompute the current phase after each callback.
            if let event { registration.observer(event) }
        }
    }

    public func disconnect() {
        guard !disconnected else { return }
        disconnected = true // Callbacks cannot restart this scene or register active observers.
        phase = .background
        for id in Array(observers.keys) { resolve(id) }
        observers.removeAll()
    }
}

@MainActor
public final class NativeLifecycleObservation {
    private var cancellation: (() -> Void)?
    fileprivate init(_ cancellation: @escaping () -> Void) { self.cancellation = cancellation }
    public func cancel() {
        let action = cancellation
        cancellation = nil
        action?()
    }
}
