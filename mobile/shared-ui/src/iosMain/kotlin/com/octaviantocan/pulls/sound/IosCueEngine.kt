package com.octaviantocan.pulls.sound

import com.octaviantocan.pulls.settings.UserDefaultsSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSOperationQueue
import platform.Foundation.create

/** A thin native output boundary. Rendering and the complete cue vocabulary stay in Kotlin. */
interface IosCueOutput {
    fun wake()
    fun haptic(cue: String, emphasis: String)
    fun holdHaptic()
    fun gestureHaptic(role: String)
}

/** One scene's actual shared synthesizer. Only the native serial producer calls [render]. */
class IosCueEngine internal constructor(palette: Palette, private val output: IosCueOutput) : NativeControlFeedback {
    private val state = CueGuard()
    private val preferences = UserDefaultsSettings()
    private val worker = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
    // The existing phone output gain, with the same shared high-pass and soft clip.
    private val mixer = Mixer(OUTPUT_GAIN * 2.0)
    private val engine = Cuelume(palette, mixer, worker = CueWorker { action -> worker.addOperationWithBlock { action() } }, onVoice = output::wake)
    private var active = false
    private var sounds = false
    private var haptics = false

    init { engine.stop(); engine.warm() }

    /** Reads the same persisted preferences as AppSettings; no second set of native toggles. */
    fun refresh() = state.locked {
        sounds = preferences.getBoolean("sounds", true)
        haptics = preferences.getBoolean("haptics", true)
        engine.material = Material.of(preferences.getString("soundMaterial", "default"))
        val enabled = active && sounds
        if (engine.enabled != enabled) {
            if (enabled) {
                // Output may have stopped before consuming the mute fade. It must never resume it.
                engine.stop()
                engine.enabled = true
            } else {
                // An active producer keeps consuming the existing 15 ms DSP fade.
                engine.enabled = false
            }
        }
    }

    /** Suspension invalidates pending synthesis and drops already mixed feedback before resuming. */
    fun setActive(value: Boolean) = state.locked {
        if (active && !value) engine.stop()
        active = value
        refresh()
    }

    override fun play(cue: Cue, emphasis: Emphasis, direction: Direction?, input: Input?) = state.locked {
        refresh()
        if (!active || cue == Cue.Type || cue.isAir) return@locked
        if (haptics) output.haptic(cue.id, emphasis.name.lowercase())
        if (sounds) engine.play(cue, PlayOptions(emphasis = emphasis, direction = direction, input = input))
    }

    override fun hold() = state.locked {
        refresh()
        if (active && haptics) output.holdHaptic()
    }

    override fun gesture(role: NativeGestureFeedback) = state.locked {
        refresh()
        if (active && haptics) output.gestureHaptic(role.name.lowercase())
    }

    /** Interleaved 48 kHz stereo Float32, copied in bulk away from Apple's real-time audio thread. */
    @OptIn(ExperimentalForeignApi::class)
    fun render(frames: Int): NSData? {
        require(frames in 1..960)
        if (!mixer.busy) return null
        val samples = FloatArray(frames * 2)
        mixer.render(samples, frames)
        return samples.usePinned { NSData.create(bytes = it.addressOf(0), length = (samples.size * Float.SIZE_BYTES).toULong()) }
    }
}

/** Resource failure is explicit at host installation, before interactive controls can be shown. */
@Throws(IllegalArgumentException::class)
fun installIosCueEngine(recipes: String, output: IosCueOutput): IosCueEngine {
    val engine = IosCueEngine(Palette.parse(recipes), output)
    ControlFeedback.install(engine)
    return engine
}
