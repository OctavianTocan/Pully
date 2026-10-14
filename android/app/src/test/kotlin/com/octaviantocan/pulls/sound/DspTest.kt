package com.octaviantocan.pulls.sound

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pieces Web Audio would have supplied, checked against what Cuelume itself produces in a browser. */
class DspTest {
    @Test
    fun theSeededGeneratorIsCuelumes() {
        // Values read from cuelume 0.2.8's `seeded()`.
        val random = SeededRandom(1)
        assertEquals(0.627073940588161, random.next(), 1e-15)
        assertEquals(0.002735721180215, random.next(), 1e-15)
        assertEquals(0.527447039959952, random.next(), 1e-15)
        assertEquals(0.718196898698807, SeededRandom(3110).next(), 1e-15)
    }

    @Test
    fun theSharedNoiseIsCuelumes() {
        val noise = noiseTable()
        assertEquals(96_000, noise.size)
        assertEquals(0.25414788722991943f, noise[0])
        assertEquals(-0.9945285320281982f, noise[1])
        assertEquals(-0.3968795835971832f, noise[95_999])
    }

    @Test
    fun theRoomIsCuelumesAndCarriesUnitEnergy() {
        val impulse = FloatArray(12_000)
        fillRoom(impulse, SAMPLE_RATE, 3110)
        assertEquals(-0.015055135823786259f, impulse[400], 1e-7f)
        assertEquals(0.0007676983368583024f, impulse[5000], 1e-7f)
        assertEquals(1.0, impulse.sumOf { it.toDouble() * it }, 1e-6)
        assertTrue((0 until 384).all { impulse[it] == 0f }, "nothing arrives before the first wall")
    }

    @Test
    fun convolvingWithTheRoomMatchesTheDirectSum() {
        val room = Room()
        val left = FloatArray(12_000).also { fillRoom(it, SAMPLE_RATE, 3110) }
        val right = FloatArray(12_000).also { fillRoom(it, SAMPLE_RATE, 5639) }
        val signal = FloatArray(300) { sin(it * 0.3).toFloat() * (1 - it / 300f) }
        val (wetLeft, wetRight) = room.reverberate(signal)
        assertEquals(signal.size + 12_000 - 1, wetLeft.size)
        for (n in listOf(0, 9, 400, 1200, 5000, 11_000, 12_200)) {
            var directLeft = 0.0
            var directRight = 0.0
            for (k in signal.indices) {
                val at = n - k
                if (at in 0 until 12_000) {
                    directLeft += signal[k] * left[at]
                    directRight += signal[k] * right[at]
                }
            }
            assertEquals(directLeft, wetLeft[n].toDouble(), 1e-4, "left at $n")
            assertEquals(directRight, wetRight[n].toDouble(), 1e-4, "right at $n")
        }
    }

    private fun gain(filter: Biquad, hz: Double): Double {
        var peak = 0.0
        for (n in 0 until SAMPLE_RATE / 2) {
            val y = filter.process(sin(2 * PI * hz * n / SAMPLE_RATE))
            if (n > SAMPLE_RATE / 4) peak = maxOf(peak, abs(y))
        }
        return peak
    }

    @Test
    fun aLowpassPassesBelowItsCornerAndCutsAbove() {
        fun lowpass() = Biquad(FilterKind.Lowpass, FLAT_Q).apply { tune(1000.0) }
        assertEquals(1.0, gain(lowpass(), 100.0), 0.02)
        assertEquals(0.707, gain(lowpass(), 1000.0), 0.03)
        assertTrue(gain(lowpass(), 10_000.0) < 0.02)
    }

    @Test
    fun aBandpassPeaksAtItsCentre() {
        fun bandpass() = Biquad(FilterKind.Bandpass, 8.0).apply { tune(4000.0) }
        assertEquals(1.0, gain(bandpass(), 4000.0), 0.02)
        assertTrue(gain(bandpass(), 400.0) < 0.1)
    }

    @Test
    fun aHighpassCutsTheSubsonic() {
        val filter = Biquad(FilterKind.Highpass, FLAT_Q).apply { tune(SUBSONIC_HZ) }
        assertTrue(gain(filter, 10.0) < 0.1)
        assertEquals(1.0, gain(Biquad(FilterKind.Highpass, FLAT_Q).apply { tune(SUBSONIC_HZ) }, 1000.0), 0.01)
    }

    @Test
    fun theSoftClipIsLinearToTheKneeAndNeverPassesItsShoulder() {
        assertEquals(0.5, softClip(0.5))
        assertEquals(-0.5, softClip(-0.5))
        assertEquals(0.85, softClip(1.0), 1e-12)
        assertEquals(0.85, softClip(5.0), 1e-12)
        assertEquals(-0.85, softClip(-9.0), 1e-12)
        var last = -1.0
        for (step in 0..200) {
            val level = softClip(step / 100.0 - 1)
            assertTrue(level >= last)
            last = level
        }
    }

    @Test
    fun theSourceFilesExist() {
        assertTrue(File("src/main/assets/cuelume/recipes.json").isFile)
    }
}
