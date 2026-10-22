import Foundation
import PullyShareSupport
import PullyShared

/// Optional provisioned data handoff. Sessions and Access credentials remain in AccountStorageHost's private Keychain.
@MainActor
final class ShareHandoffHost: NSObject, NativeSharedLinkHandoff {
    private let coordinator: ShareHandoffCoordinator?

    override init() {
        if let storage = ShareGroupSetup.storage(),
           let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
            coordinator = ShareHandoffCoordinator(privateDirectory: directory, storage: storage)
        } else { coordinator = nil }
        super.init()
    }

    nonisolated func take(namespace: String, server: String) -> String? {
        MainActor.assumeIsolated { coordinator?.take(namespace: namespace, server: server)?.payload }
    }

    nonisolated func revoke() { MainActor.assumeIsolated { coordinator?.revoke() } }
}
