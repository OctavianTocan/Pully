import Foundation
import Security
import XCTest
@testable import PullyNative

@MainActor
final class NativeAccountStoreTests: XCTestCase {
    func testRestoredSessionNeedsServerVerificationBeforeOrdinaryCalls() async throws {
        let preferences = MemoryPreferences()
        let secrets = MemorySecrets()
        let first = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        try first.signedIn(session: "fixture-session", email: "Person@OctavianTocan.com", expectedGeneration: first.generation)
        let restored = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        XCTAssertEqual(restored.sessionState, .verificationRequired)
        XCTAssertNil(restored.activeEmail)
        XCTAssertNil(restored.configuration().headers["X-Pully-Session"])
        XCTAssertEqual(restored.verificationConfiguration().headers["X-Pully-Session"], "fixture-session")
        try restored.verifiedRestoredSession(email: "person@octaviantocan.com", expectedGeneration: restored.generation)
        XCTAssertEqual(restored.activeEmail, "person@octaviantocan.com")
        XCTAssertEqual(restored.configuration().headers["X-Pully-Session"], "fixture-session")
    }

    func testServerChangeDropsSessionAndRejectsEarlierSignInAnswer() async throws {
        let preferences = MemoryPreferences()
        let secrets = MemorySecrets()
        var invalidations = 0
        let store = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: { invalidations += 1 })
        try store.signedIn(session: "fixture-old-session", email: "person@octaviantocan.com", expectedGeneration: store.generation)
        let before = store.configuration()
        try store.updateServer(
            NativeServerConfiguration(server: NativeServerAddress("https://another.example/private")),
            access: NativeAccessCredentials(clientID: "fixture-client", secret: "fixture-secret")
        )
        XCTAssertEqual(store.configuration().server, "https://another.example/private")
        XCTAssertNil(store.configuration().headers["X-Pully-Session"])
        XCTAssertEqual(store.configuration().headers["CF-Access-Client-Secret"], "fixture-secret")
        XCTAssertFalse(store.isCurrent(before.generation))
        XCTAssertThrowsError(try store.signedIn(session: "fixture-late-session", email: "person@octaviantocan.com", expectedGeneration: before.generation))
        XCTAssertEqual(invalidations, 2)
        // Returning to the first server does not silently resurrect its old session.
        try store.updateServer(preferences.initial, access: NativeAccessCredentials(clientID: "", secret: ""))
        XCTAssertNil(store.configuration().headers["X-Pully-Session"])
    }

    func testModelChoicePersistsWithoutChangingTheVerifiedPrivateSource() async throws {
        let preferences = MemoryPreferences()
        let store = try NativeAccountStore(preferences: preferences, secrets: MemorySecrets(), invalidated: {})
        try store.updateServer(preferences.value, access: NativeAccessCredentials(clientID: "fixture-id", secret: "fixture-secret"))
        try store.signedIn(session: "fixture-session", email: "person@octaviantocan.com", expectedGeneration: store.generation)
        let before = store.configuration()
        try store.updateAnalysis(engine: "codex", model: "sol", expectedGeneration: before.generation)
        XCTAssertEqual(store.generation, before.generation)
        XCTAssertEqual(store.configuration().headers, before.headers)
        XCTAssertEqual(store.activeEmail, "person@octaviantocan.com")
        XCTAssertEqual(store.configuration().engine, "codex")
        XCTAssertEqual(store.configuration().model, "sol")
        XCTAssertEqual(preferences.value.engine, "codex")
        XCTAssertEqual(preferences.value.model, "sol")
        try store.signedOut()
        XCTAssertThrowsError(try store.updateAnalysis(engine: "pi", model: "old choice", expectedGeneration: before.generation))
        XCTAssertEqual(store.configuration().engine, "codex")
        XCTAssertEqual(store.configuration().model, "sol")
    }

    func testDifferentInstallationsOnOneOriginNeverShareSession() async throws {
        let preferences = MemoryPreferences()
        let secrets = MemorySecrets()
        let store = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        try store.signedIn(session: "fixture-session", email: "person@octaviantocan.com", expectedGeneration: store.generation)
        try store.updateServer(
            NativeServerConfiguration(server: NativeServerAddress("https://pully.example/other")),
            access: NativeAccessCredentials(clientID: "", secret: "")
        )
        XCTAssertNil(store.configuration().headers["X-Pully-Session"])
        XCTAssertEqual(store.sessionState, .signedOut)
    }

    func testAccessChangeInvalidatesSourceWithoutDiscardingSameAccount() async throws {
        let preferences = MemoryPreferences()
        let store = try NativeAccountStore(preferences: preferences, secrets: MemorySecrets(), invalidated: {})
        try store.signedIn(session: "fixture-session", email: "person@octaviantocan.com", expectedGeneration: store.generation)
        let previous = store.generation
        try store.updateServer(preferences.value, access: NativeAccessCredentials(clientID: "fixture-id", secret: "fixture-secret"))
        XCTAssertFalse(store.isCurrent(previous))
        XCTAssertEqual(store.activeEmail, "person@octaviantocan.com")
        XCTAssertEqual(store.configuration().headers["X-Pully-Session"], "fixture-session")
    }

    func testRejectedVerificationClearsSavedSession() async throws {
        let preferences = MemoryPreferences()
        let secrets = MemorySecrets()
        let first = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        try first.signedIn(session: "fixture-session", email: "person@octaviantocan.com", expectedGeneration: first.generation)
        let restored = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        try restored.verifiedRestoredSession(email: "different@octaviantocan.com", expectedGeneration: restored.generation)
        XCTAssertEqual(restored.sessionState, .signedOut)
        XCTAssertTrue(secrets.values.isEmpty)
        let relaunched = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        XCTAssertEqual(relaunched.sessionState, .signedOut)
    }

    func testLateVerificationCannotActivateAnEarlierSource() async throws {
        let preferences = MemoryPreferences()
        let secrets = MemorySecrets()
        let first = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        try first.signedIn(session: "fixture-session", email: "person@octaviantocan.com", expectedGeneration: first.generation)
        let restored = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        let before = restored.generation
        try restored.updateServer(preferences.value, access: NativeAccessCredentials(clientID: "fixture-new-id", secret: "fixture-new-secret"))
        XCTAssertThrowsError(try restored.verifiedRestoredSession(email: "person@octaviantocan.com", expectedGeneration: before))
        XCTAssertEqual(restored.sessionState, .verificationRequired)
        XCTAssertNil(restored.configuration().headers["X-Pully-Session"])
    }

    func testSignOutStillInvalidatesWhenKeychainDeleteFails() async throws {
        let preferences = MemoryPreferences()
        let secrets = MemorySecrets()
        let store = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        try store.signedIn(session: "fixture-session", email: "person@octaviantocan.com", expectedGeneration: store.generation)
        let before = store.generation
        secrets.failRemoving = true
        XCTAssertThrowsError(try store.signedOut())
        XCTAssertFalse(store.isCurrent(before))
        XCTAssertNil(store.configuration().headers["X-Pully-Session"])
        XCTAssertEqual(store.sessionState, .signedOut)
        secrets.failRemoving = false
        let relaunched = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        XCTAssertEqual(relaunched.sessionState, .signedOut)
    }

    func testServerChangeFailureStillRejectsLateAnswersAndClearsMemory() async throws {
        let preferences = MemoryPreferences()
        let secrets = MemorySecrets()
        let store = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        try store.signedIn(session: "fixture-session", email: "person@octaviantocan.com", expectedGeneration: store.generation)
        let before = store.generation
        secrets.failRemoving = true
        XCTAssertThrowsError(try store.updateServer(
            NativeServerConfiguration(server: NativeServerAddress("https://another.example")),
            access: NativeAccessCredentials(clientID: "fixture-id", secret: "fixture-secret")
        ))
        XCTAssertFalse(store.isCurrent(before))
        XCTAssertNil(store.configuration().headers["X-Pully-Session"])
    }

    func testNewServerIsPublishedOnlyAfterHostInvalidationCompletes() async throws {
        let preferences = MemoryPreferences()
        let secrets = MemorySecrets()
        var inspectTransition = false
        var cleanupFinished = false
        var duringOrdinary: NativeRepoConfiguration?
        var duringVerification: NativeRepoConfiguration?
        var store: NativeAccountStore!
        store = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {
            guard inspectTransition else { return }
            // Exercise the actual synchronous bridge API, not only the MainActor convenience getters.
            let ordinary = store.configurationSource.read()
            let verification = store.verificationSource.read()
            duringOrdinary = ordinary
            duringVerification = verification
            XCTAssertFalse(store.isCurrent(ordinary.generation))
            XCTAssertFalse(store.isCurrent(verification.generation))
            XCTAssertEqual(store.configuration().server, ordinary.server)
            cleanupFinished = true
        })
        try store.updateServer(preferences.value, access: NativeAccessCredentials(clientID: "fixture-old-id", secret: "fixture-old-secret"))
        try store.signedIn(session: "fixture-old-session", email: "person@octaviantocan.com", expectedGeneration: store.generation)
        let previousGeneration = store.generation
        inspectTransition = true
        try store.updateServer(
            NativeServerConfiguration(server: NativeServerAddress("https://another.example")),
            access: NativeAccessCredentials(clientID: "fixture-new-id", secret: "fixture-new-secret")
        )
        for during in [duringOrdinary, duringVerification] {
            let snapshot = try XCTUnwrap(during)
            XCTAssertEqual(snapshot.server, "https://pully.example")
            XCTAssertEqual(snapshot.generation, previousGeneration)
            XCTAssertEqual(snapshot.headers["CF-Access-Client-Secret"], "fixture-old-secret")
            XCTAssertEqual(snapshot.headers["X-Pully-Session"], "fixture-old-session")
        }
        XCTAssertTrue(cleanupFinished)
        for source in [store.configurationSource, store.verificationSource] {
            let after = source.read()
            XCTAssertEqual(after.server, "https://another.example")
            XCTAssertEqual(after.generation, store.generation)
            XCTAssertEqual(after.headers["CF-Access-Client-Secret"], "fixture-new-secret")
            XCTAssertNil(after.headers["X-Pully-Session"])
        }
    }

    func testResetPublishesCredentialFreeRelaysAfterHostCleanup() async throws {
        let preferences = MemoryPreferences()
        let secrets = MemorySecrets()
        var resetting = false
        var duringOrdinary: NativeRepoConfiguration?
        var duringVerification: NativeRepoConfiguration?
        var store: NativeAccountStore!
        store = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {
            guard resetting else { return }
            duringOrdinary = store.configurationSource.read()
            duringVerification = store.verificationSource.read()
            XCTAssertNil(store.activeEmail)
        })
        try store.updateServer(preferences.value, access: NativeAccessCredentials(clientID: "fixture-id", secret: "fixture-secret"))
        try store.signedIn(session: "fixture-session", email: "person@octaviantocan.com", expectedGeneration: store.generation)
        resetting = true
        try store.resetCredentials()
        XCTAssertEqual(duringOrdinary?.headers["X-Pully-Session"], "fixture-session")
        XCTAssertEqual(duringVerification?.headers["X-Pully-Session"], "fixture-session")
        XCTAssertTrue(store.configurationSource.read().headers.isEmpty)
        XCTAssertTrue(store.verificationSource.read().headers.isEmpty)
        XCTAssertTrue(secrets.values.isEmpty)
        XCTAssertNil(store.activeEmail)
    }

    func testResetFailureCannotRestoreAccessOrSessionOnNextLaunch() async throws {
        let preferences = MemoryPreferences()
        let secrets = MemorySecrets()
        let store = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        try store.updateServer(preferences.value, access: NativeAccessCredentials(clientID: "fixture-id", secret: "fixture-secret"))
        try store.signedIn(session: "fixture-session", email: "person@octaviantocan.com", expectedGeneration: store.generation)
        secrets.failRemoving = true
        XCTAssertThrowsError(try store.resetCredentials())
        XCTAssertTrue(store.configuration().headers.isEmpty)
        secrets.failRemoving = false
        let relaunched = try NativeAccountStore(preferences: preferences, secrets: secrets, invalidated: {})
        XCTAssertTrue(relaunched.configuration().headers.isEmpty)
        XCTAssertEqual(relaunched.sessionState, .signedOut)
    }

    func testOnlyNonsecretConfigurationEntersDefaults() async throws {
        let name = "PullyNativeTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let preferences = DefaultsServerPreferences(defaults: defaults)
        let store = try NativeAccountStore(preferences: preferences, secrets: MemorySecrets(), invalidated: {})
        try store.updateServer(preferences.load(), access: NativeAccessCredentials(clientID: "fixture-id", secret: "fixture-secret"))
        try store.signedIn(session: "fixture-session", email: "person@octaviantocan.com", expectedGeneration: store.generation)
        let stored = defaults.persistentDomain(forName: name) ?? [:]
        XCTAssertEqual(Set(stored.keys), ["pulls-server.url", "pulls-server.engine", "pulls-server.model", "pully-account.restore-session", "pully-account.restore-access"])
        let text = String(describing: stored)
        for secret in ["fixture-id", "fixture-secret", "fixture-session", "person@octaviantocan.com"] {
            XCTAssertFalse(text.contains(secret))
        }
        XCTAssertFalse(String(reflecting: store.configuration()).contains("fixture-session"))
    }

    func testBackgroundRepoReadersNeverMixServerHeadersOrGeneration() async throws {
        let preferences = MemoryPreferences()
        let store = try NativeAccountStore(preferences: preferences, secrets: MemorySecrets(), invalidated: {})
        try store.updateServer(
            NativeServerConfiguration(server: NativeServerAddress("https://pully.example/source/0")),
            access: NativeAccessCredentials(clientID: "fixture-id", secret: "fixture-0")
        )
        let source = store.configurationSource
        let readers = (0..<4).map { _ in
            Task.detached { () -> Bool in
                for _ in 0..<10_000 {
                    let snapshot = source.read()
                    guard let index = Int(snapshot.server.split(separator: "/").last ?? ""),
                          snapshot.generation == Int64(index + 1),
                          snapshot.headers["CF-Access-Client-Secret"] == "fixture-\(index)" else { return false }
                }
                return true
            }
        }
        for index in 1..<100 {
            try store.updateServer(
                NativeServerConfiguration(server: NativeServerAddress("https://pully.example/source/\(index)")),
                access: NativeAccessCredentials(clientID: "fixture-id", secret: "fixture-\(index)")
            )
            await Task.yield()
        }
        for reader in readers {
            let consistent = await reader.value
            XCTAssertTrue(consistent)
        }
    }
}

private final class MemoryPreferences: NativeServerPreferences {
    let initial = try! NativeServerConfiguration(server: NativeServerAddress("https://pully.example"))
    lazy var value = initial
    var allowsRestoringSession = true
    var allowsRestoringAccess = true
    func load() throws -> NativeServerConfiguration { value }
    func save(_ configuration: NativeServerConfiguration) { value = configuration }
}

private final class MemorySecrets: NativeSecretStore {
    var values: [String: Data] = [:]
    var failRemoving = false
    func read(_ key: String) throws -> Data? { values[key] }
    func write(_ value: Data, for key: String) throws { values[key] = value }
    func remove(_ key: String) throws {
        if failRemoving { throw NativeAccountError.keychain(errSecInteractionNotAllowed) }
        values.removeValue(forKey: key)
    }
    func removeAll() throws {
        if failRemoving { throw NativeAccountError.keychain(errSecInteractionNotAllowed) }
        values.removeAll()
    }
}
