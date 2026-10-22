import PullyShareSupport
import XCTest

final class NativeShareCandidatesTests: XCTestCase {
    func testOnlyAllowedWholeUrlEnvelopesReachTheSharedParser() {
        let pull = "https://github.com/pully/mobile-fixtures/pull/76/files?check_run_id=77#discussion_r91"
        let posted = "https://server.example:8443/-/abc1234"
        let text = "Private sample prose: https://other.example/a, \(pull) and \(posted)"
        XCTAssertEqual(NativeShareCandidates.links(in: text, configuredServer: "https://server.example:8443/install"), [pull, posted])
        XCTAssertEqual(NativeShareCandidates.links(in: text, configuredServer: nil), [pull])
        XCTAssertTrue(NativeShareCandidates.links(in: "https://sample:secret@github.com/a/b/pull/76", configuredServer: nil).isEmpty)
        XCTAssertTrue(NativeShareCandidates.links(in: "http://github.com/a/b/pull/76", configuredServer: nil).isEmpty)
        XCTAssertTrue(NativeShareCandidates.links(in: String(repeating: "x", count: NativeIncomingLink.maximumBytes + 1), configuredServer: nil).isEmpty)
    }
}
