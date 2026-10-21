#if os(iOS)
import Foundation
import XCTest
@testable import PullyNative

/// Run on the iOS simulator/device: macOS command-line Keychain behavior is a different platform adapter.
final class KeychainSecretStoreTests: XCTestCase {
    func testAppPrivateCredentialRoundTripReplacementAndReset() throws {
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
