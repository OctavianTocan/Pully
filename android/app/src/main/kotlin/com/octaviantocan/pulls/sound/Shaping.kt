package com.octaviantocan.pulls.sound

import kotlin.math.pow

/*
 * Context-aware shaping, Cuelume's `sounds/context.ts` and the layer half of `audio/render.ts`:
 *
 *     semantic cue + interaction context + emphasis = the layers that sound
 *
 * A shape scales a recipe's pitch, level and length, plus its bright layers (centred at or above
 * 2.5 kHz) and its tail. Every factor is curated and clamped, so a cue always stays recognisably
 * itself, and context that is missing leaves the canonical sound untouched.
 */

/** A source of numbers in [0, 1). Tests hand in a constant so a play is exactly repeatable. */
fun interface Dice {
    fun roll(): Double
}

/** What a binding knows about the interaction that played a cue. */
data class Interaction(
    val direction: Direction? = null,
    val key: Key? = null,
    val input: Input? = null,
    val durationMs: Int? = null,
) {
    companion object {
        /** The context a caller passed, the way Cuelume's `contextFrom` reads it. */
        fun from(options: PlayOptions) = Interaction(
            direction = options.direction,
            key = options.key,
            input = options.input,
            durationMs = options.durationMs?.coerceIn(COUNT_MIN_MS, COUNT_MAX_MS),
        )
    }
}

/** How a play bends its recipe. 1 leaves it alone. */
data class Shape(
    val pitch: Double = 1.0,
    val level: Double = 1.0,
    val length: Double = 1.0,
    val bright: Double = 1.0,
    val tail: Double = 1.0,
    /** Negative plays a glide backwards. */
    val sweep: Double = 1.0,
    val time: Double = 1.0,
) {
    operator fun times(other: Shape) = Shape(
        pitch * other.pitch, level * other.level, length * other.length, bright * other.bright,
        tail * other.tail, sweep * other.sweep, time * other.time,
    )
}

private const val BRIGHT_HZ = 2500.0

private val EMPHASIS = mapOf(
    Emphasis.Subtle to Shape(level = 0.8, length = 0.85, bright = 0.75),
    Emphasis.Normal to Shape(),
    Emphasis.Strong to Shape(pitch = 0.98, level = 1.08, length = 1.2, bright = 1.1),
)

/** `type`: bigger keys sound bigger; deleting sits lower than writing. */
private val KEYS = mapOf(
    Key.Printable to Shape(),
    Key.Space to Shape(pitch = 0.86, level = 1.08, length = 1.15),
    Key.Delete to Shape(pitch = 0.92, level = 0.9, bright = 0.8),
    Key.Enter to Shape(pitch = 0.8, level = 1.12, length = 1.25),
)

/** `tap`: the glass answers to what touched it. */
private val INPUTS = mapOf(
    Input.Mouse to Shape(),
    Input.Touch to Shape(pitch = 0.97, level = 0.92, bright = 0.45),
    Input.Pen to Shape(pitch = 1.02, bright = 1.3),
    Input.Keyboard to Shape(pitch = 0.94, length = 0.85, bright = 0.7),
)

/** `select`: a later option rises, an earlier one falls. Perceptible, never melodic. */
private const val DIRECTION_STEP = 0.05

/** Repeats closer than FAST_MS play fully lightened; slower than SLOW_MS, not at all. */
private const val FAST_MS = 70.0
private const val SLOW_MS = 220.0
private val CADENCE_CUES = setOf(Cue.Type, Cue.Select, Cue.Tap, Cue.Toggle, Cue.Navigate)

/** `count` is written at COUNT_MS; a duration stretches it within these bounds. */
private const val COUNT_MS = 800.0
const val COUNT_MIN_MS = 300
const val COUNT_MAX_MS = 2000

/** Going back plays these cues' glides backwards, so they fall. */
private val REVERSIBLE = setOf(Cue.Navigate, Cue.Toggle, Cue.Count)

private fun cadence(sinceLastMs: Double): Shape {
    val speed = ((SLOW_MS - sinceLastMs) / (SLOW_MS - FAST_MS)).coerceIn(0.0, 1.0)
    return Shape(level = 1 - 0.22 * speed, length = 1 - 0.25 * speed, bright = 1 - 0.2 * speed, tail = 1 - 0.8 * speed)
}

/** The combined shape for one play of [cue]. [sinceLastMs] is the time since the same cue last played. */
fun shapeFor(cue: Cue, context: Interaction, emphasis: Emphasis, sinceLastMs: Double): Shape {
    var shape = EMPHASIS.getValue(emphasis)
    if (cue in CADENCE_CUES) shape *= cadence(sinceLastMs)
    if (cue == Cue.Type && context.key != null) shape *= KEYS.getValue(context.key)
    if (cue == Cue.Tap && context.input != null) shape *= INPUTS.getValue(context.input)
    if (cue == Cue.Select && context.direction != null) shape *= Shape(pitch = 1 + DIRECTION_STEP * context.direction.sign)
    if (cue == Cue.Count && context.durationMs != null) shape *= Shape(time = context.durationMs / COUNT_MS)
    if (cue in REVERSIBLE && context.direction == Direction.Back) shape *= Shape(sweep = -1.0)
    return shape
}

