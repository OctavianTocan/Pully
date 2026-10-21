import Foundation

/// Public OAuth identifiers only. A Web client ID alone cannot configure an iOS Google client.
public struct NativeGoogleSetup: Equatable {
    public let clientID: String
    public let callbackScheme: String

    public static func from(bundle: Bundle = .main) -> NativeGoogleSetup? {
        from(info: bundle.infoDictionary ?? [:])
    }

    public static func from(info: [String: Any]) -> NativeGoogleSetup? {
        guard let clientID = info["GIDClientID"] as? String, validClientID(clientID) else { return nil }
        let callback = clientID.split(separator: ".").reversed().joined(separator: ".")
        let types = info["CFBundleURLTypes"] as? [[String: Any]] ?? []
        let schemes = types.flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] }
        guard schemes.contains(callback) else { return nil }
        return NativeGoogleSetup(clientID: clientID, callbackScheme: callback)
    }

    public func acceptsServerClientID(_ value: String) -> Bool {
        // Native and Web credentials are different registrations in the same Google Cloud project.
        Self.validClientID(value) && value != clientID
    }

    private static func validClientID(_ value: String) -> Bool {
        value.hasSuffix(".apps.googleusercontent.com") && value.count < 256 &&
            value.range(of: #"^[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$"#, options: .regularExpression) != nil
    }
}
