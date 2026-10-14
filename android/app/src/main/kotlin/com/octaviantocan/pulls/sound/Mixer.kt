package com.octaviantocan.pulls.sound

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.pow

/*
 * The shared output bus, Cuelume's `audio/output.ts` and the voice handling of `audio/engine.ts`.
 * Every cue that is ringing is a voice; the bus sums them, boosts them, cuts what nobody hears
 * (everything under 40 Hz) and rounds off any peak with a soft clip. Playing a cue again lets its last
 * play go (80 ms, or 400 ms for a cue with notes), and turning sound off fades whatever rings in 15 ms.
 *
 * `add` and `silence` may be called from any thread; `render` belongs to the one thread that feeds the
 * speaker.
 */

/** One rendered play and what the bus needs to know to let go of it. */
class Voice(
    val cue: Cue,
    val rendered: Rendered,
    /** The gain already in the samples; a fade targets an absolute level, so it needs this. */
    val master: Double,
    /** How long this voice takes to fade when the same cue plays again, in seconds. */
    val fadeSeconds: Double,
    /** A release rings with its press rather than replacing it. */
    val replaces: Boolean = true,
)

/** How long a cue's last play takes to fade when the same cue plays again. */
const val HANDOFF_SECONDS = 0.08

/** A cue with notes lets its last play go slower, so a walk overlaps. */
const val NOTE_HANDOFF_SECONDS = 0.4

/** How long a silenced voice takes to fade: short enough to hear as at once, long enough not to click. */
const val SILENCE_SECONDS = 0.015

/** The level a handed-off voice's master gain ramps to; Web Audio cannot ramp to zero exponentially. */
private const val FADE_FLOOR = 0.0001

class Mixer(private val outputGain: Double = OUTPUT_GAIN) {
    private sealed interface Command {
        class Add(val voice: Voice) : Command
        data object Silence : Command
    }

    private class Playing(val voice: Voice) {
        var position = 0
        var gain = 1.0
        var fadeFrames = 0
        var exponential = true
        var step = 1.0
    }

    private val commands = ConcurrentLinkedQueue<Command>()
    private val playing = ArrayList<Playing>()
    private val lastOfCue = HashMap<Cue, Playing>()
    private val subsonicLeft = Biquad(FilterKind.Highpass, FLAT_Q).apply { tune(SUBSONIC_HZ) }
    private val subsonicRight = Biquad(FilterKind.Highpass, FLAT_Q).apply { tune(SUBSONIC_HZ) }

    fun add(voice: Voice) {
        commands.add(Command.Add(voice))
    }

    fun silence() {
        commands.add(Command.Silence)
    }

    /** Whether there is anything to mix or to take in. Read from the feeding thread. */
    val busy: Boolean get() = playing.isNotEmpty() || commands.isNotEmpty()

    private fun fade(p: Playing, seconds: Double, exponential: Boolean, floor: Double) {
        val frames = (seconds * SAMPLE_RATE).toInt().coerceAtLeast(1)
        p.fadeFrames = frames
        p.exponential = exponential
        p.step = if (exponential) (floor / p.gain).pow(1.0 / frames) else -p.gain / frames
    }

    private fun take(command: Command) {
        when (command) {
            is Command.Add -> {
                val voice = command.voice
                val next = Playing(voice)
                if (voice.replaces) {
                    val last = lastOfCue[voice.cue]
                    if (last != null && last.fadeFrames == 0) {
                        val floor = FADE_FLOOR / last.voice.master
                        if (last.gain > floor) fade(last, last.voice.fadeSeconds, exponential = true, floor = floor)
                    }
                    lastOfCue[voice.cue] = next
                }
                playing.add(next)
                if (playing.size > MAX_VOICES) playing.removeAt(0).also { dropped -> forget(dropped) }
            }
            Command.Silence -> {
                for (p in playing) fade(p, SILENCE_SECONDS, exponential = false, floor = 0.0)
                lastOfCue.clear()
            }
        }
    }

    private fun forget(p: Playing) {
        if (lastOfCue[p.voice.cue] === p) lastOfCue.remove(p.voice.cue)
    }

    /** Mixes [frames] frames of stereo into [out] (interleaved, `frames * 2` floats). */
    fun render(out: FloatArray, frames: Int) {
        while (true) take(commands.poll() ?: break)
        for (frame in 0 until frames) {
            var left = 0.0
            var right = 0.0
            for (p in playing) {
                val r = p.voice.rendered
                if (p.position >= r.frames) continue
                left += r.left[p.position] * p.gain
                right += r.right[p.position] * p.gain
                p.position++
                if (p.fadeFrames > 0) {
                    p.gain = if (p.exponential) p.gain * p.step else p.gain + p.step
                    if (--p.fadeFrames == 0) p.position = r.frames
                }
            }
            out[frame * 2] = softClip(subsonicLeft.process(left * outputGain)).toFloat()
            out[frame * 2 + 1] = softClip(subsonicRight.process(right * outputGain)).toFloat()
        }
        playing.removeAll { p ->
            val done = p.position >= p.voice.rendered.frames
            if (done) forget(p)
            done
        }
    }

    private companion object {
        /** More than this at once is a bug upstream; the oldest goes rather than the phone stalling. */
        const val MAX_VOICES = 24
    }
}
