package com.octaviantocan.pulls.sound

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/*
 * The engine, Cuelume's `audio/engine.ts`: it decides what to play (which recipe, how hard it
 * matters, how it bends to the interaction, where in its walk it is) and hands the synthesis to a
 * worker, which hands the finished voice to the [Mixer]. It never touches Android, so the whole
 * path from `play` to samples runs in a unit test.
 */
class Cuelume(
    private val palette: Palette,
    val mixer: Mixer = Mixer(),
    private val synth: Synth = Synth(),
    /** Runs the synthesis. One thread, so plays stay in order. */
    private val worker: Executor,
    /** Called once a voice is in the mixer, to wake whatever feeds the speaker. */
    private val onVoice: () -> Unit = {},
    private val now: () -> Double = { System.nanoTime() / 1_000_000.0 },
    private val dice: Dice = Dice { Random.nextDouble() },
) {
    /** Sound on or off. Off is heard at once: whatever is ringing fades, and a press waiting for its release is forgotten. */
    @Volatile
    var enabled = true
        set(value) {
            // Under the lock the worker takes to put a finished voice in the mixer, so a voice is either in
            // before the silence (and fades with it) or sees the new epoch and is dropped.
            synchronized(this) {
                field = value
                if (!value) {
                    held.clear()
                    epoch.incrementAndGet()
                    mixer.silence()
                }
            }
        }

    /** The material of future plays; sounds already ringing finish as they started. */
    @Volatile
    var material = Material.Default

    /** The volume multiplier of future plays, 0 to 1. */
    @Volatile
    var volume = 1f

    private val lastPlayedAt = HashMap<Cue, Double>()
    private val steps = HashMap<Cue, Int>()
    private val held = HashMap<String, Played>()

    /** Plays dropped on the way to the worker when sound is turned off. */
    private val epoch = AtomicInteger()

    /** Plays waiting for the worker; a burst past this is a bug, not a gesture. */
    private val pending = AtomicInteger()

    private fun sinceLastPlay(cue: Cue): Double {
        val at = now()
        val since = at - (lastPlayedAt[cue] ?: Double.NEGATIVE_INFINITY)
        lastPlayedAt[cue] = at
        return since
    }

    /** The next step of [cue]'s walk: on while repeats come quickly, back to the first after a pause. */
    private fun stepFor(cue: Cue, recipe: Recipe, sinceLastMs: Double): Int {
        val length = recipe.walk?.size ?: 0
        val step = if (length > 0 && sinceLastMs < WALK_GAP_MS) ((steps[cue] ?: -1) + 1) % length else 0
        steps[cue] = step
        return step
    }

    /** Plays [cue] now, with the user's settings. Safe anywhere: with sound off it plays nothing. */
    fun play(cue: Cue, options: PlayOptions = PlayOptions()) = start(cue, options, Part.Both, null)

    /**
     * Plays the press of a gesture that lets go later: call [release] with the same cue as it lifts.
     * [gesture] names what is pressing when several overlap, so each lets go of its own press.
     */
    fun press(cue: Cue, options: PlayOptions = PlayOptions(), gesture: String? = null) = start(cue, options, Part.Press, gesture)

    /** Lets go of the cue's last [press]. Plays nothing if that press already let go, or the material has no release. */
    fun release(cue: Cue, gesture: String? = null) = start(cue, PlayOptions(), Part.Release, gesture)

    @Synchronized
    private fun start(cue: Cue, options: PlayOptions, part: Part, gesture: String?) {
        if (!enabled) return
        val holding = if (gesture != null) "${cue.id}:$gesture" else cue.id
        val played: Played
        if (part == Part.Release) {
            played = held.remove(holding) ?: return
        } else {
            // the press before this one is over, even if this play turns out silent
            held.remove(holding)
            val volume = volume * options.volume.coerceIn(0f, 1f)
            if (volume == 0f) return
            val recipe = palette[options.material ?: material, cue]
            val since = sinceLastPlay(cue)
            played = Played(recipe, volume.toDouble(), options.emphasis, shapeFor(cue, Interaction.from(options), options.emphasis, since), stepFor(cue, recipe, since))
            if (part == Part.Press) held[holding] = played
        }
        submit(cue, played, part)
    }

    private fun submit(cue: Cue, played: Played, part: Part) {
        if (pending.get() >= MAX_PENDING) return
        pending.incrementAndGet()
        val stamp = epoch.get()
        worker.execute {
            try {
                if (stamp != epoch.get()) return@execute
                val layers = layersFor(played, part, dice)
                val master = played.recipe.masterGain * played.volume
                val rendered = synth.render(layers, master, ROOM_SEND * (played.recipe.room ?: 1.0), dice) ?: return@execute
                val notes = played.recipe.layers.any { it.note }
                val voice = Voice(cue, rendered, master, if (notes) NOTE_HANDOFF_SECONDS else HANDOFF_SECONDS, replaces = part != Part.Release)
                synchronized(this) {
                    if (stamp != epoch.get()) return@execute
                    mixer.add(voice)
                }
                onVoice()
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /** Renders [cue] once without playing it, so the first real play is not the slow one. */
    fun warm() {
        worker.execute { synth.render(layersFor(Played(palette[Material.Default, Cue.Tap], 1.0, Emphasis.Normal, Shape(), 0), Part.Both, dice), 0.39, ROOM_SEND, dice) }
    }

    private companion object {
        /** Repeats closer than this walk on to the next step; a longer pause starts the walk again. */
        const val WALK_GAP_MS = 1000.0
        const val MAX_PENDING = 12
    }
}
