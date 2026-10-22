import Foundation
import XCTest
@testable import PullyShareSupport

final class ShareStringsTests: XCTestCase {
    private var repository: URL {
        var result = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { result.deleteLastPathComponent() }
        return result
    }
    private var canonical: URL { repository.appendingPathComponent("packages/app/src/i18n/locales", isDirectory: true) }
    private func catalog(_ locale: String) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: canonical.appendingPathComponent(locale + ".json"))) as? [String: Any])
    }

    func testActualCanonicalCatalogUsesPreferredRegionalLanguage() throws {
        for (preference, locale) in [("es-MX", "es"), ("fr-CA", "fr"), ("pt-BR", "pt-BR"), ("zh-Hant-TW", "zh-TW")] {
            let strings = ShareStrings(directory: canonical, preferredLanguages: [preference, "en"])
            let source = try XCTUnwrap(catalog(locale)["nativeShare"] as? [String: Any])
            XCTAssertEqual(strings.text(.save), source["save"] as? String, preference)
            XCTAssertEqual(strings.text(.reading), source["reading"] as? String, preference)
            XCTAssertEqual(strings.message(for: ShareHandoffError.noSource), source["noSource"] as? String, preference)
        }
    }

    func testUnsupportedLanguageAndMissingTranslationUseCanonicalEnglish() throws {
        let english = try catalog("en")
        let share = try XCTUnwrap(english["nativeShare"] as? [String: Any])
        XCTAssertEqual(ShareStrings(directory: canonical, preferredLanguages: ["zz-ZZ"]).text(.save), share["save"] as? String)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data(contentsOf: canonical.appendingPathComponent("en.json")).write(to: directory.appendingPathComponent("en.json"))
        var spanish = try catalog("es")
        var translated = try XCTUnwrap(spanish["nativeShare"] as? [String: Any])
        translated.removeValue(forKey: "reading")
        spanish["nativeShare"] = translated
        try JSONSerialization.data(withJSONObject: spanish).write(to: directory.appendingPathComponent("es.json"))
        let strings = ShareStrings(directory: directory, preferredLanguages: ["es-MX"])
        XCTAssertEqual(strings.text(.reading), share["reading"] as? String)
        XCTAssertEqual(strings.text(.save), translated["save"] as? String)
    }

    func testEveryCanonicalLocaleContainsTheActualPresentationKeys() throws {
        let urls = try FileManager.default.contentsOfDirectory(at: canonical, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
        XCTAssertEqual(urls.count, 12)
        for url in urls {
            let locale = url.deletingPathExtension().lastPathComponent
            let source = try catalog(locale)
            let share = try XCTUnwrap(source["nativeShare"] as? [String: String])
            let common = try XCTUnwrap(source["common"] as? [String: String])
            let strings = ShareStrings(directory: canonical, preferredLanguages: [locale])
            for key in ShareStrings.Key.allCases {
                let expected = key == .cancel ? common["cancel"] : share[String(key.rawValue.split(separator: ".").last!)]
                XCTAssertNotNil(expected, "\(locale): \(key)")
                XCTAssertEqual(strings.text(key), expected, "\(locale): \(key)")
            }
        }
    }

    func testUnknownStorageFailureNeverDisplaysItsDescription() throws {
        let strings = ShareStrings(directory: canonical, preferredLanguages: ["de-DE"])
        let source = try XCTUnwrap(catalog("de")["nativeShare"] as? [String: Any])
        let error = NSError(domain: "fixture", code: 1, userInfo: [NSLocalizedDescriptionKey: "private synthetic diagnostic"])
        XCTAssertEqual(strings.message(for: error), source["failure"] as? String)
        XCTAssertFalse(strings.message(for: error).contains("private synthetic diagnostic"))
    }
}
