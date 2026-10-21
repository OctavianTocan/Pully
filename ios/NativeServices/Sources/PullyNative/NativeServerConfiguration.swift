import Foundation
import Security

public enum NativeAccountError: Error, LocalizedError {
    case invalidServer
    case incompleteAccessCredentials
    case invalidCredential
    case staleIdentity
    case keychain(OSStatus)
    case corruptCredentials
    case googleNotConfigured
    case googleAlreadyPresenting
    case googleFailed

    public var errorDescription: String? {
        switch self {
        case .invalidServer: return "Use an HTTPS server address without a username, query or fragment."
        case .incompleteAccessCredentials: return "Enter both Access credentials, or clear both."
        case .invalidCredential: return "The sign-in credential could not be used."
        case .staleIdentity: return "The server or account changed. Try signing in again."
        case .keychain: return "Pully could not access its saved credentials. Unlock the device and try again."
        case .corruptCredentials: return "Saved credentials could not be read. Reset this device's sign-in."
        case .googleNotConfigured: return "Google sign-in has not been configured for this iOS app."
        case .googleAlreadyPresenting: return "Finish the Google sign-in already open."
        case .googleFailed: return "Google could not confirm that sign-in. Try again."
        }
    }
}

/// The server address is configuration, never a sign-in callback or an inbound-link value.
public struct NativeServerAddress: Equatable {
    public let url: String
    public let origin: String

    public init(_ raw: String) throws {
        guard var parts = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              parts.scheme?.lowercased() == "https", let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.port == nil || (1...65535).contains(parts.port!) else {
            throw NativeAccountError.invalidServer
        }
        parts.scheme = "https"
        parts.host = host.lowercased()
        if parts.port == 443 { parts.port = nil }
        // Preserve a configured path prefix: two server installations on one host do not share sessions.
        while parts.percentEncodedPath.hasSuffix("/") { parts.percentEncodedPath.removeLast() }
        guard let normalized = parts.url, normalized.host != nil else { throw NativeAccountError.invalidServer }
        url = normalized.absoluteString
        parts.percentEncodedPath = ""
        guard let normalizedOrigin = parts.url else { throw NativeAccountError.invalidServer }
        origin = normalizedOrigin.absoluteString
    }
}

public struct NativeServerConfiguration: Equatable {
    public var server: NativeServerAddress
    public var engine: String
    public var model: String

    public init(server: NativeServerAddress, engine: String = "claude-code", model: String = "") {
        self.server = server
        let engine = engine.trimmingCharacters(in: .whitespacesAndNewlines)
        self.engine = engine.isEmpty ? "claude-code" : engine
        self.model = model.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

public struct NativeAccessCredentials: Equatable, Codable, CustomStringConvertible, CustomDebugStringConvertible {
    let clientID: String
    let secret: String

    public init(clientID: String, secret: String) throws {
        let clientID = clientID.trimmingCharacters(in: .whitespacesAndNewlines)
        let secret = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clientID.isEmpty == secret.isEmpty else { throw NativeAccountError.incompleteAccessCredentials }
        guard !clientID.containsControlCharacters, !secret.containsControlCharacters else {
            throw NativeAccountError.invalidCredential
        }
        self.clientID = clientID
        self.secret = secret
    }

    var isEmpty: Bool { clientID.isEmpty }
    public var description: String { "NativeAccessCredentials(redacted)" }
    public var debugDescription: String { description }

    private enum CodingKeys: String, CodingKey { case clientID, secret }
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(clientID: values.decode(String.self, forKey: .clientID), secret: values.decode(String.self, forKey: .secret))
    }
}

/// A native-friendly snapshot for the shared Repo configuration callback. Never print its headers.
public struct NativeRepoConfiguration: Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let server: String
    public let headers: [String: String]
    public let engine: String
    public let model: String
    public let generation: Int64
    public var description: String { "NativeRepoConfiguration(generation: \(generation), credentials: redacted)" }
    public var debugDescription: String { description }
}

/// The shared Repo's synchronous callback can run on any dispatcher. Publish complete snapshots atomically.
public final class NativeConfigurationSource: @unchecked Sendable {
    private let lock = NSLock()
    private var value: NativeRepoConfiguration
    init(_ value: NativeRepoConfiguration) { self.value = value }

    public func read() -> NativeRepoConfiguration {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func publish(_ value: NativeRepoConfiguration) {
        lock.lock()
        defer { lock.unlock() }
        self.value = value
    }
}

public protocol NativeServerPreferences {
    func load() throws -> NativeServerConfiguration
    func save(_ configuration: NativeServerConfiguration)
    var allowsRestoringSession: Bool { get set }
    var allowsRestoringAccess: Bool { get set }
}

/// Contains only server/model choices and a local reset marker; all credential bytes live in Keychain.
public final class DefaultsServerPreferences: NativeServerPreferences {
    private let defaults: UserDefaults
    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public func load() throws -> NativeServerConfiguration {
        try NativeServerConfiguration(
            server: NativeServerAddress(defaults.string(forKey: "pulls-server.url") ?? "https://prs.octaviantocan.com"),
            engine: defaults.string(forKey: "pulls-server.engine") ?? "claude-code",
            model: defaults.string(forKey: "pulls-server.model") ?? ""
        )
    }

    public func save(_ configuration: NativeServerConfiguration) {
        defaults.set(configuration.server.url, forKey: "pulls-server.url")
        defaults.set(configuration.engine, forKey: "pulls-server.engine")
        defaults.set(configuration.model, forKey: "pulls-server.model")
    }

    public var allowsRestoringSession: Bool {
        get { defaults.object(forKey: "pully-account.restore-session") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "pully-account.restore-session") }
    }

    public var allowsRestoringAccess: Bool {
        get { defaults.object(forKey: "pully-account.restore-access") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "pully-account.restore-access") }
    }
}

extension String {
    var containsControlCharacters: Bool { rangeOfCharacter(from: .controlCharacters) != nil }
}
