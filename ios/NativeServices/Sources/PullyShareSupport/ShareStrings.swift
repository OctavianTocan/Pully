import Foundation

public struct ShareStrings {
    public enum Key: String, CaseIterable {
        case title = "nativeShare.title"
        case reading = "nativeShare.reading"
        case save = "nativeShare.save"
        case cancel = "common.cancel"
        case unsupported = "nativeShare.unsupported"
        case hint = "nativeShare.hint"
        case unavailable = "nativeShare.unavailable"
        case busy = "nativeShare.busy"
        case noSource = "nativeShare.noSource"
        case staleSource = "nativeShare.staleSource"
        case invalidRecord = "nativeShare.invalidRecord"
        case failure = "nativeShare.failure"
    }

    private let selected: [String: Any]
    private let english: [String: Any]

    public init(bundle: Bundle = .main, preferredLanguages: [String] = Locale.preferredLanguages) {
        self.init(directory: bundle.resourceURL?.appendingPathComponent("i18n", isDirectory: true), preferredLanguages: preferredLanguages)
    }

    internal init(directory: URL?, preferredLanguages: [String]) {
        let urls = directory.flatMap {
            try? FileManager.default.contentsOfDirectory(at: $0, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])
        } ?? []
        let available = Set(urls.filter { $0.pathExtension == "json" }.map { $0.deletingPathExtension().lastPathComponent })
        let candidates = ["en"] + available.filter { $0 != "en" }.sorted()
        let locale = Bundle.preferredLocalizations(from: candidates, forPreferences: preferredLanguages).first ?? "en"
        let english = Self.catalog(directory, locale: "en")
        self.english = english
        selected = locale == "en" ? english : Self.catalog(directory, locale: locale)
    }

    public func text(_ key: Key) -> String {
        Self.lookup(selected, key: key) ?? Self.lookup(english, key: key) ?? key.rawValue
    }

    public func message(for error: Error) -> String {
        let key: Key
        switch error as? ShareHandoffError {
        case .unavailable: key = .unavailable
        case .busy: key = .busy
        case .noSource: key = .noSource
        case .staleSource: key = .staleSource
        case .invalidRecord: key = .invalidRecord
        case nil: key = .failure
        }
        return text(key)
    }

    private static func catalog(_ directory: URL?, locale: String) -> [String: Any] {
        guard let directory, directory.isFileURL,
              let data = try? Data(contentsOf: directory.appendingPathComponent(locale + ".json")),
              data.count <= 1024 * 1024,
              let catalog = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return catalog
    }

    private static func lookup(_ catalog: [String: Any], key: Key) -> String? {
        var value: Any = catalog
        for component in key.rawValue.split(separator: ".") {
            guard let nested = value as? [String: Any], let next = nested[String(component)] else { return nil }
            value = next
        }
        return value as? String
    }
}
