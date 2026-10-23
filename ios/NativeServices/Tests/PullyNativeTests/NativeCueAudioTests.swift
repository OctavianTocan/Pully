#if os(iOS)
import XCTest
@testable import PullyNative

final class NativeCueAudioTests: XCTestCase {
    func testInactiveAndInitiallyMutedOutputNeverStartsTheRenderer() {
        let audio = NativeCueAudio { _ in
            XCTFail("An inactive or initially muted output must not consume or start a cue.")
            return nil
        }
        audio.wake()
        audio.setActive(false) // The synchronous lifecycle boundary waits for the queued wake.
        audio.setEnabled(false)
        audio.setActive(true)
        audio.wake()
        audio.setActive(false)
    }

    func testSharedInterleavedSamplesReachTheMatchingAppleChannelsUnchanged() throws {
        let samples: [Float] = [0.25, -0.5, 0.75, -0.125]
        let bytes = samples.withUnsafeBytes { Data($0) }
        let buffer = try XCTUnwrap(NativeCueAudio.makeBuffer(bytes, frames: 2))
        XCTAssertEqual(buffer.format.sampleRate, 48_000)
        XCTAssertEqual(buffer.frameLength, 2)
        XCTAssertEqual(buffer.floatChannelData?[0][0], 0.25)
        XCTAssertEqual(buffer.floatChannelData?[1][0], -0.5)
        XCTAssertEqual(buffer.floatChannelData?[0][1], 0.75)
        XCTAssertEqual(buffer.floatChannelData?[1][1], -0.125)
    }

    func testMalformedOrUnboundedNativeBuffersAreRejected() {
        XCTAssertNil(NativeCueAudio.makeBuffer(Data(), frames: 0))
        XCTAssertNil(NativeCueAudio.makeBuffer(Data(count: 8), frames: 2))
        XCTAssertNil(NativeCueAudio.makeBuffer(Data(count: 961 * 8), frames: 961))
    }
}
#endif
