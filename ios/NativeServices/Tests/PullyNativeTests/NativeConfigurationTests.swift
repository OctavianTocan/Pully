import Foundation
import XCTest
@testable import PullyNative

final class NativeConfigurationTests: XCTestCase {
    func testNormalizesEquivalentHTTPSAddressesAndKeepsServerPrefix() throws {
        let address = try NativeServerAddress(" HTTPS://PULLY.EXAMPLE:443/private/// ")
        XCTAssertEqual(address.url, "https://pully.example/private")
        XCTAssertEqual(address.origin, "https://pully.example")
        XCTAssertEqual(try NativeServerAddress("https://pully.example/"), try NativeServerAddress("https://pully.example"))
    }

    func testRejectsCredentialURLsPlaintextAndUntrustedRoutingParts() {
        for invalid in ["http://pully.example", "http://127.0.0.1", "https://user:password@pully.example", "https://pully.example?token=fixture", "https://pully.example#fragment", "file:///tmp", "https:///", "https://pully.example:0"] {
            XCTAssertThrowsError(try NativeServerAddress(invalid), invalid)
        }
        XCTAssertThrowsError(try NativeAccessCredentials(clientID: "only-one", secret: ""))
        XCTAssertThrowsError(try NativeAccessCredentials(clientID: "id", secret: "fixture\r\nHeader: injected"))
    }

    func testGoogleRequiresNativeClientAndItsRegisteredCallbackBeforeAvailability() {
        let nativeID = "123456-native.apps.googleusercontent.com"
        let callback = "com.googleusercontent.apps.123456-native"
        XCTAssertNil(NativeGoogleSetup.from(info: [:]))
        XCTAssertNil(NativeGoogleSetup.from(info: ["GIDClientID": nativeID]))
        XCTAssertNil(NativeGoogleSetup.from(info: ["GIDClientID": "$(UNCONFIGURED)", "CFBundleURLTypes": [["CFBundleURLSchemes": [callback]]]]))
        let setup = NativeGoogleSetup.from(info: ["GIDClientID": nativeID, "CFBundleURLTypes": [["CFBundleURLSchemes": [callback]]]])
        XCTAssertEqual(setup?.callbackScheme, callback)
        XCTAssertTrue(setup?.acceptsServerClientID("123456-server.apps.googleusercontent.com") == true)
        XCTAssertFalse(setup?.acceptsServerClientID(nativeID) == true)
        XCTAssertFalse(setup?.acceptsServerClientID("") == true)
    }
}
