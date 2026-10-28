import CryptoKit
import Foundation

/// Native storage/configuration only. The shared Repo performs account-me, sign-in and sign-out RPCs.
@MainActor
public final class NativeAccountStore {
    public enum SessionState: Equatable { case signedOut, verificationRequired, verified }

    public private(set) var serverConfiguration: NativeServerConfiguration
    public private(set) var generation: Int64 = 0
    public private(set) var sessionState: SessionState = .signedOut
    public var activeEmail: String? { sessionState == .verified ? session?.email : nil }
    public nonisolated let configurationSource: NativeConfigurationSource
    public nonisolated let verificationSource: NativeConfigurationSource

    private var preferences: NativeServerPreferences
    private let secrets: NativeSecretStore
    private let invalidated: @MainActor () -> Void
    private let identityWillChange: @MainActor () -> Void
    private var access: NativeAccessCredentials?
    /// A Cloudflare Access sign-in's `CF_Authorization` token for this server; the service token wins when both exist.
    private var accessToken: String?
    private var session: SessionRecord?
    private var invalidationDepth = 0
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(
        preferences: NativeServerPreferences = DefaultsServerPreferences(),
        secrets: NativeSecretStore = KeychainSecretStore(),
        identityWillChange: @escaping @MainActor () -> Void = {},
        invalidated: @escaping @MainActor () -> Void
    ) throws {
        self.preferences = preferences
        self.secrets = secrets
        self.invalidated = invalidated
        self.identityWillChange = identityWillChange
        serverConfiguration = try preferences.load()
        let initial = NativeRepoConfiguration(
            server: serverConfiguration.server.url, headers: [:], engine: serverConfiguration.engine,
            model: serverConfiguration.model, generation: 0
        )
        configurationSource = NativeConfigurationSource(initial)
        verificationSource = NativeConfigurationSource(initial)
        if preferences.allowsRestoringAccess {
            access = try decode(NativeAccessCredentials.self, key: accessKey(serverConfiguration.server))
            accessToken = savedAccessToken(serverConfiguration.server)
        }
        if preferences.allowsRestoringSession,
           let active = try decode(ActiveAccount.self, key: activeKey(serverConfiguration.server)),
           let saved = try decode(SessionRecord.self, key: sessionKey(serverConfiguration.server, active.email)),
           saved.server == serverConfiguration.server.url, saved.email == active.email {
            guard !saved.email.isEmpty, !saved.email.containsControlCharacters, !saved.token.isEmpty,
                  saved.token.utf8.count <= 16 * 1024, !saved.token.containsControlCharacters else {
                throw NativeAccountError.corruptCredentials
            }
            session = saved
            sessionState = .verificationRequired
        }
        publishConfiguration()
    }

    /// Ordinary calls never carry an unverified session restored from Keychain.
    public func configuration() -> NativeRepoConfiguration { configurationSource.read() }

    /// Used only by the shared account controller's account-me verification call at startup.
    public func verificationConfiguration() -> NativeRepoConfiguration { verificationSource.read() }

    public func isCurrent(_ expectedGeneration: Int64) -> Bool { expectedGeneration == generation }

    public func updateServer(_ value: NativeServerConfiguration, access credentials: NativeAccessCredentials) throws {
        let changedServer = value.server != serverConfiguration.server
        let changedAccess = access != (credentials.isEmpty ? nil : credentials)
        if credentials.isEmpty { try secrets.remove(accessKey(value.server)) }
        else { try secrets.write(encoder.encode(credentials), for: accessKey(value.server)) }

        if changedServer || changedAccess { identityWillChange() }
        if changedServer {
            do { try clearSession() }
            catch { invalidate(); throw error }
        }
        preferences.save(value)
        preferences.allowsRestoringAccess = true
        serverConfiguration = value
        access = credentials.isEmpty ? nil : credentials
        if changedServer { accessToken = savedAccessToken(value.server) }
        if changedServer || changedAccess { invalidate() }
        else { publishConfiguration() }
    }

    /// Keeps the token a Cloudflare Access sign-in ended with for the configured server. A token for the same Access
    /// email keeps this source's caches; a first sign-in or another email is a new identity.
    public func signedInToAccess(token: String, expectedGeneration: Int64) throws {
        guard isCurrent(expectedGeneration) else { throw NativeAccountError.staleIdentity }
        guard NativeAccessToken.isWellFormed(token) else { throw NativeAccountError.invalidCredential }
        try secrets.write(encoder.encode(SavedAccessToken(token: token)), for: accessTokenKey(serverConfiguration.server))
        preferences.allowsRestoringAccess = true
        let previousEmail = accessToken.flatMap(NativeAccessToken.email)
        let sameIdentity = previousEmail != nil && previousEmail == NativeAccessToken.email(token)
        accessToken = token
        if sameIdentity { publishConfiguration() }
        else {
            identityWillChange()
            invalidate()
        }
    }

    /// A model change is a preference, not a new account/Access identity.
    public func updateAnalysis(engine: String, model: String, expectedGeneration: Int64) throws {
        guard isCurrent(expectedGeneration) else { throw NativeAccountError.staleIdentity }
        let next = NativeServerConfiguration(server: serverConfiguration.server, engine: engine, model: model)
        preferences.save(next)
        serverConfiguration = next
        publishConfiguration()
    }

