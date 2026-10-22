import Foundation

/// Foundation extracts only URL envelopes; the shared Kotlin parser alone chooses the actual destination.
public enum NativeShareCandidates {
    public static func links(in text: String, configuredServer: String?) -> [String] {
        guard text.utf8.count <= NativeIncomingLink.maximumBytes,
              let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return [] }
        var links: [String] = []
        var bytes = 0
        for match in detector.matches(in: text, range: NSRange(text.startIndex..<text.endIndex, in: text)) {
            guard links.count < 16, let url = match.url,
                  let link = NativeIncomingLink.text(from: url, configuredServer: configuredServer) else { continue }
            bytes += link.utf8.count + 1
            guard bytes <= NativeIncomingLink.maximumBytes else { break }
            links.append(link)
        }
        return links
    }
}
