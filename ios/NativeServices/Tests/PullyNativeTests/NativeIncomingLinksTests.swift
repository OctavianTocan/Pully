import Foundation
import XCTest
@testable import PullyNative

final class NativeIncomingLinkTests: XCTestCase {
    private func envelope(_ target: String) -> URL {
        var value = URLComponents()
        value.scheme = "pully"
        value.host = "open"
        value.queryItems = [URLQueryItem(name: "url", value: target)]
        return value.url!
    }

    func testCustomEnvelopeRetainsTheExactQueryFragmentAndEncodedPath() {
        let raw = "https://github.com/Octo/Repo/pull/76/files/abcdef1..abcdef2?check_run_id=77&w=1#diff-0123L42"
        XCTAssertEqual(NativeIncomingLink.text(from: envelope(raw), configuredServer: nil), raw)
        let compare = "https://github.com/Octo/Repo/compare/main...feature%2Fshared#diff-0123R18"
        XCTAssertEqual(NativeIncomingLink.text(from: envelope(compare), configuredServer: nil), compare)
    }

    func testOnlyGithubOrTheConfiguredHttpsOriginPassesTheNativeBoundary() {
        XCTAssertEqual(NativeIncomingLink.text(from: URL(string: "HTTPS://WWW.GITHUB.COM/Octo/Repo/pull/76#discussion_r91")!, configuredServer: nil),
                       "https://github.com/Octo/Repo/pull/76#discussion_r91")
        let posted = URL(string: "https://server.example:8443/-/abc123?view=diff#detail")!
        XCTAssertEqual(NativeIncomingLink.text(from: posted, configuredServer: "https://server.example:8443"), posted.absoluteString)
        XCTAssertNil(NativeIncomingLink.text(from: posted, configuredServer: "https://server.example"))
        XCTAssertNil(NativeIncomingLink.text(from: posted, configuredServer: nil))
        XCTAssertNil(NativeIncomingLink.text(from: URL(string: "https://github.com.evil.example/a/b/pull/76")!, configuredServer: nil))
    }

    func testCredentialsControlsOversizedAndMalformedEnvelopesAreRejected() {
        let values = [
            "http://github.com/a/b/pull/76", "https://person:sample@github.com/a/b/pull/76",
            "https://github.com/a/b/pull/76%0A", "https://github.com/a/b/pull/76?note=%00",
            "pully://open?url=https://github.com/a/b/pull/76&session=sample",
            "pully://person:sample@open?url=https://github.com/a/b/pull/76",
            "pully://settings?server=https://server.example", "pully://open?url=",
            "pully://open?url=pully://open", "pully://open?url=file:///tmp/fixture",
            "pully://open?url=https://github.com/a/b/pull/76#extra",
            "https://github.com/" + String(repeating: "a", count: NativeIncomingLink.maximumBytes),
        ]
        for raw in values {
            XCTAssertNil(NativeIncomingLink.text(from: URL(string: raw)!, configuredServer: nil), raw)
        }
        var duplicate = URLComponents(url: envelope("https://github.com/a/b/pull/76"), resolvingAgainstBaseURL: false)!
        duplicate.queryItems?.append(URLQueryItem(name: "url", value: "https://github.com/a/b/pull/77"))
        XCTAssertNil(NativeIncomingLink.text(from: duplicate.url!, configuredServer: nil))
    }
}

@MainActor
final class NativeIncomingLinksTests: XCTestCase {
    func testGoogleCallbacksAreOfferedBeforeTheSameBatchesLinksAreDelivered() async {
        var callbackHandled = false
        var events: [String] = []
        let receiver = NativeIncomingLinks(google: { url in
            if url.host == "accounts.google.example" { callbackHandled = true; events.append("google"); return true }
            return false
        }, configuredServer: { "https://server.example" }, open: { text in
            XCTAssertTrue(callbackHandled)
            events.append(text)
        }, rejected: { events.append("rejected") })
        let link = URL(string: "https://github.com/a/b/pull/76#issuecomment-92")!
        let callback = NSUserActivity(activityType: NSUserActivityTypeBrowsingWeb)
        callback.webpageURL = URL(string: "https://accounts.google.example/callback")!
        receiver.receive([link], activities: [callback])
        XCTAssertEqual(events, ["google", link.absoluteString])
        receiver.close()
        receiver.receive([link])
        XCTAssertEqual(events, ["google", link.absoluteString])
    }

    func testBrowsingActivitiesUseTheSameReceiverAndOtherActivitiesDoNotInventLinks() async {
        var links: [String] = []
        var failures = 0
        let receiver = NativeIncomingLinks(google: { _ in false }, configuredServer: { nil }, open: { links.append($0) }, rejected: { failures += 1 })
        let activity = NSUserActivity(activityType: NSUserActivityTypeBrowsingWeb)
        activity.webpageURL = URL(string: "https://github.com/a/b/pull/76/checks?check_run_id=77")
        receiver.receive(activity)
        let unrelated = NSUserActivity(activityType: "com.example.unrelated")
        unrelated.webpageURL = activity.webpageURL
        receiver.receive(unrelated)
        receiver.receive([URL(string: "pully://unknown")!])
        XCTAssertEqual(links, [activity.webpageURL!.absoluteString])
        XCTAssertEqual(failures, 1)
    }

    func testReentrantSceneDisconnectionMakesTheRestOfTheDeliveryInert() async {
        var links = 0
        var receiver: NativeIncomingLinks!
        defer { receiver = nil }
        receiver = NativeIncomingLinks(google: { url in
            if url.scheme == "sample-google" { receiver.close(); return true }
            return false
        }, configuredServer: { nil }, open: { _ in links += 1 }, rejected: { XCTFail("A disconnected scene must not report a stale error.") })
        receiver.receive([URL(string: "https://github.com/a/b/pull/76")!, URL(string: "sample-google:/callback")!])
        XCTAssertEqual(links, 0)
    }
}
