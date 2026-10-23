import Darwin
import Foundation
import PullyFileLock

public enum ShareHandoffError: Error, LocalizedError {
    case unavailable, busy, noSource, staleSource, invalidRecord

    public var errorDescription: String? {
        ShareStrings().message(for: self)
    }
}

/// Public origin and opaque audience only. This is not an account, credential or server configuration.
public struct ShareSourceContext: Codable, Equatable {
    public let token: String
    public let serverOrigin: String

    public init(token: String, serverOrigin: String) {
        self.token = token
        self.serverOrigin = serverOrigin
    }

    fileprivate var valid: Bool {
        UUID(uuidString: token) != nil && NativeIncomingLink.serverOrigin(serverOrigin) == serverOrigin
    }
}

public struct ShareHandoffRecord: Codable, Equatable {
    public let version: Int
    public let id: String
    public let token: String
    public let createdAt: TimeInterval
    public let payload: String
}

/// The app and extension serialize every read/replace/revoke/consume through the same OS file lock.
public final class ShareHandoffStorage {
    public static let maximumPayloadBytes = 16 * 1024
    public static let lifetime: TimeInterval = 24 * 60 * 60
    private let files: ProtectedShareFiles
    private let now: () -> TimeInterval

    public init(container: URL, now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 }) {
        files = ProtectedShareFiles(directory: container.appendingPathComponent("pully-links-v1", isDirectory: true))
        self.now = now
    }

    public func context() throws -> ShareSourceContext? {
        try files.locked {
            guard let value: ShareSourceContext = try files.read("source.json") else { return nil }
            guard value.valid else { throw ShareHandoffError.invalidRecord }
            return value
        }
    }

    public func activate(_ context: ShareSourceContext) throws {
        guard context.valid else { throw ShareHandoffError.invalidRecord }
        try files.locked {
            let previous: ShareSourceContext? = try files.read("source.json")
            if previous == context { return }
            // Publish the new audience before retiring data: even a crash cannot import an older token's record.
            try files.write(context, "source.json")
            try files.remove("pending.json")
        }
    }

    public func revoke() throws {
        try files.locked {
            // A crash between these removals still leaves no eligible token for a late writer.
            try files.remove("source.json")
            try files.remove("pending.json")
        }
    }

    @discardableResult
    public func save(payload: String, for captured: ShareSourceContext) throws -> ShareHandoffRecord {
        guard captured.valid, !payload.isEmpty, payload.utf8.count <= Self.maximumPayloadBytes else { throw ShareHandoffError.invalidRecord }
        return try files.locked {
            let current: ShareSourceContext? = try files.read("source.json")
            guard current == captured else { throw ShareHandoffError.staleSource }
            let record = ShareHandoffRecord(version: 1, id: UUID().uuidString, token: captured.token, createdAt: now(), payload: payload)
            guard record.createdAt.isFinite else { throw ShareHandoffError.invalidRecord }
            try files.write(record, "pending.json")
            return record
        }
    }

    /// Removes the single record under lock before returning it. Another process can never consume it again.
    public func take(for captured: ShareSourceContext) throws -> ShareHandoffRecord? {
        try files.locked {
            let current: ShareSourceContext? = try files.read("source.json")
            guard captured.valid, current == captured else { throw ShareHandoffError.staleSource }
            let record: ShareHandoffRecord?
            do { record = try files.read("pending.json") }
            catch ShareHandoffError.invalidRecord {
                try files.remove("pending.json")
                throw ShareHandoffError.invalidRecord
            }
            guard let record else { return nil }
            try files.remove("pending.json")
            let elapsed = now() - record.createdAt
            guard record.version == 1, UUID(uuidString: record.id) != nil, record.token == captured.token,
                  record.createdAt.isFinite, elapsed.isFinite, elapsed >= 0, elapsed <= Self.lifetime,
                  !record.payload.isEmpty, record.payload.utf8.count <= Self.maximumPayloadBytes else { return nil }
            return record
        }
    }
}

/// Host-private durable namespace→token state; the extension never receives the namespace or this file.
@MainActor
public final class ShareHandoffCoordinator {
    private struct Identity: Codable { let namespace: String; let token: String }
    private let files: ProtectedShareFiles
    private let storage: ShareHandoffStorage?
    private var disabled = false

    public init(privateDirectory: URL, storage: ShareHandoffStorage?) {
        files = ProtectedShareFiles(directory: privateDirectory.appendingPathComponent("pully-share-source-v1", isDirectory: true))
        self.storage = storage
    }

    /// Called only by the verified shared owner, with its existing hashed source namespace.
    public func take(namespace: String, server: String) -> ShareHandoffRecord? {
        guard namespace.count == 64, namespace.allSatisfy({ $0.isHexDigit && !$0.isUppercase }),
              let origin = NativeIncomingLink.serverOrigin(server), let storage else { return nil }
        if disabled { revoke() }
        guard !disabled else { return nil }
        do {
            let context: ShareSourceContext
            do { context = try activate(namespace: namespace, origin: origin, storage: storage) }
            catch ShareHandoffError.invalidRecord {
                revoke()
                guard !disabled else { return nil }
                context = try activate(namespace: namespace, origin: origin, storage: storage)
            }
            return try storage.take(for: context)
        } catch {
            return nil
        }
    }

