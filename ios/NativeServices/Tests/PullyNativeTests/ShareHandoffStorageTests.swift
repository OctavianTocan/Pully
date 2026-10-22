import Foundation
@testable import PullyShareSupport
import XCTest

final class ShareHandoffStorageTests: XCTestCase {
    private let payload = "{\"version\":1,\"kind\":\"pull\",\"owner\":\"pully\",\"repo\":\"mobile-fixtures\",\"number\":76}"

    private func temporaryDirectory() throws -> URL {
        let value = FileManager.default.temporaryDirectory.appendingPathComponent("PullyShareTests.\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: value, withIntermediateDirectories: true)
        return value
    }

    func testTwoIndependentClientsConsumeOnlyTheLatestRecordOnce() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let host = ShareHandoffStorage(container: directory, now: { 100 })
        let extensionClient = ShareHandoffStorage(container: directory, now: { 100 })
        let source = ShareSourceContext(token: UUID().uuidString, serverOrigin: "https://server.example")
        try host.activate(source)
        try extensionClient.save(payload: payload, for: source)
        let last = try extensionClient.save(payload: payload.replacingOccurrences(of: "76", with: "77"), for: source)
        XCTAssertEqual(try host.take(for: source), last)
        XCTAssertNil(try extensionClient.take(for: source))
    }

    func testRevocationAndReplacementRejectAnAlreadyLoadedExtensionAudience() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let host = ShareHandoffStorage(container: directory, now: { 100 })
        let extensionClient = ShareHandoffStorage(container: directory, now: { 100 })
        let source = ShareSourceContext(token: UUID().uuidString, serverOrigin: "https://server.example")
        try host.activate(source)
        let captured = try XCTUnwrap(extensionClient.context())
        try extensionClient.save(payload: payload, for: captured)
        try host.revoke()
        XCTAssertThrowsError(try extensionClient.save(payload: payload, for: captured))
        XCTAssertNil(try host.context())
        let replacement = ShareSourceContext(token: UUID().uuidString, serverOrigin: source.serverOrigin)
        try host.activate(replacement)
        XCTAssertNil(try host.take(for: replacement))
        XCTAssertThrowsError(try extensionClient.save(payload: payload, for: captured))
    }

    func testExpiredOrFutureRecordsAreRemovedWithoutBeingImportedAgain() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        var clock: TimeInterval = 100
        let storage = ShareHandoffStorage(container: directory, now: { clock })
        let source = ShareSourceContext(token: UUID().uuidString, serverOrigin: "https://server.example")
        try storage.activate(source)
        try storage.save(payload: payload, for: source)
        clock += ShareHandoffStorage.lifetime + 1
        XCTAssertNil(try storage.take(for: source))
        XCTAssertNil(try storage.take(for: source))
        try storage.save(payload: payload, for: source)
        clock -= 1
        XCTAssertNil(try storage.take(for: source))
    }

    func testBoundedAtomicFilesHavePrivatePermissionsAndIosCompleteProtection() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let storage = ShareHandoffStorage(container: directory)
        let source = ShareSourceContext(token: UUID().uuidString, serverOrigin: "https://server.example")
        try storage.activate(source)
        XCTAssertThrowsError(try storage.save(payload: String(repeating: "x", count: ShareHandoffStorage.maximumPayloadBytes + 1), for: source))
        try storage.save(payload: payload, for: source)
        let files = directory.appendingPathComponent("pully-links-v1", isDirectory: true)
        for name in ["handoff.lock", "source.json", "pending.json"] {
            let attributes = try FileManager.default.attributesOfItem(atPath: files.appendingPathComponent(name).path)
            XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
            #if os(iOS)
            let protection = (attributes[.protectionKey] as? FileProtectionType)?.rawValue ?? attributes[.protectionKey] as? String
            #if targetEnvironment(simulator)
            // The simulator has no Data Protection and reports no class; any class it does report must be complete.
            if let protection { XCTAssertEqual(protection, FileProtectionType.complete.rawValue) }
            #else
            XCTAssertEqual(protection, FileProtectionType.complete.rawValue)
            #endif
            #endif
        }
        #if os(iOS)
        // Where the class cannot be read back, prove every write and attribute update requests it.
        XCTAssertTrue(ShareFileProtection.writeOptions.contains(.completeFileProtection))
        XCTAssertEqual(ShareFileProtection.attributes(directory: false)[.protectionKey] as? FileProtectionType, .complete)
        XCTAssertEqual(ShareFileProtection.attributes(directory: true)[.protectionKey] as? FileProtectionType, .complete)
        #endif
        XCTAssertEqual((try FileManager.default.attributesOfItem(atPath: files.path)[.posixPermissions] as? NSNumber)?.intValue, 0o700)
        let context = try String(contentsOf: files.appendingPathComponent("source.json"), encoding: .utf8)
        XCTAssertFalse(context.contains("namespace"))
        XCTAssertFalse(context.contains("account"))
        XCTAssertFalse(context.contains("headers"))
    }

    func testMalformedOrForeignTokenRecordsAreDiscardedOnlyOnce() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let storage = ShareHandoffStorage(container: directory)
        let source = ShareSourceContext(token: UUID().uuidString, serverOrigin: "https://server.example")
        try storage.activate(source)
        try storage.save(payload: payload, for: source)
        let file = directory.appendingPathComponent("pully-links-v1/pending.json")
        var record = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        record["token"] = UUID().uuidString
        try JSONSerialization.data(withJSONObject: record).write(to: file, options: .atomic)
        XCTAssertNil(try storage.take(for: source))
        XCTAssertNil(try storage.take(for: source))
        try Data("not-json".utf8).write(to: file, options: .atomic)
        XCTAssertThrowsError(try storage.take(for: source))
        XCTAssertNil(try storage.take(for: source))
    }

    #if os(macOS)
    func testTheRealProcessLockRejectsABusyWriterPromptlyWithoutReplacingTheRecord() async throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let storage = ShareHandoffStorage(container: directory)
        let source = ShareSourceContext(token: UUID().uuidString, serverOrigin: "https://server.example")
        try storage.activate(source)
        let saved = try storage.save(payload: payload, for: source)
        let pendingFile = directory.appendingPathComponent("pully-links-v1/pending.json")
        let retained = try Data(contentsOf: pendingFile)
        let holder = try ShareTestProcessLock(directory.appendingPathComponent("pully-links-v1/handoff.lock"))
        defer { holder.stop() }
        let began = ProcessInfo.processInfo.systemUptime
        XCTAssertThrowsError(try storage.save(payload: "busy-replacement", for: source)) { error in
            guard case .busy? = error as? ShareHandoffError else { XCTFail("Lock contention must be a retryable busy error, got \(error)"); return }
        }
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - began, 1)
        XCTAssertEqual(try Data(contentsOf: pendingFile), retained)
        try holder.release()
        XCTAssertEqual(try storage.context(), source)
        XCTAssertEqual(try storage.take(for: source), saved)
        XCTAssertNil(try storage.take(for: source))
    }
    #endif
}