    /// The shared Repo has already verified the Google/password answer; an old answer cannot change this source.
    public func signedIn(session token: String, email: String, expectedGeneration: Int64) throws {
        guard isCurrent(expectedGeneration) else { throw NativeAccountError.staleIdentity }
        let email = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !token.isEmpty, token.utf8.count <= 16 * 1024, !token.containsControlCharacters,
              !email.isEmpty, !email.containsControlCharacters else { throw NativeAccountError.invalidCredential }
        let saved = SessionRecord(server: serverConfiguration.server.url, email: email, token: token)
        do {
            try secrets.write(encoder.encode(saved), for: sessionKey(serverConfiguration.server, email))
            try secrets.write(encoder.encode(ActiveAccount(email: email)), for: activeKey(serverConfiguration.server))
            if let previous = session, previous.email != email {
                try secrets.remove(sessionKey(serverConfiguration.server, previous.email))
            }
        } catch {
            identityWillChange()
            preferences.allowsRestoringSession = false
            session = nil
            sessionState = .signedOut
            invalidate()
            try? secrets.remove(sessionKey(serverConfiguration.server, email))
            try? secrets.remove(activeKey(serverConfiguration.server))
            throw error
        }
        if session?.email != email { identityWillChange() }
        session = saved
        sessionState = .verified
        preferences.allowsRestoringSession = true
        invalidate()
    }

    public func verifiedRestoredSession(email: String?, expectedGeneration: Int64) throws {
        guard isCurrent(expectedGeneration) else { throw NativeAccountError.staleIdentity }
        guard sessionState == .verificationRequired else { return }
        guard let email, email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == session?.email else {
            try signedOut()
            return
        }
        sessionState = .verified
        invalidate()
    }

    /// A failed network verification leaves verificationRequired intact; an explicit account-me rejection uses this.
    public func signedOut() throws {
        identityWillChange()
        do {
            try clearSession()
            invalidate()
        } catch {
            // Never keep sending in-memory credentials merely because Keychain is temporarily unavailable.
            session = nil
            sessionState = .signedOut
            preferences.allowsRestoringSession = false
            invalidate()
            throw error
        }
    }

    /// Explicit device reset includes Access credentials and inactive records for every configured server.
    public func resetCredentials() throws {
        identityWillChange()
        session = nil
        access = nil
        accessToken = nil
        sessionState = .signedOut
        preferences.allowsRestoringSession = false
        preferences.allowsRestoringAccess = false
        invalidate()
        try secrets.removeAll()
    }

    private func clearSession() throws {
        preferences.allowsRestoringSession = false
        let previous = session
        session = nil
        sessionState = .signedOut
        if let previous { try secrets.remove(sessionKey(serverConfiguration.server, previous.email)) }
        try secrets.remove(activeKey(serverConfiguration.server))
    }

    private func snapshot(includeRestoredSession: Bool) -> NativeRepoConfiguration {
        var headers: [String: String] = [:]
        if let access {
            headers["CF-Access-Client-Id"] = access.clientID
            headers["CF-Access-Client-Secret"] = access.secret
        } else if let accessToken {
            headers["cf-access-token"] = accessToken
        }
        if let session, session.server == serverConfiguration.server.url,
           sessionState == .verified || (includeRestoredSession && sessionState == .verificationRequired) {
            headers["X-Pully-Session"] = session.token
        }
        return NativeRepoConfiguration(
            server: serverConfiguration.server.url, headers: headers, engine: serverConfiguration.engine,
            model: serverConfiguration.model, generation: generation
        )
    }

    private func invalidate() {
        generation &+= 1
        invalidationDepth += 1
        defer {
            invalidationDepth -= 1
            publishConfiguration()
        }
        // Keep the next source unavailable until the host has cleared old sessions, routes and caches.
        // Readers retain the prior complete snapshot; no relay lock is held while host cleanup runs.
        invalidated()
    }

    private func publishConfiguration() {
        guard invalidationDepth == 0 else { return }
        configurationSource.publish(snapshot(includeRestoredSession: false))
        verificationSource.publish(snapshot(includeRestoredSession: true))
    }

    private func decode<T: Decodable>(_ type: T.Type, key: String) throws -> T? {
        guard let value = try secrets.read(key) else { return nil }
        guard let decoded = try? decoder.decode(type, from: value) else { throw NativeAccountError.corruptCredentials }
        return decoded
    }

    private func serverKey(_ server: NativeServerAddress) -> String { digest(server.url) }
    private func accessKey(_ server: NativeServerAddress) -> String { "access:\(serverKey(server))" }
    private func accessTokenKey(_ server: NativeServerAddress) -> String { "access-token:\(serverKey(server))" }

    /// An unreadable or malformed saved token is dropped: the server asks for a fresh Access sign-in.
    private func savedAccessToken(_ server: NativeServerAddress) -> String? {
        guard let saved = try? decode(SavedAccessToken.self, key: accessTokenKey(server)),
              NativeAccessToken.isWellFormed(saved.token) else { return nil }
        return saved.token
    }
    private func activeKey(_ server: NativeServerAddress) -> String { "active:\(serverKey(server))" }
    private func sessionKey(_ server: NativeServerAddress, _ email: String) -> String {
        "session:\(serverKey(server)):\(digest(email))"
    }
    private func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private struct ActiveAccount: Codable { let email: String }
    private struct SavedAccessToken: Codable { let token: String }
    private struct SessionRecord: Codable { let server: String; let email: String; let token: String }
}
