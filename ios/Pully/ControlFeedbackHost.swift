import AVFAudio
import PullyNative
import PullyShared
import UIKit

private final class WeakCueOutput: NSObject, IosCueOutput {
    weak var host: ControlFeedbackHost?
    func wake() { host?.wake() }
    func haptic(cue: String, emphasis: String) { host?.haptic(cue: cue, emphasis: emphasis) }
    func holdHaptic() { host?.holdHaptic() }
    func gestureHaptic(role: String) { host?.gestureHaptic(role: role) }
}

/// Thin platform output. The shared engine reads the existing settings and owns all cue synthesis.
@MainActor
final class ControlFeedbackHost: NSObject {
    private var shared: IosCueEngine!
    private let output = WeakCueOutput()
    private var audio: NativeCueAudio!
    private let haptics = NativeCueHaptics()
    private var observers: [NSObjectProtocol] = []
    private var sceneActive = false
    private var interrupted = false
    private var hapticsEnabled = true

    override init() {
        super.init()
        output.host = self
        guard let url = Bundle.main.url(forResource: "recipes", withExtension: "json", subdirectory: "cuelume"),
              let recipes = try? String(contentsOf: url, encoding: .utf8) else {
            preconditionFailure("The canonical Cuelume recipes must be bundled before showing controls.")
        }
        do { shared = try IosCueEngineKt.installIosCueEngine(recipes: recipes, output: output) }
        catch { preconditionFailure("The bundled Cuelume recipes could not be loaded.") }
        let sharedEngine = shared!
        audio = NativeCueAudio { frames in sharedEngine.render(frames: Int32(frames)) as Data? }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UserDefaults.didChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.refresh()
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let self,
                  let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            self.interrupted = type == .began
            self.applyActivity()
        })
    }

    deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        audio.setActive(false)
        shared.setActive(value: false)
    }

    func setActive(_ active: Bool) { sceneActive = active; applyActivity() }

    private func applyActivity() {
        let active = sceneActive && !interrupted
        shared.setActive(value: active)
        audio.setActive(active)
        refresh()
    }

    private func refresh() {
        let defaults = UserDefaults.standard
        let key = "pulls-ui.sounds"
        let sounds = defaults.object(forKey: key) == nil || defaults.bool(forKey: key)
        // Flush the previous output stream before shared synthesis can be enabled again.
        // Muting first rejects synthesis and enqueues its DSP fade, then drains active output.
        if sounds {
            audio.setEnabled(true)
            shared.refresh()
        } else {
            shared.refresh()
            audio.setEnabled(false)
        }
        let hapticsKey = "pulls-ui.haptics"
        hapticsEnabled = defaults.object(forKey: hapticsKey) == nil || defaults.bool(forKey: hapticsKey)
    }

    // A synthesized voice wakes this producer from Kotlin's worker thread.
    nonisolated func wake() { DispatchQueue.main.async { [weak self] in self?.audio.wake() } }

    nonisolated func haptic(cue: String, emphasis: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.sceneActive && !self.interrupted && self.hapticsEnabled else { return }
            self.haptics.play(cue: cue, emphasis: emphasis)
        }
    }

    nonisolated func holdHaptic() {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.sceneActive && !self.interrupted && self.hapticsEnabled else { return }
            self.haptics.hold()
        }
    }

    nonisolated func gestureHaptic(role: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.sceneActive && !self.interrupted && self.hapticsEnabled else { return }
            self.haptics.gesture(role: role)
        }
    }
}
