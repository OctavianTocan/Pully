import Foundation

/// The native envelope only validates transport and origin. Shared Kotlin parses the actual destination.
public enum NativeIncomingLink {
    public static let maximumBytes = 16 * 1024

    /// Only public routing metadata may enter a shared container; account credentials never do.
    public static func serverOrigin(_ text: String) -> String? {
        guard acceptableText(text), let parts = URLComponents(string: text), let origin = Self.origin(parts) else { return nil }
        var value = URLComponents()
        value.scheme = "https"
        value.host = origin.host
        if origin.port != 443 { value.port = origin.port }
        return value.url?.absoluteString
    }

    public static func text(from url: URL, configuredServer: String?) -> String? {
        let raw = url.absoluteString
        guard acceptableText(raw), let parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        if parts.scheme?.lowercased() == "pully" {
            guard parts.host?.lowercased() == "open", parts.user == nil, parts.password == nil, parts.port == nil,
                  parts.path.isEmpty || parts.path == "/", parts.fragment == nil,
                  let items = parts.queryItems, items.count == 1, items[0].name == "url",
                  let value = items[0].value, acceptableText(value), let destination = URL(string: value) else { return nil }
            return webText(destination, configuredServer: configuredServer)
        }
        return webText(url, configuredServer: configuredServer)
    }

    private static func webText(_ url: URL, configuredServer: String?) -> String? {
        guard acceptableText(url.absoluteString),
              var parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let origin = Self.origin(parts),
              !hasControls(parts.path), !hasControls(parts.query ?? ""), !hasControls(parts.fragment ?? "") else { return nil }
        let github = (origin.host == "github.com" || origin.host == "www.github.com") && origin.port == 443
        let server = configuredServer.flatMap { URLComponents(string: $0) }.flatMap { Self.origin($0) }
        guard github || server == origin else { return nil }
        parts.scheme = "https"
        parts.host = github ? "github.com" : origin.host
        return parts.url?.absoluteString
    }

    private struct Origin: Equatable {
        let host: String
        let port: Int
    }

    private static func origin(_ parts: URLComponents) -> Origin? {
        guard parts.scheme?.lowercased() == "https", let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil else { return nil }
        let port = parts.port ?? 443
        guard (1...65535).contains(port) else { return nil }
        return Origin(host: host, port: port)
    }

    private static func acceptableText(_ text: String) -> Bool {
        !text.isEmpty && text.utf8.count <= maximumBytes && !hasControls(text)
    }

    private static func hasControls(_ text: String) -> Bool {
        text.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) }
    }
}

/// One scene receiver forwards into the app's shared inbox; it owns no routes or credentials.
@MainActor
public final class NativeIncomingLinks {
    private let google: (URL) -> Bool
    private let configuredServer: () -> String?
    private let open: (String) -> Void
    private let rejected: () -> Void
    private var closed = false

    public init(
        google: @escaping (URL) -> Bool,
        configuredServer: @escaping () -> String?,
        open: @escaping (String) -> Void,
        rejected: @escaping () -> Void
    ) {
        self.google = google
        self.configuredServer = configuredServer
        self.open = open
        self.rejected = rejected
    }

    /// Offer authentication callbacks to their SDK before delivering ordinary arrivals from the same scene batch.
    public func receive(_ urls: [URL], activities: [NSUserActivity] = []) {
        guard !closed else { return }
        var unhandled: [URL] = []
        let browsing = activities.filter { $0.activityType == NSUserActivityTypeBrowsingWeb }.compactMap(\.webpageURL)
        for url in urls + browsing {
            if !google(url) { unhandled.append(url) }
            if closed { return }
        }
        for url in unhandled {
            guard !closed else { return }
            if let text = NativeIncomingLink.text(from: url, configuredServer: configuredServer()) { open(text) }
            else { rejected() }
        }
    }

    public func receive(_ activity: NSUserActivity) {
        receive([], activities: [activity])
    }

    public func close() { closed = true }
}
