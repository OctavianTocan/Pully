#if os(iOS)
import AVFAudio
import Foundation

/// PCM output only. Pully's shared Kotlin Cuelume engine supplies every sample.
/// The producer is a serial background queue; no Kotlin or allocation runs in a render callback.
public final class NativeCueAudio {
    private let queue = DispatchQueue(label: "com.octaviantocan.pully.cue-output", qos: .userInteractive)
    private let render: (Int) -> Data?
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
    private var active = false
    private var enabled = true
    private var draining = false
    private var epoch: UInt64 = 0
    private var queued = 0
    private var attached = false

    public init(render: @escaping (Int) -> Data?) { self.render = render }

    /// Host lifecycle/preferences calls are serialized before it changes the shared cue gate.
    /// Do not call these setters from the render closure, which already runs on this queue.
    public func setActive(_ value: Bool) {
        queue.sync { [self] in
            active = value
            if !value { stop() }
        }
    }

    public func setEnabled(_ value: Bool) {
        queue.sync { [self] in
            guard enabled != value else { return }
            enabled = value
            if value {
                // Re-enabling starts a new stream, never an unconsumed old mute fade.
                stop()
            } else {
                // The shared engine has already rejected new synthesis and enqueued its 15 ms fade.
                // Drain only an existing stream; initial muted preferences never start audio.
                draining = active && queued > 0
            }
        }
    }

    public func wake() { queue.async { [self] in fill() } }

    private func stop() {
        epoch &+= 1
        queued = 0
        draining = false
        if attached {
            player.stop() // Unschedules buffers, including completions from the previous epoch.
            engine.pause()
        }
    }

    private func start() throws {
        if !attached {
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: format)
            attached = true
        }
        // Ambient follows Ring/Silent and mixes with other audio; no ducking or playback category.
        if engine.isRunning { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.ambient, mode: .default)
        try session.setActive(true)
        if !engine.isRunning { try engine.start() }
    }

    private func fill() {
        guard active && (enabled || draining) else { return }
        do { try start() } catch { stop(); return } // Unavailable audio must never fail an action.
        let stamp = epoch
        while queued < 3 {
            guard let bytes = render(480), let buffer = Self.makeBuffer(bytes, frames: 480) else { break }
            queued += 1
            player.scheduleBuffer(buffer, at: nil, options: [], completionCallbackType: .dataPlayedBack) { [weak self] _ in
                guard let self else { return }
                self.queue.async {
                    guard self.epoch == stamp else { return }
                    self.queued -= 1
                    if self.queued == 0 {
                        self.player.pause()
                        self.engine.pause()
                    }
                    self.fill()
                }
            }
        }
        if queued > 0 && !player.isPlaying { player.play() }
        if queued == 0 { draining = false; engine.pause() }
    }

    /// A checked bulk copy from shared interleaved Float32 into Apple's planar PCM buffer.
    static func makeBuffer(_ bytes: Data, frames: Int) -> AVAudioPCMBuffer? {
        guard (1...960).contains(frames), bytes.count == frames * 2 * MemoryLayout<Float>.size,
              let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)),
              let channels = buffer.floatChannelData else { return nil }
        buffer.frameLength = AVAudioFrameCount(frames)
        bytes.withUnsafeBytes { source in
            // Data does not promise Float alignment.
            for frame in 0..<frames {
                channels[0][frame] = source.loadUnaligned(fromByteOffset: frame * 8, as: Float.self)
                channels[1][frame] = source.loadUnaligned(fromByteOffset: frame * 8 + 4, as: Float.self)
            }
        }
        return buffer
    }
}
#endif
