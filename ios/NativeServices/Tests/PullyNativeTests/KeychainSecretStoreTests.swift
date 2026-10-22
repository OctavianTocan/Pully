#if os(iOS)
import Foundation
import Security
import XCTest
import PullyNative

/// Run on the iOS simulator/device: macOS command-line Keychain behavior is a different platform adapter.
final class KeychainSecretStoreTests: XCTestCase {
    @MainActor
    func testPersonalVaultUsesASeparateRealDeviceOnlyNonSynchronizingKeychainItem() throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.octaviantocan.pully.native-keychain-tests")
        let service = "PullyPersonalKeychainTests.\(UUID().uuidString)"
        let personal = KeychainSecretStore(service: service)
        let legacy = KeychainSecretStore(service: service + ".legacy")
        defer { try? personal.removeAll(); try? legacy.removeAll() }
        try legacy.write(Data("legacy-fixture".utf8), for: "vault-v1")
        let store = NativePersonalSessionBlobStore(secrets: personal)
        XCTAssertNil(try store.read())
        try store.write(Data("accepted-and-pending-fixture".utf8))
        try store.write(Data("replacement-and-pending-fixture".utf8))
        XCTAssertEqual(try store.read(), Data("replacement-and-pending-fixture".utf8))
        XCTAssertEqual(try legacy.read("vault-v1"), Data("legacy-fixture".utf8))
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "vault-v1",
            kSecAttrSynchronizable as String: false,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, &result), errSecSuccess)
        let attributes = try XCTUnwrap(result as? [String: Any])
        XCTAssertEqual(attributes[kSecAttrAccessible as String] as? String, kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
        XCTAssertEqual(attributes[kSecAttrSynchronizable as String] as? Bool, false)
    }

    func testAppPrivateCredentialRoundTripReplacementAndReset() throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.octaviantocan.pully.native-keychain-tests")
        let store = KeychainSecretStore(service: "PullyNativeTests.\(UUID().uuidString)")
        defer { try? store.removeAll() }
        XCTAssertNil(try store.read("fixture"))
        try store.write(Data("first-fixture".utf8), for: "fixture")
        XCTAssertEqual(try store.read("fixture"), Data("first-fixture".utf8))
        try store.write(Data("replacement-fixture".utf8), for: "fixture")
        XCTAssertEqual(try store.read("fixture"), Data("replacement-fixture".utf8))
        try store.remove("fixture")
        XCTAssertNil(try store.read("fixture"))
        try store.write(Data("another-fixture".utf8), for: "other")
        try store.removeAll()
        XCTAssertNil(try store.read("other"))
    }
}
#endif
