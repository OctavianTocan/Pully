package com.octaviantocan.pulls.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixerTest {
    /** A 1 kHz tone at [level] for [frames] frames, in both channels. (A flat level would be cut by the subsonic filter.) */
    private fun steady(frames: Int, level: Float = 0.1f) =
        Rendered(FloatArray(frames) { level * sin(2 * PI * 1000 * it / SAMPLE_RATE).toFloat() }, FloatArray(frames) { level * sin(2 * PI * 1000 * it / SAMPLE_RATE).toFloat() })

    private fun voice(cue: Cue, frames: Int, fade: Double = HANDOFF_SECONDS, replaces: Boolean = true, level: Float = 0.1f) =
        Voice(cue, steady(frames, level), master = 0.5, fadeSeconds = fade, replaces = replaces)

    /** Lets the subsonic filter's onset pass, then reads the steady level. */
    private fun settled(mixer: Mixer): Float {
        peak(mixer, 2000)
        return peak(mixer, 480)
    }

    private fun peak(mixer: Mixer, frames: Int): Float {
        val out = FloatArray(frames * 2)
        mixer.render(out, frames)
        return out.maxOf { abs(it) }
    }

    @Test
    fun anEmptyMixerIsIdleAndSilent() {
        val mixer = Mixer()
        assertFalse(mixer.busy)
        assertEquals(0f, peak(mixer, 480))
    }

    @Test
    fun aVoiceRingsUntilItEndsAndThenTheMixerIsIdleAgain() {
        val mixer = Mixer()
        mixer.add(voice(Cue.Tap, 1000))
        assertTrue(mixer.busy)
        assertTrue(peak(mixer, 480) > 0f)
        assertTrue(mixer.busy)
        peak(mixer, 600)
        assertFalse(mixer.busy)
    }

    @Test
    fun theOutputIsBoostedThenSoftClipped() {
        val mixer = Mixer()
        mixer.add(voice(Cue.Tap, 48_000, level = 1f))
        // far past the knee: it settles at the clip's shoulder, 0.85, never beyond
        val out = FloatArray(48_000 * 2)
        mixer.render(out, 48_000)
        assertEquals(0.85f, out.maxOf { it }, 1e-4f)
        assertTrue(out.all { abs(it) <= 0.85001f })
    }

    @Test
    fun theSameCueLetsItsLastPlayGoInEightyMilliseconds() {
        val mixer = Mixer()
        mixer.add(voice(Cue.Tap, 48_000))
        val before = settled(mixer)
        mixer.add(voice(Cue.Tap, 48_000, level = 0f))
        // 80 ms after the second play the first has faded to the floor, 80 dB down
        peak(mixer, (SAMPLE_RATE * HANDOFF_SECONDS).toInt())
        val after = peak(mixer, 480)
        assertTrue(before > 0.1f, "before $before")
        assertTrue(after < before * 0.001f, "after $after, before $before")
    }

    @Test
    fun anotherCueIsLeftAlone() {
        val mixer = Mixer()
        mixer.add(voice(Cue.Tap, 48_000))
        val before = settled(mixer)
        mixer.add(voice(Cue.Select, 48_000, level = 0f))
        peak(mixer, 10_000)
        assertEquals(before, peak(mixer, 480), before * 0.02f)
    }

    @Test
    fun aReleaseRingsWithItsPressInsteadOfReplacingIt() {
        val mixer = Mixer()
        mixer.add(voice(Cue.Tap, 48_000))
        val before = settled(mixer)
        mixer.add(voice(Cue.Tap, 48_000, replaces = false, level = 0f))
        peak(mixer, 10_000)
        assertEquals(before, peak(mixer, 480), before * 0.02f)
    }

    @Test
    fun aCueWithNotesLetsGoSlower() {
        val mixer = Mixer()
        mixer.add(voice(Cue.Tap, 96_000, fade = NOTE_HANDOFF_SECONDS))
        val before = settled(mixer)
        mixer.add(voice(Cue.Tap, 96_000, level = 0f))
        peak(mixer, (SAMPLE_RATE * HANDOFF_SECONDS).toInt())
        // an ordinary cue would be 80 dB down by now; a note is still 15 dB down, and falling
        assertTrue(peak(mixer, 480) > before * 0.05f, "still ringing at 80 ms")
        peak(mixer, (SAMPLE_RATE * NOTE_HANDOFF_SECONDS).toInt())
        assertTrue(peak(mixer, 480) < before * 0.001f, "gone by 400 ms")
    }

    @Test
    fun turningSoundOffFadesWhatRingsWithinFifteenMilliseconds() {
        val mixer = Mixer()
        mixer.add(voice(Cue.Tap, 96_000))
        mixer.add(voice(Cue.Success, 96_000))
        peak(mixer, 480)
        mixer.silence()
        peak(mixer, (SAMPLE_RATE * SILENCE_SECONDS).toInt() + 2)
        assertTrue(peak(mixer, 480) < 0.001f)
        assertFalse(mixer.busy)
    }
}
