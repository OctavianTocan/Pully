import Foundation
import XCTest
@testable import PullyNative

@MainActor
final class NativePersonalSessionBlobStoreTests: XCTestCase {
    func testMissingIsDifferentFromUnreadableAndFailedReplacementRetainsThePreviousBlob() throws {
        let secrets = PersonalMemorySecrets()
        let store = NativePersonalSessionBlobStore(secrets: secrets)
        XCTAssertNil(try store.read())
        let original = Data("accepted-and-pending-fixture".utf8)
        try store.write(original)
        secrets.rejectWrites = true
        XCTAssertThrowsError(try store.write(Data("replacement-fixture".utf8)))
        XCTAssertEqual(try store.read(), original)
        secrets.rejectReads = true
        XCTAssertThrowsError(try store.read())
        secrets.rejectReads = false
        XCTAssertEqual(try store.read(), original)
        XCTAssertEqual(secrets.removals, 0)
    }

    func testBoundsNeverOverwriteOrEraseAnExistingRecord() throws {
        let secrets = PersonalMemorySecrets()
        let store = NativePersonalSessionBlobStore(secrets: secrets)
        let maximum = Data(repeating: 1, count: NativePersonalSessionBlobStore.maximumBytes)
        try store.write(maximum)
        XCTAssertEqual(try store.read(), maximum)
        let writes = secrets.writes
        XCTAssertThrowsError(try store.write(Data()))
        XCTAssertThrowsError(try store.write(Data(repeating: 2, count: maximum.count + 1)))
        XCTAssertEqual(secrets.writes, writes)
        XCTAssertEqual(try store.read(), maximum)
        secrets.bytes = Data()
        XCTAssertThrowsError(try store.read())
        secrets.bytes = Data(repeating: 2, count: maximum.count + 1)
        XCTAssertThrowsError(try store.read())
        XCTAssertEqual(secrets.removals, 0)
    }
}

private final class PersonalMemorySecrets: NativeSecretStore {
    var bytes: Data?
    var rejectReads = false
    var rejectWrites = false
    var writes = 0
    var removals = 0
    func read(_ key: String) throws -> Data? {
        if rejectReads { throw NativePersonalSessionError.invalidBlob }
        return bytes
    }
    func write(_ value: Data, for key: String) throws {
        if rejectWrites { throw NativePersonalSessionError.invalidBlob }
        writes += 1
        bytes = value
    }
    func remove(_ key: String) { removals += 1; bytes = nil }
    func removeAll() { removals += 1; bytes = nil }
}
