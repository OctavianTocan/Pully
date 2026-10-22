import Foundation
import PullyNative
import PullyShared
import Security
import UIKit
import XCTest
@testable import Pully

/// Real Cocoa exception propagation and Keychain behavior, hosted by the signed Pully app.
@MainActor
final class PersonalSessionStorageHostTests: XCTestCase {
    func testARealKeychainReadCrossesTheKotlinBridgeAndCorruptDataCannotBecomeAnEmptyVault() throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.octaviantocan.pully.preview")
        let secrets = KeychainSecretStore(service: "PullyPersonalBridgeTests.\(UUID().uuidString)")
        defer { try? secrets.removeAll() }
        let host = PersonalSessionStorageHost(blobs: NativePersonalSessionBlobStore(secrets: secrets))
        let store = IosPersonalSessionStore(storage: host, configuredOrigin: "")
        XCTAssertFalse(store.configured)
        try store.verifyStorage()
        try secrets.write(Data("not-a-vault".utf8), for: "vault-v1")
        // Use a new application lifetime to prove durable validation, not its in-memory cache.
        let reopened = IosPersonalSessionStore(storage: host, configuredOrigin: "")
        XCTAssertThrowsError(try reopened.verifyStorage())
        XCTAssertEqual(try secrets.read("vault-v1"), Data("not-a-vault".utf8))
    }

    func testAnNSErrorFromTheActualSwiftStoragePortFailsClosedAndRemainsRetryable() throws {
        let secrets = PersonalFailingSecrets()
        let host = PersonalSessionStorageHost(blobs: NativePersonalSessionBlobStore(secrets: secrets))
        let store = IosPersonalSessionStore(storage: host, configuredOrigin: "https://accounts.example")
        XCTAssertTrue(store.configured)
        XCTAssertThrowsError(try store.verifyStorage()) { error in
            XCTAssertFalse(String(describing: error).contains("fixture-private-detail"))
        }
        secrets.locked = false
        try store.verifyStorage()
        secrets.locked = true
        // A previous successful Kotlin vault cache must not conceal a newly unavailable Keychain.
        XCTAssertThrowsError(try store.verifyStorage())
        XCTAssertEqual(secrets.writes, 0)
    }

    func testReentrantPersonalPauseCannotResumeHomeUntilANewActiveSceneEvent() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let pauses: [NativeSceneLifecycle.Phase] = [.background, .foregroundInactive]
        for pause in pauses {
            let host = HomeActionsHost(scene: scene)
            host.update(.background)
            let home = PersonalHomeLifecycleProbe()
            let lifecycle = host.observeLifecycle(observer: home)
            let foreground = host.observePersonalForeground(observer: PersonalActivityProbe { active in
                if active { host.update(pause) }
            })
            defer { foreground.cancel(); lifecycle.cancel(); host.disconnect() }

            host.update(.foregroundActive)
            XCTAssertEqual(home.events, pause == .background ? ["stopped"] : ["stopped", "started"])
            foreground.cancel()
            host.update(.foregroundActive)
            XCTAssertEqual(home.events.last, "resumed")
        }
    }
}

@MainActor
private final class PersonalActivityProbe: NSObject, NativePersonalForegroundObserver {
    private let action: (Bool) -> Void
    init(_ action: @escaping (Bool) -> Void) { self.action = action; super.init() }
    nonisolated func changed(active: Bool) { MainActor.assumeIsolated { action(active) } }
}

@MainActor
private final class PersonalHomeLifecycleProbe: NSObject, HomeLifecycleObserver {
    private(set) var events: [String] = []
    nonisolated func started() { MainActor.assumeIsolated { events.append("started") } }
    nonisolated func resumed() { MainActor.assumeIsolated { events.append("resumed") } }
    nonisolated func stopped() { MainActor.assumeIsolated { events.append("stopped") } }
}

private final class PersonalFailingSecrets: NativeSecretStore {
    var locked = true
    var writes = 0
    func read(_ key: String) throws -> Data? {
        if locked { throw NSError(domain: "fixture-private-detail", code: Int(errSecInteractionNotAllowed)) }
        return nil
    }
    func write(_ value: Data, for key: String) { writes += 1 }
    func remove(_ key: String) { XCTFail("A storage failure must not delete personal or legacy data") }
    func removeAll() { XCTFail("A storage failure must not reset the Keychain") }
}
