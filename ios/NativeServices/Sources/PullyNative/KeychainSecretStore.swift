import Foundation
import Security

public protocol NativeSecretStore {
    func read(_ key: String) throws -> Data?
    func write(_ value: Data, for key: String) throws
    func remove(_ key: String) throws
    func removeAll() throws
}

/// App-private foreground credentials; no shared access group, iCloud sync or background-unlock class.
public final class KeychainSecretStore: NativeSecretStore {
    private let service: String

    public init(service: String = "com.octaviantocan.pully.credentials.v1") { self.service = service }

    public func read(_ key: String) throws -> Data? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw NativeAccountError.keychain(status) }
        guard let data = result as? Data else { throw NativeAccountError.corruptCredentials }
        return data
    }

    public func write(_ value: Data, for key: String) throws {
        let query = baseQuery(key)
        let attributes: [String: Any] = [
            kSecValueData as String: value,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(attributes) { _, updated in updated } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw NativeAccountError.keychain(status) }
    }

    public func remove(_ key: String) throws {
        let status = SecItemDelete(baseQuery(key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw NativeAccountError.keychain(status) }
    }

    public func removeAll() throws {
        let status = SecItemDelete(baseQuery(nil) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw NativeAccountError.keychain(status) }
    }

    private func baseQuery(_ key: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: false,
        ]
        if let key { query[kSecAttrAccount as String] = key }
        #if os(macOS)
        query[kSecUseDataProtectionKeychain as String] = true
        #endif
        return query
    }
}
