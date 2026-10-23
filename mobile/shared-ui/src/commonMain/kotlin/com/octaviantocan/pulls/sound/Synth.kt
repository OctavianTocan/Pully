package com.octaviantocan.pulls.sound

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Rendering, Cuelume's `audio/render.ts` done offline: one shaped list of layers becomes samples.
 * A layer is a sine or a burst of filtered noise, swelling in along a straight line and dying away
 * exponentially. A straight swell is heard from its first moment; an exponential one stays silent for
 * most of its length and then jumps in, heard as a late second hit. Every cue rings faintly into the
 * shared room, so it sounds placed in a space. What Web Audio builds as a graph is computed here
 * sample by sample, to the same formulas.
 */

/** One play, before the output stage: the dry cue with its room, in two channels, gain already applied. */
class Rendered(val left: FloatArray, val right: FloatArray) {
    val frames: Int get() = left.size
}

/** A source runs this long past its envelope's end. */
const val SOURCE_STOP_PADDING = 0.05

private const val DECAY_DB = 60.0

/** The time constant of an exponential that falls [decay] seconds to -60 dB. */
fun fallTime(decay: Double): Double = decay / (DECAY_DB / 20 * ln(10.0))

/** Where a value sits partway through an exponential ramp from [from] to [to] over [time] seconds, held after. */
private fun glideAt(t: Double, from: Double, to: Double?, time: Double): Double =
    if (to == null || time <= 0) from else from * (to / from).pow(minOf(t, time) / time)

/** How the glide time defaults: the layer's whole length. */
private fun glideTime(layer: Layer) = layer.glideTime ?: (layer.attack + layer.decay)

/** A layer's envelope: a straight swell to its peak, then an exponential fall. */
private fun envelope(layer: Layer, t: Double, tau: Double): Double =
    if (t < layer.attack) layer.peak * t / layer.attack else layer.peak * exp(-(t - layer.attack) / tau)

private fun frames(seconds: Double) = ceil(seconds * SAMPLE_RATE).toInt()

private fun lengthOf(layer: Layer) = frames(layer.attack + layer.decay + SOURCE_STOP_PADDING)

private fun startOf(layer: Layer) = ((layer.offset ?: 0.0) * SAMPLE_RATE).roundToInt()

class Synth(private val noise: FloatArray = noiseTable(), val room: Room = Room()) {
    private fun tone(layer: Layer, out: FloatArray, start: Int) {
        val tau = fallTime(layer.decay)
        val gliding = layer.glideTo != null
        val time = glideTime(layer)
        var phase = 0.0
        for (i in 0 until lengthOf(layer)) {
            val t = i.toDouble() / SAMPLE_RATE
            val frequency = if (gliding) glideAt(t, layer.frequency, layer.glideTo, time) else layer.frequency
            out[start + i] += (sin(2 * PI * phase) * envelope(layer, t, tau)).toFloat()
            phase += frequency / SAMPLE_RATE
            if (phase >= 1) phase -= 1
        }
    }

    private fun noise(layer: Layer, out: FloatArray, start: Int, dice: Dice) {
        val tau = fallTime(layer.decay)
        val kind = if (layer.filterType == FilterType.Lowpass) FilterKind.Lowpass else FilterKind.Bandpass
        val filter = Biquad(kind, layer.filterQ ?: 1.0)
        val gliding = layer.glideTo != null
        val time = glideTime(layer)
        // a different stretch of the same noise every time
        var at = (dice.roll() * NOISE_SECONDS * SAMPLE_RATE).toInt().coerceIn(0, noise.size - 1)
        filter.tune(layer.filterFrequency)
        for (i in 0 until lengthOf(layer)) {
            val t = i.toDouble() / SAMPLE_RATE
            if (gliding && i % RETUNE_EVERY == 0) filter.tune(glideAt(t, layer.filterFrequency, layer.glideTo, time))
            out[start + i] += (filter.process(noise[at].toDouble()) * envelope(layer, t, tau)).toFloat()
            if (++at == noise.size) at = 0
        }
    }

    /**
     * Renders [layers] at [master] gain, with [roomSend] of the signal sent into the room. Returns null
     * when there is nothing to hear.
     */
    fun render(layers: List<Layer>, master: Double, roomSend: Double, dice: Dice): Rendered? {
        if (layers.isEmpty()) return null
        val dryFrames = layers.maxOf { startOf(it) + lengthOf(it) }
        val dry = FloatArray(dryFrames)
        for (layer in layers) {
            if (layer.kind == Kind.Tone) tone(layer, dry, startOf(layer)) else noise(layer, dry, startOf(layer), dice)
        }
        for (i in dry.indices) dry[i] *= master.toFloat()

        // the send reaches the room through its walls, which swallow the highs
        val walls = Biquad(FilterKind.Lowpass, FLAT_Q).apply { tune(ROOM_LOWPASS) }
        val send = FloatArray(dryFrames) { (walls.process(dry[it].toDouble()) * roomSend).toFloat() }
        val (wetLeft, wetRight) = room.reverberate(send)
        for (i in dry.indices) {
            wetLeft[i] += dry[i]
            wetRight[i] += dry[i]
        }
        return Rendered(wetLeft, wetRight)
    }

    private companion object {
        /** A sweeping filter is retuned this often, in frames: smooth to the ear, cheap to compute. */
        const val RETUNE_EVERY = 8
    }
}