    private func activate(namespace: String, origin: String, storage: ShareHandoffStorage) throws -> ShareSourceContext {
        let context = try files.locked { () -> ShareSourceContext in
            let previous: Identity? = try files.read("identity.json")
            if let previous {
                guard previous.namespace.count == 64,
                      previous.namespace.allSatisfy({ $0.isHexDigit && !$0.isUppercase }),
                      UUID(uuidString: previous.token) != nil else { throw ShareHandoffError.invalidRecord }
            }
            let live = try storage.context()
            let token: String
            if let previous, previous.namespace == namespace,
               live?.token == previous.token, live?.serverOrigin == origin { token = previous.token }
            else { token = UUID().uuidString }
            try files.write(Identity(namespace: namespace, token: token), "identity.json")
            return ShareSourceContext(token: token, serverOrigin: origin)
        }
        try storage.activate(context)
        return context
    }

    /// Synchronous and independent of account cleanup. No old private token is reused after revocation.
    public func revoke() {
        disabled = true
        let privateRetired = (try? files.locked { try files.remove("identity.json") }) != nil
        let sharedRetired = storage.map { (try? $0.revoke()) != nil } ?? true
        disabled = !privateRetired || !sharedRetired
    }
}

/// One implementation of protected, bounded atomic files, used in separate private and App Group directories.
private final class ProtectedShareFiles {
    private let directory: URL
    private let manager = FileManager.default
    private let maximumRecordBytes = 40 * 1024

    init(directory: URL) { self.directory = directory }

    func locked<T>(_ operation: () throws -> T) throws -> T {
        try manager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: ShareFileProtection.attributes(directory: true))
        try rejectSymlink(directory)
        try manager.setAttributes(ShareFileProtection.attributes(directory: true), ofItemAtPath: directory.path)
        let lock = directory.appendingPathComponent("handoff.lock")
        let descriptor = Darwin.open(lock.path, O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, mode_t(0o600))
        guard descriptor >= 0 else { throw ShareHandoffError.unavailable }
        defer { Darwin.close(descriptor) }
        try manager.setAttributes(ShareFileProtection.attributes(directory: false), ofItemAtPath: lock.path)
        // A suspended extension can retain its lock. Never wait for another process on the host's MainActor.
        while pully_file_lock(descriptor, LOCK_EX | LOCK_NB) != 0 {
            let failure = errno
            if failure == EINTR { continue }
            if failure == EWOULDBLOCK || failure == EAGAIN { throw ShareHandoffError.busy }
            throw ShareHandoffError.unavailable
        }
        defer { _ = pully_file_lock(descriptor, LOCK_UN) }
        return try operation()
    }

    func read<T: Decodable>(_ name: String) throws -> T? {
        let url = directory.appendingPathComponent(name)
        let metadata: [FileAttributeKey: Any]
        do { metadata = try manager.attributesOfItem(atPath: url.path) }
        catch let error as CocoaError where error.code == .fileNoSuchFile || error.code == .fileReadNoSuchFile { return nil }
        try rejectSymlink(url)
        let size = (metadata[.size] as? NSNumber)?.intValue ?? maximumRecordBytes + 1
        guard size <= maximumRecordBytes else { throw ShareHandoffError.invalidRecord }
        let data = try Data(contentsOf: url)
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw ShareHandoffError.invalidRecord }
    }

    func write<T: Encodable>(_ value: T, _ name: String) throws {
        let data = try JSONEncoder().encode(value)
        guard data.count <= maximumRecordBytes else { throw ShareHandoffError.invalidRecord }
        let url = directory.appendingPathComponent(name)
        try data.write(to: url, options: ShareFileProtection.writeOptions)
        try manager.setAttributes(ShareFileProtection.attributes(directory: false), ofItemAtPath: url.path)
    }

    func remove(_ name: String) throws {
        let url = directory.appendingPathComponent(name)
        do { try manager.removeItem(at: url) }
        catch let error as CocoaError where error.code == .fileNoSuchFile || error.code == .fileReadNoSuchFile { return }
    }

    private func rejectSymlink(_ url: URL) throws {
        guard try url.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { throw ShareHandoffError.unavailable }
    }
}

/// The permissions and protection class every handoff file and directory asks for.
/// The iOS Simulator has no Data Protection and reads no class back, so tests check this request there.
enum ShareFileProtection {
    static var writeOptions: Data.WritingOptions {
        #if os(iOS)
        return [.atomic, .completeFileProtection]
        #else
        return .atomic
        #endif
    }

    static func attributes(directory: Bool) -> [FileAttributeKey: Any] {
        var value: [FileAttributeKey: Any] = [.posixPermissions: directory ? 0o700 : 0o600]
        #if os(iOS)
        value[.protectionKey] = FileProtectionType.complete
        #endif
        return value
    }
}
