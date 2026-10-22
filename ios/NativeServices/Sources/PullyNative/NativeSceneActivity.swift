import Foundation

/// Active foreground is distinct from the existing started/background Home lifecycle.
@MainActor
public final class NativeSceneActivity {
    private final class Registration {
        let observer: (Bool) -> Void
        var delivered: Bool?
        init(_ observer: @escaping (Bool) -> Void) { self.observer = observer }
    }
    private var active: Bool
    private var closed = false
    private var reconciling = false
    private var observers: [UUID: Registration] = [:]

    public init(active: Bool) { self.active = active }

    public func observe(_ observer: @escaping (Bool) -> Void) -> NativeSceneActivityObservation {
        guard !closed else { observer(false); return NativeSceneActivityObservation {} }
        let id = UUID()
        observers[id] = Registration(observer)
        resolve(id)
        reconcile()
        return NativeSceneActivityObservation { [weak self] in self?.observers.removeValue(forKey: id) }
    }

    public func update(active: Bool) {
        guard !closed, self.active != active else { return }
        self.active = active
        reconcile()
    }

    public func close() {
        guard !closed else { return }
        closed = true
        active = false
        for id in Array(observers.keys) { resolve(id) }
        observers.removeAll()
    }

    private func reconcile() {
        guard !reconciling else { return }
        reconciling = true
        defer { reconciling = false }
        while observers.values.contains(where: { $0.delivered != active }) {
            for id in Array(observers.keys) { resolve(id) }
        }
    }

    private func resolve(_ id: UUID) {
        while let registration = observers[id], registration.delivered != active {
            registration.delivered = active
            registration.observer(active)
        }
    }
}

@MainActor
public final class NativeSceneActivityObservation {
    private var cancellation: (() -> Void)?
    fileprivate init(_ cancellation: @escaping () -> Void) { self.cancellation = cancellation }
    public func cancel() {
        let action = cancellation
        cancellation = nil
        action?()
    }
}
