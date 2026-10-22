import Foundation

public enum NativePersonalSessionError: Error, LocalizedError {
    case invalidBlob

    public var errorDescription: String? { "GitHub sign-in is temporarily unavailable. Try again." }
}

/// The shared vault owns the record format. This adapter replaces one separate, app-private item.
@MainActor
public final class NativePersonalSessionBlobStore {
    public static let maximumBytes = 64 * 1024
    private static let service = "com.octaviantocan.pully.personal.sessions.v1"
    private static let item = "vault-v1"
    private let secrets: NativeSecretStore

    public init(secrets: NativeSecretStore? = nil) {
        self.secrets = secrets ?? KeychainSecretStore(service: Self.service)
    }

    public func read() throws -> Data? {
        guard let bytes = try secrets.read(Self.item) else { return nil }
        guard !bytes.isEmpty, bytes.count <= Self.maximumBytes else { throw NativePersonalSessionError.invalidBlob }
        return bytes
    }

    public func write(_ contents: Data) throws {
        guard !contents.isEmpty, contents.count <= Self.maximumBytes else { throw NativePersonalSessionError.invalidBlob }
        // SecItemUpdate atomically replaces this item; a failed write does not delete its previous value.
        try secrets.write(contents, for: Self.item)
    }
}
