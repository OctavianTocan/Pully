package com.octaviantocan.pulls.sound

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ShapingTest {
    private val palette = Palette.parse(File("src/main/assets/cuelume/recipes.json").readText())
    private val steady = Dice { 0.5 }

    private fun layers(cue: Cue, emphasis: Emphasis = Emphasis.Normal, interaction: Interaction = Interaction(), since: Double = Double.POSITIVE_INFINITY, material: Material = Material.Default, step: Int = 0, part: Part = Part.Both): List<Layer> {
        val recipe = palette[material, cue]
        return layersFor(Played(recipe, 1.0, emphasis, shapeFor(cue, interaction, emphasis, since), step), part, steady)
    }

    @Test
    fun everyMaterialCarriesEveryCue() {
        for (material in Material.entries) {
            for (cue in Cue.entries) {
                val recipe = palette[material, cue]
                assertTrue(recipe.layers.isNotEmpty(), "$material $cue")
                assertTrue(recipe.layers.all { it.decay > 0 && it.attack > 0 && it.peak > 0 }, "$material $cue")
            }
        }
        assertEquals("0.2.8", palette.version)
    }

    @Test
    fun emphasisChoosesWhichLayersPlay() {
        // tap: subtle strips the nail's tick and the knock, strong adds a layer only it plays
        val subtle = layers(Cue.Tap, Emphasis.Subtle).size
        val normal = layers(Cue.Tap).size
        val strong = layers(Cue.Tap, Emphasis.Strong).size
        assertEquals(3, subtle)
        assertEquals(5, normal)
        assertEquals(7, strong)
    }

    @Test
    fun aPlainNormalPlayLeavesTheRecipeAsWritten() {
        val written = palette[Material.Default, Cue.Select].layers.filter { it.from != From.Strong }
        val played = layers(Cue.Select)
        assertEquals(written.size, played.size)
        for ((a, b) in written.zip(played)) {
            assertEquals(a.start, if (b.kind == Kind.Tone) b.frequency else b.filterFrequency, 1e-9)
            assertEquals(a.peak, b.peak, 1e-12)
            assertEquals(a.decay, b.decay, 1e-12)
        }
    }

    @Test
    fun subtleIsQuieterAndShorter() {
        val normal = layers(Cue.Success).first { it.kind == Kind.Tone }
        val subtle = layers(Cue.Success, Emphasis.Subtle).first { it.kind == Kind.Tone }
        assertEquals(normal.peak * 0.8, subtle.peak, 1e-12)
        assertEquals(normal.decay * 0.85, subtle.decay, 1e-12)
    }

    @Test
    fun aLaterOptionRisesAndAnEarlierOneFalls() {
        val flat = layers(Cue.Select).first { it.kind == Kind.Tone }.frequency
        val forward = layers(Cue.Select, interaction = Interaction(direction = Direction.Forward)).first { it.kind == Kind.Tone }.frequency
        val back = layers(Cue.Select, interaction = Interaction(direction = Direction.Back)).first { it.kind == Kind.Tone }.frequency
        assertEquals(flat * 1.05, forward, 1e-9)
        assertEquals(flat * 0.95, back, 1e-9)
    }

    @Test
    fun goingBackPlaysAGlideBackwards() {
        val forward = layers(Cue.Navigate).first { it.glideTo != null }
        val back = layers(Cue.Navigate, interaction = Interaction(direction = Direction.Back)).first { it.glideTo != null }
        assertEquals(forward.filterFrequency, back.glideTo!!, 1e-9)
        assertEquals(forward.glideTo!!, back.filterFrequency, 1e-9)
    }

    @Test
    fun aCountRollsAsLongAsItsAnimation() {
        val stretched = layers(Cue.Count, interaction = Interaction(durationMs = 1600)).filter { it.kind == Kind.Noise }.maxOf { it.decay }
        val plain = layers(Cue.Count).filter { it.kind == Kind.Noise }.maxOf { it.decay }
        assertEquals(plain * 2, stretched, 1e-9)
        assertEquals(COUNT_MAX_MS, Interaction.from(PlayOptions(durationMs = 99_999)).durationMs)
        assertEquals(COUNT_MIN_MS, Interaction.from(PlayOptions(durationMs = 1)).durationMs)
    }

    @Test
    fun touchSoundsSofterAndRounderThanAMouse() {
        val mouse = shapeFor(Cue.Tap, Interaction(input = Input.Mouse), Emphasis.Normal, Double.POSITIVE_INFINITY)
        val touch = shapeFor(Cue.Tap, Interaction(input = Input.Touch), Emphasis.Normal, Double.POSITIVE_INFINITY)
        assertTrue(touch.level < mouse.level && touch.bright < mouse.bright && touch.pitch < mouse.pitch)
    }

    @Test
    fun quickRepeatsGetLighter() {
        val slow = shapeFor(Cue.Tap, Interaction(), Emphasis.Normal, 400.0)
        val fast = shapeFor(Cue.Tap, Interaction(), Emphasis.Normal, 40.0)
        assertEquals(1.0, slow.level)
        assertEquals(0.78, fast.level, 1e-12)
        assertEquals(0.2, fast.tail, 1e-12)
        // a cue outside the cadence set does not lighten
        assertEquals(1.0, shapeFor(Cue.Success, Interaction(), Emphasis.Normal, 40.0).level)
    }

    @Test
    fun aReleaseLaysItselfAfterItsPress() {
        val both = layers(Cue.Tap, material = Material.Press, part = Part.Both)
        val press = layers(Cue.Tap, material = Material.Press, part = Part.Press)
        val release = layers(Cue.Tap, material = Material.Press, part = Part.Release)
        assertTrue(release.isNotEmpty() && press.isNotEmpty())
        assertEquals(press.size + release.size, both.size)
        assertTrue(press.all { (it.offset ?: 0.0) == 0.0 })
        assertTrue(release.all { (it.offset ?: 0.0) == 0.0 })
        assertEquals(RELEASE_GAP, both.last().offset!!, 1e-12)
    }

    @Test
    fun theNotesOfAPressWalkUpTheChord() {
        val first = layers(Cue.Tap, material = Material.Press, step = 0).first { it.note }.glideTo!!
        val second = layers(Cue.Tap, material = Material.Press, step = 1).first { it.note }.glideTo!!
        assertEquals(first * Math.pow(2.0, 4 / 12.0), second, 1e-6)
    }

    @Test
    fun aVaryingCueIsNeverTheSameTwice() {
        val recipe = palette[Material.Default, Cue.Tap]
        val shape = shapeFor(Cue.Tap, Interaction(), Emphasis.Normal, Double.POSITIVE_INFINITY)
        val low = layersFor(Played(recipe, 1.0, Emphasis.Normal, shape, 0), Part.Both) { 0.0 }
        val high = layersFor(Played(recipe, 1.0, Emphasis.Normal, shape, 0), Part.Both) { 0.99 }
        assertNotEquals(low.map { it.peak }, high.map { it.peak })
        // an outcome cue plays the same every time, so its meaning never blurs
        val outcome = palette[Material.Default, Cue.Success]
        val a = layersFor(Played(outcome, 1.0, Emphasis.Normal, Shape(), 0), Part.Both) { 0.0 }
        val b = layersFor(Played(outcome, 1.0, Emphasis.Normal, Shape(), 0), Part.Both) { 0.99 }
        assertEquals(a.map { it.peak }, b.map { it.peak })
    }
}
