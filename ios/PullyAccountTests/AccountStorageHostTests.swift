import Foundation
import PullyNative
import PullyShareSupport
import PullyShared
import XCTest
@testable import Pully

/// Actual production bridge and Security adapter, hosted by the real Pully simulator app.
@MainActor
final class AccountStorageHostTests: XCTestCase {
    private func fixture() throws -> (AccountBridgePreferences, KeychainSecretStore) {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.octaviantocan.pully.preview")
        let preferences = try AccountBridgePreferences()
        let secrets = KeychainSecretStore(service: "PullyAppAccountTests.\(UUID().uuidString)")
        addTeardownBlock { try secrets.removeAll() }
        return (preferences, secrets)
    }

    func testReplacementConnectionAppearsOnlyAfterSynchronousPrivateStateCleanup() throws {
        let (preferences, secrets) = try fixture()
        let host = AccountStorageHost(preferences: preferences, secrets: secrets)
        defer { host.disconnect() }
        let observer = AccountBridgeObserver()
        let subscription = host.observe(observer: observer)
        defer { subscription.cancel() }
        var cleaned = false
        observer.before = {
            XCTAssertNil(host.connection(verification: false)?.headers["X-Pully-Session"])
            XCTAssertEqual(host.connection(verification: false)?.generation, 0)
            cleaned = true
        }
        observer.after = {
            XCTAssertTrue(cleaned)
            XCTAssertEqual(host.connection(verification: false)?.headers["X-Pully-Session"], "fixture-session")
            XCTAssertEqual(host.account().email, "reader@example.com")
        }
        XCTAssertNil(host.acceptSession(session: "fixture-session", email: "reader@example.com", expectedGeneration: 0))
        XCTAssertEqual(observer.invalidations, 1)
        XCTAssertEqual(observer.publications, 1)
        XCTAssertEqual(host.connection(verification: false)?.generation, 1)
    }

    func testRealKeychainRestoreCannotAuthorizeOrdinaryRequestsBeforeVerification() throws {
        let (preferences, secrets) = try fixture()
        let first = AccountStorageHost(preferences: preferences, secrets: secrets)
        XCTAssertNil(first.acceptSession(session: "fixture-session", email: "reader@example.com", expectedGeneration: 0))
        first.disconnect()

        let restored = AccountStorageHost(preferences: preferences, secrets: secrets)
        defer { restored.disconnect() }
        XCTAssertTrue(restored.account().verificationRequired)
        XCTAssertNil(restored.account().email)
        XCTAssertNil(restored.connection(verification: false)?.headers["X-Pully-Session"])
        XCTAssertEqual(restored.connection(verification: true)?.headers["X-Pully-Session"], "fixture-session")
        XCTAssertNil(restored.confirmRestoredSession(email: "reader@example.com", expectedGeneration: 0))
        XCTAssertFalse(restored.account().verificationRequired)
        XCTAssertEqual(restored.account().email, "reader@example.com")
        XCTAssertEqual(restored.connection(verification: false)?.headers["X-Pully-Session"], "fixture-session")
    }

    func testServerReplacementRejectsLateCredentialsAndResetClearsRealAccessStorage() throws {
        let (preferences, secrets) = try fixture()
        let host = AccountStorageHost(preferences: preferences, secrets: secrets)
        defer { host.disconnect() }
        XCTAssertNil(host.acceptSession(session: "old-session", email: "reader@example.com", expectedGeneration: 0))
        XCTAssertNil(host.saveServer(server: "https://replacement.example/pully", accessId: "fixture-id", accessSecret: "fixture-secret"))
        XCTAssertNil(host.account().email)
        XCTAssertNil(host.connection(verification: false)?.headers["X-Pully-Session"])
        XCTAssertEqual(host.connection(verification: false)?.headers["CF-Access-Client-Id"], "fixture-id")
        XCTAssertNotNil(host.acceptSession(session: "late-session", email: "reader@example.com", expectedGeneration: 0))
        XCTAssertNil(host.account().email)
        XCTAssertNil(host.resetCredentials())

        let restored = AccountStorageHost(preferences: preferences, secrets: secrets)
        defer { restored.disconnect() }
        XCTAssertNil(restored.connection(verification: true)?.headers["X-Pully-Session"])
        XCTAssertNil(restored.connection(verification: false)?.headers["CF-Access-Client-Id"])
        XCTAssertNil(restored.connection(verification: false)?.headers["CF-Access-Client-Secret"])
        XCTAssertFalse(restored.account().verificationRequired)
    }

    func testUnregisteredGoogleAndDisconnectedSceneCannotStartSelection() throws {
        let (preferences, secrets) = try fixture()
        let host = AccountStorageHost(preferences: preferences, secrets: secrets)
        XCTAssertFalse(host.googleAvailable(serverClientId: "fixture-unregistered-web-client"))
        host.disconnect()
        XCTAssertNil(host.connection(verification: false))
        XCTAssertNotNil(host.acceptSession(session: "late-session", email: "reader@example.com", expectedGeneration: 0))
        XCTAssertFalse(host.googleAvailable(serverClientId: "fixture-unregistered-web-client"))
    }