@MainActor
final class ShareHandoffCoordinatorTests: XCTestCase {
    #if os(macOS)
    func testHostTakeReturnsPromptlyAndKeepsItsAuthorityAndRecordWhileAnotherProcessOwnsEitherLock() async throws {
        for lockPath in ["group/pully-links-v1/handoff.lock", "private/pully-share-source-v1/handoff.lock"] {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("PullyShareBusyTake.\(UUID().uuidString)", isDirectory: true)
            defer { try? FileManager.default.removeItem(at: root) }
            let storage = ShareHandoffStorage(container: root.appendingPathComponent("group"))
            let privateDirectory = root.appendingPathComponent("private")
            let namespace = String(repeating: "a", count: 64)
            let first = ShareHandoffCoordinator(privateDirectory: privateDirectory, storage: storage)
            XCTAssertNil(first.take(namespace: namespace, server: "https://server.example"))
            let captured = try XCTUnwrap(storage.context())
            let saved = try storage.save(payload: "still-pending", for: captured)
            let paths = ["group/pully-links-v1/source.json", "private/pully-share-source-v1/identity.json", "group/pully-links-v1/pending.json"]
            let retained = try paths.map { try Data(contentsOf: root.appendingPathComponent($0)) }
            let holder = try ShareTestProcessLock(root.appendingPathComponent(lockPath))
            defer { holder.stop() }
            let restored = ShareHandoffCoordinator(privateDirectory: privateDirectory, storage: storage)

            let began = ProcessInfo.processInfo.systemUptime
            XCTAssertNil(restored.take(namespace: namespace, server: "https://server.example"))
            XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - began, 1)
            for (path, data) in zip(paths, retained) { XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent(path)), data) }
            try holder.release()
            XCTAssertEqual(restored.take(namespace: namespace, server: "https://server.example"), saved)
            XCTAssertEqual(try storage.context(), captured)
            XCTAssertNil(restored.take(namespace: namespace, server: "https://server.example"))
        }
    }

    func testBusyHostRevocationReturnsPromptlyAndNeverImportsUntilBothDurableSidesAreRetired() async throws {
        for lockPath in ["group/pully-links-v1/handoff.lock", "private/pully-share-source-v1/handoff.lock"] {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("PullyShareBusyRevoke.\(UUID().uuidString)", isDirectory: true)
            defer { try? FileManager.default.removeItem(at: root) }
            let storage = ShareHandoffStorage(container: root.appendingPathComponent("group"))
            let namespace = String(repeating: "a", count: 64)
            let owner = ShareHandoffCoordinator(privateDirectory: root.appendingPathComponent("private"), storage: storage)
            XCTAssertNil(owner.take(namespace: namespace, server: "https://server.example"))
            let captured = try XCTUnwrap(storage.context())
            try storage.save(payload: "old-source", for: captured)
            let holder = try ShareTestProcessLock(root.appendingPathComponent(lockPath))
            defer { holder.stop() }

            var began = ProcessInfo.processInfo.systemUptime
            owner.revoke()
            XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - began, 1)
            began = ProcessInfo.processInfo.systemUptime
            XCTAssertNil(owner.take(namespace: namespace, server: "https://server.example"))
            XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - began, 1)
            try holder.release()
            XCTAssertNil(owner.take(namespace: namespace, server: "https://server.example"))
            let replacement = try XCTUnwrap(storage.context())
            XCTAssertNotEqual(replacement.token, captured.token)
            XCTAssertNil(try storage.take(for: replacement))
            XCTAssertThrowsError(try storage.save(payload: "late-old-source", for: captured))
            let saved = try storage.save(payload: "new-source", for: replacement)
            XCTAssertEqual(owner.take(namespace: namespace, server: "https://server.example"), saved)
        }
    }
    #endif

    func testMalformedOversizedAndInvalidAuthorityRetiresOldDataBeforeRecovering() async throws {
        let malformed = Data("not-json".utf8)
        let oversized = Data(repeating: 0x61, count: 40 * 1024 + 1)
        let invalid = try JSONSerialization.data(withJSONObject: [
            "namespace": String(repeating: "a", count: 64), "token": "not-a-uuid", "serverOrigin": "https://server.example",
        ])
        for relativePath in ["group/pully-links-v1/source.json", "private/pully-share-source-v1/identity.json"] {
            for damagedData in [malformed, oversized, invalid] {
                let root = FileManager.default.temporaryDirectory.appendingPathComponent("PullyShareCorruptAuthority.\(UUID().uuidString)", isDirectory: true)
                defer { try? FileManager.default.removeItem(at: root) }
                let storage = ShareHandoffStorage(container: root.appendingPathComponent("group"))
                let privateDirectory = root.appendingPathComponent("private")
                let namespace = String(repeating: "a", count: 64)
                let first = ShareHandoffCoordinator(privateDirectory: privateDirectory, storage: storage)
                XCTAssertNil(first.take(namespace: namespace, server: "https://server.example"))
                let captured = try XCTUnwrap(storage.context())
                try storage.save(payload: "old-source", for: captured)
                try damagedData.write(to: root.appendingPathComponent(relativePath), options: .atomic)

                let restored = ShareHandoffCoordinator(privateDirectory: privateDirectory, storage: storage)
                XCTAssertNil(restored.take(namespace: namespace, server: "https://server.example"))
                let replacement = try XCTUnwrap(storage.context())
                XCTAssertNotEqual(replacement.token, captured.token)
                XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("group/pully-links-v1/pending.json").path))
                XCTAssertThrowsError(try storage.save(payload: "late-old-source", for: captured))
                let saved = try storage.save(payload: "new-source", for: replacement)
                XCTAssertEqual(restored.take(namespace: namespace, server: "https://server.example"), saved)
                XCTAssertEqual(try storage.context(), replacement)
            }
        }
    }

    func testActualAuthorityAndPendingIoFailuresPreserveTheSameTokenAndUnconsumedRecord() async throws {
        for relativePath in ["group/pully-links-v1/source.json", "private/pully-share-source-v1/identity.json", "group/pully-links-v1/pending.json"] {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("PullyShareTransientIo.\(UUID().uuidString)", isDirectory: true)
            defer { try? FileManager.default.removeItem(at: root) }
            let storage = ShareHandoffStorage(container: root.appendingPathComponent("group"))
            let privateDirectory = root.appendingPathComponent("private")
            let namespace = String(repeating: "a", count: 64)
            let first = ShareHandoffCoordinator(privateDirectory: privateDirectory, storage: storage)
            XCTAssertNil(first.take(namespace: namespace, server: "https://server.example"))
            let captured = try XCTUnwrap(storage.context())
            let saved = try storage.save(payload: "still-pending", for: captured)
            let target = root.appendingPathComponent(relativePath)
            let retained = try Data(contentsOf: target)
            try FileManager.default.removeItem(at: target)
            // Reading a real directory as file data fails with IO, rather than a JSON decoding error.
            try FileManager.default.createDirectory(at: target, withIntermediateDirectories: false)
            let restored = ShareHandoffCoordinator(privateDirectory: privateDirectory, storage: storage)
            XCTAssertNil(restored.take(namespace: namespace, server: "https://server.example"))
            var isDirectory: ObjCBool = false
            XCTAssertTrue(FileManager.default.fileExists(atPath: target.path, isDirectory: &isDirectory))
            XCTAssertTrue(isDirectory.boolValue)
            for preservedPath in ["group/pully-links-v1/source.json", "private/pully-share-source-v1/identity.json", "group/pully-links-v1/pending.json"] where preservedPath != relativePath {
                XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent(preservedPath).path))
            }

            try FileManager.default.removeItem(at: target)
            try retained.write(to: target, options: .atomic)
            XCTAssertEqual(restored.take(namespace: namespace, server: "https://server.example"), saved)
            XCTAssertEqual(try storage.context(), captured)
            XCTAssertNil(restored.take(namespace: namespace, server: "https://server.example"))
        }
    }

    func testCorruptAuthorityCannotRecoverUntilBothDurableSidesAreRetired() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("PullyShareBlockedRecovery.\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let group = root.appendingPathComponent("group")
        let heldGroup = root.appendingPathComponent("held-group")
        let storage = ShareHandoffStorage(container: group)
        let privateDirectory = root.appendingPathComponent("private")
        let namespace = String(repeating: "a", count: 64)
        let owner = ShareHandoffCoordinator(privateDirectory: privateDirectory, storage: storage)
        XCTAssertNil(owner.take(namespace: namespace, server: "https://server.example"))
        let captured = try XCTUnwrap(storage.context())
        try storage.save(payload: "old-source", for: captured)
        try Data("not-json".utf8).write(to: privateDirectory.appendingPathComponent("pully-share-source-v1/identity.json"), options: .atomic)
        try FileManager.default.moveItem(at: group, to: heldGroup)
        try Data("Temporarily unavailable container".utf8).write(to: group)

        XCTAssertNil(owner.take(namespace: namespace, server: "https://server.example"))
        XCTAssertNil(owner.take(namespace: namespace, server: "https://server.example"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: heldGroup.appendingPathComponent("pully-links-v1/pending.json").path))
        try FileManager.default.removeItem(at: group)
        try FileManager.default.moveItem(at: heldGroup, to: group)
        XCTAssertNil(owner.take(namespace: namespace, server: "https://server.example"))
        let replacement = try XCTUnwrap(storage.context())
        XCTAssertNotEqual(replacement.token, captured.token)
        XCTAssertNil(try storage.take(for: replacement))
        XCTAssertThrowsError(try storage.save(payload: "late-old-source", for: captured))
        let saved = try storage.save(payload: "new-source", for: replacement)
        XCTAssertEqual(owner.take(namespace: namespace, server: "https://server.example"), saved)
    }

    func testMalformedPendingDataDoesNotRotateItsValidSourceAuthority() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("PullyShareCorruptPending.\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = ShareHandoffStorage(container: root.appendingPathComponent("group"))
        let namespace = String(repeating: "a", count: 64)
        let owner = ShareHandoffCoordinator(privateDirectory: root.appendingPathComponent("private"), storage: storage)
        XCTAssertNil(owner.take(namespace: namespace, server: "https://server.example"))
        let captured = try XCTUnwrap(storage.context())
        let pending = root.appendingPathComponent("group/pully-links-v1/pending.json")
        try Data("not-json".utf8).write(to: pending, options: .atomic)
        XCTAssertNil(owner.take(namespace: namespace, server: "https://server.example"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: pending.path))
        XCTAssertEqual(try storage.context(), captured)
        let saved = try storage.save(payload: "same-source", for: captured)
        XCTAssertEqual(owner.take(namespace: namespace, server: "https://server.example"), saved)
    }

    func testARealUnconsumedHandoffAndOpaqueTokenSurviveHostRecreationButNotSourceReplacement() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("PullyShareOwnerTests.\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = ShareHandoffStorage(container: root.appendingPathComponent("group"))
        let privateDirectory = root.appendingPathComponent("private")
        let namespace = String(repeating: "a", count: 64)
        var first: ShareHandoffCoordinator? = ShareHandoffCoordinator(privateDirectory: privateDirectory, storage: storage)
        XCTAssertNil(first?.take(namespace: namespace, server: "https://server.example/install"))
        let captured = try XCTUnwrap(storage.context())
        let saved = try storage.save(payload: "{\"kind\":\"sample\"}", for: captured)
        first = nil // No in-memory token/cache survives this host replacement.
        let restored = ShareHandoffCoordinator(privateDirectory: privateDirectory, storage: storage)
        XCTAssertEqual(restored.take(namespace: namespace, server: "https://server.example/install"), saved)
        XCTAssertEqual(try storage.context(), captured)
        XCTAssertNil(restored.take(namespace: namespace, server: "https://server.example/install"))
        try storage.save(payload: "{\"kind\":\"sample\"}", for: captured)
        restored.revoke()
        XCTAssertThrowsError(try storage.save(payload: "{\"kind\":\"late\"}", for: captured))
        XCTAssertNil(restored.take(namespace: namespace, server: "https://server.example/install"))
        XCTAssertNotEqual(try storage.context()?.token, captured.token)
        let renewed = try XCTUnwrap(storage.context())
        try storage.save(payload: "{\"kind\":\"old-account\"}", for: renewed)
        XCTAssertNil(restored.take(namespace: String(repeating: "b", count: 64), server: "https://server.example/install"))
        XCTAssertThrowsError(try storage.save(payload: "{\"kind\":\"late\"}", for: renewed))
    }

    func testUnavailableContainerFailsClosedWithoutCreatingAGroupOrImporting() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("PullyShareUnavailable.\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let owner = ShareHandoffCoordinator(privateDirectory: root, storage: nil)
        XCTAssertNil(owner.take(namespace: String(repeating: "a", count: 64), server: "https://server.example"))
        owner.revoke()
        XCTAssertNil(owner.take(namespace: String(repeating: "a", count: 64), server: "https://server.example"))
    }

    func testAnActuallyInaccessibleContainerCannotImportAndRevocationDoesNotThrow() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("PullyShareBlocked.\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let blocked = root.appendingPathComponent("group")
        try Data("This is a file, so it cannot provide a group directory.".utf8).write(to: blocked)
        let storage = ShareHandoffStorage(container: blocked)
        let owner = ShareHandoffCoordinator(privateDirectory: root.appendingPathComponent("private"), storage: storage)
        XCTAssertNil(owner.take(namespace: String(repeating: "a", count: 64), server: "https://server.example"))
        owner.revoke()
        XCTAssertNil(owner.take(namespace: String(repeating: "a", count: 64), server: "https://server.example"))
        try FileManager.default.removeItem(at: blocked)
        XCTAssertNil(owner.take(namespace: String(repeating: "a", count: 64), server: "https://server.example"))
        XCTAssertNotNil(try storage.context())
    }
}

