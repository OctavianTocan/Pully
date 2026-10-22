import Foundation
import PullyNative
import PullyShared
import UIKit

/// Only immutable native snapshots cross the shared client's background dispatcher boundary.
private final class AccountConnectionRelay: @unchecked Sendable {
    private let lock = NSLock()
    private var ordinary: NativeConfigurationSource?
    private var verification: NativeConfigurationSource?

    func replace(_ store: NativeAccountStore?) {
        lock.lock()
        ordinary = store?.configurationSource
        verification = store?.verificationSource
        lock.unlock()
    }

    func read(verification: Bool) -> NativeRepoConfiguration? {
        lock.lock()
        let source = verification ? self.verification : ordinary
        lock.unlock()
        return source?.read()
    }
}

@MainActor
private final class AccountNativeSubscription: NSObject, AccountStorageSubscription {
    private var cancellation: (() -> Void)?
    init(_ cancellation: @escaping () -> Void) { self.cancellation = cancellation }
    nonisolated func cancel() {
        // Coroutine cancellation can arrive from a background dispatcher.
        Task { @MainActor in
            let action = self.cancellation
            self.cancellation = nil
            action?()
        }
    }
}

/// Secure storage and Google presentation. The Kotlin AccountRuntime owns every account RPC.
@MainActor
final class AccountStorageHost: NSObject, AccountStorage {
    private nonisolated let connections = AccountConnectionRelay()
    private var preferences: NativeServerPreferences
    private let secrets: NativeSecretStore
    private let identityWillChange: @MainActor () -> Void
    private var store: NativeAccountStore?
    private var startupProblem: String?
    private var observers: [UUID: AccountStorageObserver] = [:]
    private var selections: [UUID: Task<Void, Never>] = [:]
    private weak var presenting: UIViewController?
    private var disconnected = false
    private var mutationDepth = 0

    init(
        preferences: NativeServerPreferences = DefaultsServerPreferences(),
        secrets: NativeSecretStore = KeychainSecretStore(),
        identityWillChange: @escaping @MainActor () -> Void = {}
    ) {
        self.preferences = preferences
        self.secrets = secrets
        self.identityWillChange = identityWillChange
        super.init()
        do { try loadStorage() }
        catch { startupProblem = safeMessage(error) }
    }

    func attach(to controller: UIViewController) { presenting = controller }

    nonisolated func connection(verification: Bool) -> AccountConnection? {
        guard let value = connections.read(verification: verification) else { return nil }
        return AccountConnection(
            server: value.server, headers: value.headers, engine: value.engine,
            model: value.model, generation: value.generation
        )
    }

    nonisolated func account() -> StoredAccount {
        MainActor.assumeIsolated {
            StoredAccount(
                email: store?.activeEmail,
                verificationRequired: store?.sessionState == .verificationRequired,
                problem: startupProblem
            )
        }
    }

    nonisolated func observe(observer: AccountStorageObserver) -> AccountStorageSubscription {
        MainActor.assumeIsolated {
            guard !disconnected else { return AccountNativeSubscription {} }
            let id = UUID()
            observers[id] = observer
            return AccountNativeSubscription { [weak self] in self?.observers.removeValue(forKey: id) }
        }
    }

    nonisolated func acceptSession(session: String, email: String, expectedGeneration: Int64) -> String? {
        MainActor.assumeIsolated {
            mutate { try requireStorage().signedIn(session: session, email: email, expectedGeneration: expectedGeneration) }
        }
    }

    nonisolated func confirmRestoredSession(email: String?, expectedGeneration: Int64) -> String? {
        MainActor.assumeIsolated {
            mutate { try requireStorage().verifiedRestoredSession(email: email, expectedGeneration: expectedGeneration) }
        }
    }

    nonisolated func forgetSession() -> String? {
        MainActor.assumeIsolated { mutate { try requireStorage().signedOut() } }
    }

    nonisolated func saveServer(server: String, accessId: String, accessSecret: String) -> String? {
        MainActor.assumeIsolated {
            mutate {
                let store = try requireStorage()
                let previous = store.serverConfiguration
                let configuration = try NativeServerConfiguration(
                    server: NativeServerAddress(server), engine: previous.engine, model: previous.model
                )
                let access = try NativeAccessCredentials(clientID: accessId, secret: accessSecret)
                try store.updateServer(configuration, access: access)
            }
        }
    }

