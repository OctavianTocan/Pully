import Foundation
import PullyNative
import PullyShared

/// One vault and recovery lifetime for every scene. No legacy server supplies the personal origin.
@MainActor
enum PersonalSessionServices {
    static let shared = IosPersonalSessionStore(
        storage: PersonalSessionStorageHost(),
        configuredOrigin: Bundle.main.object(forInfoDictionaryKey: "PullyPersonalAuthOrigin") as? String ?? ""
    )
}

@MainActor
final class PersonalSessionStorageHost: NSObject, NativePersonalSessionStorage {
    private let blobs: NativePersonalSessionBlobStore

    init(blobs: NativePersonalSessionBlobStore? = nil) {
        self.blobs = blobs ?? NativePersonalSessionBlobStore()
        super.init()
    }

    nonisolated func read() throws -> NativePersonalSessionRead {
        try MainActor.assumeIsolated {
            guard var bytes = try blobs.read() else { return NativePersonalSessionRead(contents: nil) }
            defer { bytes.resetBytes(in: bytes.startIndex..<bytes.endIndex) }
            let result = KotlinByteArray(size: Int32(bytes.count))
            for (index, byte) in bytes.enumerated() { result.set(index: Int32(index), value: Int8(bitPattern: byte)) }
            return NativePersonalSessionRead(contents: result)
        }
    }

    nonisolated func write(contents: KotlinByteArray) throws {
        try MainActor.assumeIsolated {
            guard contents.size > 0, Int(contents.size) <= NativePersonalSessionBlobStore.maximumBytes else {
                throw NativePersonalSessionError.invalidBlob
            }
            var bytes = Data(count: Int(contents.size))
            defer { bytes.resetBytes(in: bytes.startIndex..<bytes.endIndex) }
            for index in 0..<contents.size { bytes[Int(index)] = UInt8(bitPattern: contents.get(index: index)) }
            try blobs.write(bytes)
        }
    }
}