/** What a shape does to one layer, bounded so no context can run away with a cue. */
private class Factors(val pitch: Double, val gain: Double, val length: Double)

private fun factors(layer: Layer, shape: Shape): Factors {
    val gain = shape.level * (if (layer.start >= BRIGHT_HZ) shape.bright else 1.0) * (if ((layer.offset ?: 0.0) > 0) shape.tail else 1.0)
    return Factors(shape.pitch.coerceIn(0.75, 1.25), gain.coerceIn(0.0, 1.5), shape.length.coerceIn(0.6, 1.6))
}

/** The layers an emphasis plays: subtle strips ornament, strong adds layers of its own. */
fun arrangement(layers: List<Layer>, emphasis: Emphasis): List<Layer> =
    layers.filter { emphasis.ordinal >= (it.from?.ordinal ?: 0) }

/** One play, decided: the recipe, how loud, how hard it matters, how it bends, and where in its walk it is. */
data class Played(val recipe: Recipe, val volume: Double, val emphasis: Emphasis, val shape: Shape, val step: Int)

enum class Part { Both, Press, Release }

/** Within one strike, each layer's ring length moves by up to this: same object, a slightly different hit. */
private const val DECAY_JITTER = 0.1

/** And each tone's pitch by up to this, about 9 cents: the object's modes never sit exactly the same twice. */
private const val MODE_JITTER = 0.005

/** The press lets go this long after it presses; a play with no real let-go waits this long (seconds). */
const val RELEASE_GAP = 0.09

private class Strike(val pitch: Double, val force: Double)

/**
 * A copy of [layer] bent by the play's shape and its strike. A cue with `vary` also moves each layer's
 * ring and tone a little on its own, so no two plays match; one without plays exactly. A harder strike
 * raises a noise layer's filter with its level: louder is brighter. A reversed sweep starts where it
 * would have ended. `time` moves every layer's start, and a `stretch` layer lasts as long as the count.
 */
private fun shaped(layer: Layer, shape: Shape, strike: Strike, vary: Vary?, walk: Double, dice: Dice): Layer {
    val factors = factors(layer, shape)
    val nudge = { amount: Double -> 1 + (dice.roll() * 2 - 1) * amount }
    val tone = layer.kind == Kind.Tone
    val tune = if (tone) (if (vary != null) nudge(MODE_JITTER) else 1.0) else strike.force
    val pitch = factors.pitch * strike.pitch * tune * (if (layer.note) walk else 1.0)
    val peak = layer.peak * factors.gain * strike.force
    val stretch = if (layer.stretch) shape.time else 1.0
    val decay = layer.decay * factors.length * stretch * (if (vary != null) nudge(DECAY_JITTER) else 1.0)
    val start = layer.start
    val reversed = shape.sweep < 0 && layer.glideTo != null
    val from = if (reversed) layer.glideTo!! else start
    val to = if (reversed) start else layer.glideTo
    val glideTo = to?.let { it * pitch }
    val offset = (layer.offset ?: 0.0) * shape.time
    val attack = layer.attack * stretch
    val glideTime = layer.glideTime?.let { it * stretch }
    return if (tone) {
        layer.copy(offset = offset, attack = attack, glideTime = glideTime, frequency = from * pitch, glideTo = glideTo, peak = peak, decay = decay)
    } else {
        layer.copy(offset = offset, attack = attack, glideTime = glideTime, filterFrequency = from * pitch, glideTo = glideTo, peak = peak, decay = decay)
    }
}

/**
 * The layers [part] of one play sounds: arranged for its emphasis, bent by its shape, strike and
 * walk, the release starting as the gesture lets go.
 */
fun layersFor(played: Played, part: Part, dice: Dice): List<Layer> {
    val (recipe, _, emphasis, shape, step) = played
    val parts = buildList {
        if (part != Part.Release) add(recipe.layers to 0.0)
        if (part != Part.Press && recipe.release != null) add(recipe.release to if (part == Part.Both) RELEASE_GAP else 0.0)
    }
    val walk = 2.0.pow((recipe.walk?.getOrNull(step) ?: 0) / 12.0)
    val vary = recipe.vary
    val strike = if (vary != null) {
        Strike(1 + (dice.roll() * 2 - 1) * vary.pitch, 1 + (dice.roll() * 2 - 1) * vary.level)
    } else {
        Strike(1.0, 1.0)
    }
    return parts.flatMap { (layers, delay) ->
        arrangement(layers, emphasis).map { layer ->
            val bent = shaped(layer, shape, strike, vary, walk, dice)
            bent.copy(offset = (bent.offset ?: 0.0) + delay)
        }
    }
}