    nonisolated func saveAnalysis(engine: String, model: String, expectedGeneration: Int64) -> String? {
        MainActor.assumeIsolated {
            mutate { try requireStorage().updateAnalysis(engine: engine, model: model, expectedGeneration: expectedGeneration) }
        }
    }

    nonisolated func resetCredentials() -> String? {
        MainActor.assumeIsolated {
            mutate {
                if let store { try store.resetCredentials() }
                else {
                    identityWillChange()
                    invalidateObservers()
                    // A corrupt or locked store never silently falls back to anonymous requests.
                    // This explicit reset clears only this app's credential service.
                    preferences.allowsRestoringSession = false
                    preferences.allowsRestoringAccess = false
                    try secrets.removeAll()
                    try loadStorage()
                }
            }
        }
    }

    nonisolated func retryStorage() -> String? {
        MainActor.assumeIsolated {
            mutate {
                guard store == nil else { return }
                invalidateObservers()
                try loadStorage()
            }
        }
    }

    nonisolated func googleAvailable(serverClientId: String) -> Bool {
        MainActor.assumeIsolated {
            !disconnected && NativeGoogleSignIn.shared.isAvailable(serverClientID: serverClientId)
        }
    }

    nonisolated func googleToken(
        serverClientId: String, expectedGeneration: Int64, completed: AccountTokenObserver
    ) -> AccountStorageSubscription {
        MainActor.assumeIsolated {
            guard !disconnected, let store, store.isCurrent(expectedGeneration),
                  let presenting, presenting.viewIfLoaded?.window != nil,
                  presenting.presentedViewController == nil else {
                completed.completed(token: nil, problem: NativeAccountError.staleIdentity.errorDescription)
                return AccountNativeSubscription {}
            }
            let id = UUID()
            // Task inherits MainActor and cannot begin before this registration is complete.
            selections[id] = Task { @MainActor [weak self, weak presenting] in
                guard let self else { return }
                defer { self.selections.removeValue(forKey: id) }
                do {
                    guard let presenting else { throw NativeAccountError.staleIdentity }
                    let token = try await NativeGoogleSignIn.shared.idToken(
                        serverClientID: serverClientId, presenting: presenting,
                        expectedGeneration: expectedGeneration,
                        currentGeneration: { [weak self] in self?.store?.generation ?? Int64.min }
                    )
                    try Task.checkCancellation()
                    guard !self.disconnected, self.store?.isCurrent(expectedGeneration) == true else {
                        throw NativeAccountError.staleIdentity
                    }
                    completed.completed(token: token, problem: nil)
                } catch is CancellationError {
                    completed.completed(token: nil, problem: nil)
                } catch {
                    completed.completed(token: nil, problem: self.safeMessage(error))
                }
            }
            return AccountNativeSubscription { [weak self] in self?.selections[id]?.cancel() }
        }
    }

    func disconnect() {
        guard !disconnected else { return }
        invalidateObservers()
        disconnected = true
        connections.replace(nil)
        observers.removeAll()
        presenting = nil
    }

    private func loadStorage() throws {
        let value = try NativeAccountStore(preferences: preferences, secrets: secrets, identityWillChange: identityWillChange) { [weak self] in
            self?.invalidateObservers()
        }
        store = value
        startupProblem = nil
        connections.replace(value)
    }

    private func requireStorage() throws -> NativeAccountStore {
        guard !disconnected, let store else { throw NativeAccountError.corruptCredentials }
        return store
    }

    private func invalidateObservers() {
        for task in selections.values { task.cancel() }
        // Cancellation callbacks can remove registrations; each observer gets the same transition.
        for observer in Array(observers.values) { observer.invalidating() }
    }

    private func mutate(_ operation: () throws -> Void) -> String? {
        guard !disconnected else { return "This Pully window has closed." }
        mutationDepth += 1
        defer {
            mutationDepth -= 1
            if mutationDepth == 0 {
                // NativeAccountStore has now published after synchronous cleanup.
                for observer in Array(observers.values) { observer.changed() }
            }
        }
        do { try operation(); return nil }
        catch { return safeMessage(error) }
    }

    private func safeMessage(_ error: Error) -> String {
        (error as? NativeAccountError)?.errorDescription ?? "Pully could not update its saved credentials. Unlock the device and try again."
    }
}
