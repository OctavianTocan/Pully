package com.octaviantocan.pulls.sound

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The engine's bookkeeping: what plays, what does not, and what a press remembers for its release. */
class CuelumeTest {
    private val palette = Palette.parse(SoundFixtures.recipes)
    private var clock = 0.0
    private var woken = 0
    private val synth = Synth()

    private fun engine(worker: CueWorker = CueWorker { it() }) =
        Cuelume(palette, Mixer(), synth, worker, onVoice = { woken++ }, now = { clock }, dice = { 0.5 })

    private fun Cuelume.heard(frames: Int = 24_000): Float {
        val out = FloatArray(frames * 2)
        mixer.render(out, frames)
        return out.maxOf { abs(it) }
    }

    @Test
    fun playingACueRendersItIntoTheMixer() {
        val sounds = engine()
        sounds.play(Cue.Tap)
        assertEquals(1, woken)
        assertTrue(sounds.mixer.busy)
        assertTrue(sounds.heard() > 0.01f)
    }

    @Test
    fun withSoundOffNothingPlays() {
        val sounds = engine()
        sounds.enabled = false
        sounds.play(Cue.Tap)
        assertEquals(0, woken)
        assertEquals(0f, sounds.heard(480))
        assertFalse(sounds.mixer.busy)
        sounds.enabled = true
        sounds.play(Cue.Tap)
        assertEquals(1, woken)
    }

    @Test
    fun stoppedNativeOutputCannotReplayItsUnconsumedMuteFadeAfterReenable() {
        val sounds = engine()
        sounds.play(Cue.Success)
        assertTrue(sounds.heard(480) > 0f)
        sounds.enabled = false
        // Native output consumes no samples while disabled.
        // Its next activation discards any fade it did not consume.
        sounds.stop()
        sounds.enabled = true
        assertEquals(0f, sounds.heard(480))
        assertFalse(sounds.mixer.busy)
        sounds.play(Cue.Tap)
        assertTrue(sounds.heard(480) > 0f)
    }

    @Test
    fun aMutedVolumePlaysNothing() {
        val sounds = engine()
        sounds.play(Cue.Tap, PlayOptions(volume = 0f))
        sounds.volume = 0f
        sounds.play(Cue.Tap)
        assertEquals(0, woken)
    }

    @Test
    fun theMaterialIsHeard() {
        val sounds = engine()
        sounds.play(Cue.Success)
        val warm = sounds.heard()
        val other = engine()
        other.material = Material.Mech
        other.play(Cue.Success)
        assertTrue(abs(other.heard() - warm) > 0.01f)
    }

    @Test
    fun aPressRemembersItselfForItsRelease() {
        val sounds = engine()
        sounds.material = Material.Press
        sounds.press(Cue.Tap)
        assertEquals(1, woken)
        sounds.release(Cue.Tap)
        assertEquals(2, woken)
        // nothing is held any more, so a second release plays nothing
        sounds.release(Cue.Tap)
        assertEquals(2, woken)
    }

    @Test
    fun aReleaseWithNoPressPlaysNothing() {
        val sounds = engine()
        sounds.release(Cue.Tap)
        assertEquals(0, woken)
    }

    @Test
    fun overlappingGesturesLetGoOfTheirOwnPress() {
        val sounds = engine()
        sounds.material = Material.Press
        sounds.press(Cue.Type, gesture = "a")
        sounds.press(Cue.Type, gesture = "b")
        sounds.release(Cue.Type, gesture = "a")
        sounds.release(Cue.Type, gesture = "a")
        sounds.release(Cue.Type, gesture = "b")
        assertEquals(4, woken)
    }

    @Test
    fun turningSoundOffForgetsPresses() {
        val sounds = engine()
        sounds.press(Cue.Tap)
        sounds.enabled = false
        sounds.enabled = true
        sounds.release(Cue.Tap)
        assertEquals(1, woken)
    }

    /** Everything the cue sounds, rung out to its end so the next play starts from silence. */
    private fun Cuelume.sample(): FloatArray = FloatArray(2 * 4 * SAMPLE_RATE).also { mixer.render(it, 4 * SAMPLE_RATE) }

    private fun difference(a: FloatArray, b: FloatArray) = a.indices.maxOf { abs(a[it] - b[it]) }

    @Test
    fun quickRepeatsWalkUpAPressMaterialsChordAndAPauseStartsOver() {
        val sounds = engine()
        sounds.material = Material.Press
        val heard = listOf(0.0, 300.0, 600.0, 3000.0).map { at ->
            clock = at
            sounds.play(Cue.Tap)
            sounds.sample()
        }
        // a pause of two seconds starts the walk again: the fourth is the first
        assertTrue(difference(heard[0], heard[3]) < 1e-5f)
        // each step along the walk is its own note
        assertTrue(difference(heard[0], heard[1]) > 0.005f)
        assertTrue(difference(heard[1], heard[2]) > 0.005f)
    }

    @Test
    fun aPlayQueuedWhenSoundGoesOffNeverSounds() {
        val queued = ArrayDeque<() -> Unit>()
        val sounds = engine(CueWorker { queued.add(it) })
        sounds.play(Cue.Tap)
        sounds.enabled = false
        queued.forEach { it() }
        assertEquals(0, woken)
        assertEquals(0f, sounds.heard(480))
        assertFalse(sounds.mixer.busy)
    }

    @Test
    fun aPlayOvertakenWhileItRendersNeverSounds() {
        lateinit var sounds: Cuelume
        sounds = Cuelume(palette, Mixer(), synth, { it() }, onVoice = { woken++ }, now = { clock }, dice = { sounds.enabled = false; 0.5 })
        sounds.play(Cue.Success)
        assertEquals(0, woken)
        assertEquals(0f, sounds.heard(480))
        assertFalse(sounds.mixer.busy)
    }

    @Test
    fun aBurstPastTheLimitIsDropped() {
        val queued = ArrayDeque<() -> Unit>()
        val sounds = engine(CueWorker { queued.add(it) })
        repeat(40) { sounds.play(Cue.Tap) }
        assertEquals(12, queued.size)
    }
}
