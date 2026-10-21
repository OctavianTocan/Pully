#if os(iOS)
import Foundation
import GoogleSignIn
import UIKit

/// System Google authentication only; shared Repo exchanges this short-lived ID token for a Pully session.
@MainActor
public final class NativeGoogleSignIn {
    public static let shared = NativeGoogleSignIn()
    private let sdk = GIDSignIn.sharedInstance
    private var pending: Attempt?
    private init() { sdk.signOut() }

    public func isAvailable(serverClientID: String, bundle: Bundle = .main) -> Bool {
        NativeGoogleSetup.from(bundle: bundle)?.acceptsServerClientID(serverClientID) == true
    }

    public func idToken(
        serverClientID: String,
        presenting: UIViewController,
        expectedGeneration: Int64,
        currentGeneration: @escaping @MainActor () -> Int64,
        bundle: Bundle = .main
    ) async throws -> String? {
        guard let setup = NativeGoogleSetup.from(bundle: bundle), setup.acceptsServerClientID(serverClientID) else {
            throw NativeAccountError.googleNotConfigured
        }
        guard pending == nil else { throw NativeAccountError.googleAlreadyPresenting }
        guard currentGeneration() == expectedGeneration, presenting.viewIfLoaded?.window != nil else {
            throw NativeAccountError.staleIdentity
        }
        try Task.checkCancellation()
        let id = UUID()
        sdk.configuration = GIDConfiguration(clientID: setup.clientID, serverClientID: serverClientID)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String?, Error>) in
                pending = Attempt(id: id, continuation: continuation, expectedGeneration: expectedGeneration, currentGeneration: currentGeneration)
                sdk.signIn(withPresenting: presenting) { [weak self] result, error in
                    // The pinned SDK documents this completion on the main queue.
                    MainActor.assumeIsolated { self?.finish(id: id, result: result, error: error) }
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in self?.cancel(id: id) }
        }
    }

    /// Called by the native scene only; the SDK validates the flow's state and redirect contents.
    @discardableResult
    public func handle(_ url: URL, bundle: Bundle = .main) -> Bool {
        guard let setup = NativeGoogleSetup.from(bundle: bundle), url.scheme == setup.callbackScheme else { return false }
        return sdk.handle(url)
    }

    private func cancel(id: UUID) {
        guard pending?.id == id else { return }
        pending?.continuation?.resume(throwing: CancellationError())
        pending?.continuation = nil
        // The system authentication sheet owns its dismissal. Its eventual answer is discarded.
        sdk.signOut()
    }

    private func finish(id: UUID, result: GIDSignInResult?, error: Error?) {
        guard let attempt = pending, attempt.id == id else { return }
        pending = nil
        // Keep no independent Google session/refresh-token lifecycle beside the Pully session.
        defer { sdk.signOut() }
        guard let continuation = attempt.continuation else { return }
        guard attempt.currentGeneration() == attempt.expectedGeneration else {
            continuation.resume(throwing: NativeAccountError.staleIdentity)
            return
        }
        if let error = error as NSError? {
            if error.domain == kGIDSignInErrorDomain, error.code == GIDSignInError.canceled.rawValue {
                continuation.resume(returning: nil)
            } else {
                // Provider errors may contain URLs or credentials; expose only a fixed app-owned message.
                continuation.resume(throwing: NativeAccountError.googleFailed)
            }
            return
        }
        guard let token = result?.user.idToken?.tokenString, !token.isEmpty, token.utf8.count <= 16 * 1024 else {
            continuation.resume(throwing: NativeAccountError.googleFailed)
            return
        }
        continuation.resume(returning: token)
    }

    private struct Attempt {
        let id: UUID
        var continuation: CheckedContinuation<String?, Error>?
        let expectedGeneration: Int64
        let currentGeneration: @MainActor () -> Int64
    }
}
#endif
