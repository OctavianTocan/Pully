import Foundation

public enum NativeGithubLink {
    /// External navigation accepts ordinary GitHub web links, never credentials or app callbacks.
    public static func url(_ value: String) -> URL? {
        guard let parts = URLComponents(string: value),
              parts.scheme?.lowercased() == "https",
              parts.host?.lowercased() == "github.com",
              parts.user == nil, parts.password == nil,
              parts.port == nil || parts.port == 443 else { return nil }
        return parts.url
    }
}

public enum NativeBrowserLink {
    /// Browser presentation never accepts app schemes, local files or URL credentials.
    public static func url(_ value: String) -> URL? {
        guard !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              let parts = URLComponents(string: value),
              let scheme = parts.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil else { return nil }
        return parts.url
    }
}

#if os(iOS)
import SafariServices
import UIKit

@MainActor
public enum NativeHomeSystemActions {
    public static func copyText(_ value: String) { UIPasteboard.general.string = value }

    /// Direct system navigation bypasses Pully's incoming/deep-link router.
    @discardableResult
    public static func openOnGithub(_ value: String) -> Bool {
        guard let url = NativeGithubLink.url(value) else { return false }
        UIApplication.shared.open(url)
        return true
    }

    /// Initial navigation stays inside Safari's browser controller instead of universal-link routing.
    @discardableResult
    public static func openInBrowser(_ value: String, presenting controller: UIViewController) -> Bool {
        guard let url = NativeBrowserLink.url(value), controller.viewIfLoaded?.window != nil,
              !controller.isBeingPresented, !controller.isBeingDismissed,
              controller.presentedViewController == nil else { return false }
        controller.present(SFSafariViewController(url: url), animated: true)
        return true
    }
}
#endif
