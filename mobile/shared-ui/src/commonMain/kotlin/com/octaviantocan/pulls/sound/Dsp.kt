package com.octaviantocan.pulls.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * The signal-processing pieces Cuelume gets from Web Audio and Pully has to bring itself: the
 * seeded noise, a biquad filter, a convolution reverb and the output stage's soft clip. Each one
 * follows the Web Audio specification, so a cue sounds as it does in a browser.
 */

/** Frames per second everything is rendered at. */
const val SAMPLE_RATE = 48_000

/** Cuelume's seeded generator (mulberry32): the noise and the room are the same on every machine. */
class SeededRandom(seed: Int) {
    private var state = seed

    fun next(): Double {
        state += 0x6d2b79f5
        var mixed = (state xor (state ushr 15)) * (1 or state)
        mixed = (mixed + (mixed xor (mixed ushr 7)) * (61 or mixed)) xor mixed
        return ((mixed xor (mixed ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
}

/** Seconds of shared noise. Noise layers loop it from a random point, so none is made per play. */
const val NOISE_SECONDS = 2.0

fun noiseTable(rate: Int = SAMPLE_RATE): FloatArray {
    val random = SeededRandom(1)
    return FloatArray(max(1, (NOISE_SECONDS * rate).toInt())) { (2 * random.next() - 1).toFloat() }
}

/** Lowpass Q in dB that gives a flat, unresonant corner. Web Audio reads a lowpass Q in dB. */
const val FLAT_Q = -3.0

enum class FilterKind { Lowpass, Bandpass, Highpass }

/**
 * Web Audio's `BiquadFilterNode`. A lowpass or highpass reads [q] in dB, a bandpass as a plain Q.
 * [tune] moves the corner; the state carries over, so a sweep is smooth.
 */
class Biquad(private val kind: FilterKind, private val q: Double, private val rate: Int = SAMPLE_RATE) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    /** A native output suspension discards its previous stream, including filter history. */
    fun clear() { z1 = 0.0; z2 = 0.0 }

    fun tune(frequency: Double) {
        val f = frequency.coerceIn(1.0, rate / 2.0 - 1.0)
        val w = 2 * PI * f / rate
        val cosW = cos(w)
        val sinW = sin(w)
        val alpha = if (kind == FilterKind.Bandpass) sinW / (2 * q) else sinW / (2 * 10.0.pow(q / 20))
        val a0 = 1 + alpha
        when (kind) {
            FilterKind.Lowpass -> {
                b0 = (1 - cosW) / 2 / a0
                b1 = (1 - cosW) / a0
                b2 = b0
            }
            FilterKind.Highpass -> {
                b0 = (1 + cosW) / 2 / a0
                b1 = -(1 + cosW) / a0
                b2 = b0
            }
            FilterKind.Bandpass -> {
                b0 = alpha / a0
                b1 = 0.0
                b2 = -alpha / a0
            }
        }
        a1 = -2 * cosW / a0
        a2 = (1 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * The shared room: a short, dense burst of reflections that dies away over [ROOM_SECONDS].
 * Reflections within the first 80 ms or so are what move a sound out of the listener's head;
 * a longer tail only adds distance. Its two channels are independent noise, so the room is wide
 * while the sound itself stays centred.
 */
const val ROOM_SECONDS = 0.25

/** The gap before the first reflection: a small room's nearest wall. */
const val ROOM_PREDELAY = 0.008

/** Reflections are low-passed here: walls swallow the highs first. */
const val ROOM_LOWPASS = 3500.0

/** How much of every cue reaches the room: about 22 dB down, faint enough to feel rather than hear. */
const val ROOM_SEND = 0.08
private const val ROOM_DECAY_DB = 60.0
private val ROOM_SEEDS = intArrayOf(3110, 5639)
private const val ROOM_REFLECTIONS_PER_SECOND = 48_000

fun fillRoom(data: FloatArray, rate: Int, seed: Int) {
    val random = SeededRandom(seed)
    val fall = ROOM_DECAY_DB / 20 * ln(10.0)
    val reflections = DoubleArray((ROOM_SECONDS * ROOM_REFLECTIONS_PER_SECOND).toInt())
    var energy = 0.0
    for (i in reflections.indices) {
        val t = i.toDouble() / ROOM_REFLECTIONS_PER_SECOND
        reflections[i] = if (t < ROOM_PREDELAY) 0.0 else (2 * random.next() - 1) * exp(-fall * t / ROOM_SECONDS)
        energy += reflections[i] * reflections[i]
    }
    val scale = 1 / sqrt(energy)
    for (i in reflections.indices) {
        val at = i.toDouble() / ROOM_REFLECTIONS_PER_SECOND * rate
        val sample = floor(at).toInt()
        val late = at - sample
        if (sample < data.size) data[sample] += (reflections[i] * scale * (1 - late)).toFloat()
        if (sample + 1 < data.size) data[sample + 1] += (reflections[i] * scale * late).toFloat()
    }
}

/** A power-of-two complex FFT, in place. */
class Fft(val size: Int) {
    private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { -sin(2 * PI * it / size) }

    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two" }
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = if (inverse) -sinTable[k * step] else sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += length
            }
            length *= 2
        }
        if (inverse) {
            for (i in 0 until size) {
                re[i] /= size
                im[i] /= size
            }
        }
    }
}

/**
 * The room as a stereo convolution. A mono signal goes in, a left and a right come out. Both
 * channels run through one FFT at a time: the impulse is packed as `left + i * right`, and since the
 * signal is real, the real part of the product is the left channel and the imaginary part the right.
 */
class Room(val rate: Int = SAMPLE_RATE) {
    val length = max(1, (ROOM_SECONDS * rate).toInt())
    private val impulse = Array(2) { channel -> FloatArray(length).also { fillRoom(it, rate, ROOM_SEEDS[channel]) } }
    private val spectra = HashMap<Int, Triple<Fft, DoubleArray, DoubleArray>>()
    private val spectrumGuard = CueGuard()

    private fun spectrum(size: Int): Triple<Fft, DoubleArray, DoubleArray> = spectrumGuard.locked {
        spectra.getOrPut(size) {
            val fft = Fft(size)
            val re = DoubleArray(size)
            val im = DoubleArray(size)
            for (i in 0 until length) {
                re[i] = impulse[0][i].toDouble()
                im[i] = impulse[1][i].toDouble()
            }
            fft.transform(re, im, inverse = false)
            Triple(fft, re, im)
        }
    }

    /** [signal] convolved with the room: `signal.size + length - 1` frames per channel. */
    fun reverberate(signal: FloatArray): Pair<FloatArray, FloatArray> {
        val total = signal.size + length - 1
        var size = 2
        while (size < total) size *= 2
        val (fft, hRe, hIm) = spectrum(size)
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (i in signal.indices) re[i] = signal[i].toDouble()
        fft.transform(re, im, inverse = false)
        for (i in 0 until size) {
            val r = re[i] * hRe[i] - im[i] * hIm[i]
            im[i] = re[i] * hIm[i] + im[i] * hRe[i]
            re[i] = r
        }
        fft.transform(re, im, inverse = true)
        return FloatArray(total) { re[it].toFloat() } to FloatArray(total) { im[it].toFloat() }
    }
}

/** The output stage's gain, before the clip. */
const val OUTPUT_GAIN = 5.8
const val CLIP_KNEE = 0.7

/** Below this nothing is heard, but deep notes would still spend headroom on it. */
const val SUBSONIC_HZ = 40.0

/** The soft clip every cue leaves through: linear to the knee, then a rounded shoulder that never passes 0.85. */
fun softClip(level: Double): Double {
    val x = level.coerceIn(-1.0, 1.0)
    val over = abs(x) - CLIP_KNEE
    if (over <= 0) return x
    val shoulder = 1 - CLIP_KNEE
    return sign(x) * (CLIP_KNEE + over - over * over / (2 * shoulder))
}
