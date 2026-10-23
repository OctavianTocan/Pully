import Foundation

/// Configuration alone grants no container access. The OS must provide the actually provisioned App Group.
public enum ShareGroupSetup {
    public static func storage(bundle: Bundle = .main) -> ShareHandoffStorage? {
        guard let group = bundle.object(forInfoDictionaryKey: "PullyShareAppGroup") as? String,
              group.hasPrefix("group."), group.count <= 255,
              group.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-").contains($0) }),
              let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return nil }
        return ShareHandoffStorage(container: container)
    }
}