    func testRealKeychainHostRevokesTheCapturedShareTokenBeforePublishingAccessReplacement() throws {
        let (preferences, secrets) = try fixture()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("PullyAppShareTests.\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        // A temporary directory exercises the actual protected-file adapter; it does not claim App Group provisioning.
        let storage = ShareHandoffStorage(container: directory.appendingPathComponent("group"))
        let coordinator = ShareHandoffCoordinator(privateDirectory: directory.appendingPathComponent("private"), storage: storage)
        var events: [String] = []
        let host = AccountStorageHost(preferences: preferences, secrets: secrets, identityWillChange: {
            events.append("revoke")
            coordinator.revoke()
        })
        let observer = AccountBridgeObserver()
        let subscription = host.observe(observer: observer)
        defer { observer.before = {}; observer.after = {}; subscription.cancel(); host.disconnect() }
        XCTAssertNil(host.acceptSession(session: "fixture-session", email: "reader@example.com", expectedGeneration: 0))
        let server = try XCTUnwrap(host.connection(verification: false)?.server)
        let before = try XCTUnwrap(host.connection(verification: false)?.generation)
        XCTAssertNil(coordinator.take(namespace: String(repeating: "a", count: 64), server: server))
        let captured = try XCTUnwrap(storage.context())
        let payload = "{\"version\":1,\"target\":{\"kind\":\"githubPull\",\"owner\":\"pully\",\"repo\":\"mobile-fixtures\",\"number\":76}}"
        try storage.save(payload: payload, for: captured)
        events.removeAll()
        observer.before = {
            events.append("invalidating")
            XCTAssertEqual(events, ["revoke", "invalidating"])
            // Observer callbacks cannot throw, so a storage error fails the test here.
            do {
                let context = try storage.context()
                XCTAssertNil(context)
                XCTAssertThrowsError(try storage.save(payload: payload, for: captured))
            } catch {
                XCTFail("Reading the revoked share context failed: \(error)")
            }
            XCTAssertEqual(host.connection(verification: false)?.generation, before)
            XCTAssertNil(host.connection(verification: false)?.headers["CF-Access-Client-Id"])
        }
        observer.after = {
            events.append("published")
            XCTAssertEqual(host.connection(verification: false)?.headers["CF-Access-Client-Id"], "fixture-id")
        }
        XCTAssertNil(host.saveServer(server: server, accessId: "fixture-id", accessSecret: "fixture-secret"))
        XCTAssertEqual(events, ["revoke", "invalidating", "published"])
        XCTAssertNil(coordinator.take(namespace: String(repeating: "b", count: 64), server: server))
        let renewed = try XCTUnwrap(storage.context())
        observer.before = {
            do {
                let context = try storage.context()
                XCTAssertEqual(context, renewed)
            } catch {
                XCTFail("Reading the renewed share context failed: \(error)")
            }
        }
        observer.after = {}
        events.removeAll()
        let generation = try XCTUnwrap(host.connection(verification: false)?.generation)
        XCTAssertNil(host.acceptSession(session: "fixture-renewed", email: "reader@example.com", expectedGeneration: generation))
        XCTAssertTrue(events.isEmpty)
        observer.before = {}
        host.disconnect()
        XCTAssertEqual(try storage.context(), renewed)
        let restored = AccountStorageHost(preferences: preferences, secrets: secrets, identityWillChange: {
            XCTFail("A successful same-account restore must preserve the captured token.")
            coordinator.revoke()
        })
        defer { restored.disconnect() }
        XCTAssertTrue(restored.account().verificationRequired)
        XCTAssertNil(restored.confirmRestoredSession(email: "reader@example.com", expectedGeneration: 0))
        XCTAssertEqual(try storage.context(), renewed)
    }
}

private final class AccountBridgePreferences: NativeServerPreferences {
    private var configuration: NativeServerConfiguration
    var allowsRestoringSession = true
    var allowsRestoringAccess = true
    init() throws { configuration = try NativeServerConfiguration(server: NativeServerAddress("https://fixture.example/pully")) }
    func load() throws -> NativeServerConfiguration { configuration }
    func save(_ configuration: NativeServerConfiguration) { self.configuration = configuration }
}

@MainActor
private final class AccountBridgeObserver: NSObject, AccountStorageObserver {
    var invalidations = 0
    var publications = 0
    var before: () -> Void = {}
    var after: () -> Void = {}
    nonisolated func invalidating() { MainActor.assumeIsolated { invalidations += 1; before() } }
    nonisolated func changed() { MainActor.assumeIsolated { publications += 1; after() } }
}