#if os(macOS)
/// Holds the production OS lock in a separate process. The deadline prevents a broken blocking lock from hanging XCTest.
private final class ShareTestProcessLock {
    private let process = Process()
    private let input = Pipe()
    private var released = false

    init(_ lock: URL) throws {
        let output = Pipe()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = ["-c", """
            import fcntl,os,select,sys
            descriptor=os.open(sys.argv[1],os.O_RDWR)
            fcntl.flock(descriptor,fcntl.LOCK_EX|fcntl.LOCK_NB)
            print('locked',flush=True)
            select.select([sys.stdin],[],[],10)
            fcntl.flock(descriptor,fcntl.LOCK_UN)
            os.close(descriptor)
            """, lock.path]
        process.standardOutput = output
        process.standardInput = input
        try process.run()
        guard String(data: output.fileHandleForReading.availableData, encoding: .utf8) == "locked\n" else {
            stop()
            throw NSError(domain: "PullyShareTests", code: 1, userInfo: [NSLocalizedDescriptionKey: "The separate process did not acquire the real handoff lock."])
        }
    }

    func release() throws {
        guard !released else { return }
        released = true
        try input.fileHandleForWriting.close() // EOF releases select without writing into a terminated child's pipe.
        process.waitUntilExit()
    }

    func stop() {
        if !released { released = true; try? input.fileHandleForWriting.close() }
        if process.isRunning { process.terminate() }
        process.waitUntilExit()
    }
}
#endif
