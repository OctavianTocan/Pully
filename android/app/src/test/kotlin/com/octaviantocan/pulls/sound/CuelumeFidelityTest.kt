package com.octaviantocan.pulls.sound

import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the port to the original. `cuelume-reference.json` holds what Cuelume 0.2.8 itself sounds like
 * (see `android/scripts/gen-cuelume-reference.mjs`): every cue in every material, the emphases, the
 * ways a cue bends to its context, a press and its release, and a walk. Each is rendered here and its
 * loudness over time must match within half a decibel.
 */
class CuelumeFidelityTest {
    private val palette = Palette.parse(File("src/main/assets/cuelume/recipes.json").readText())
    private val synth = Synth()
    private val half = Dice { 0.5 }
    private val fixture = JSONObject(javaClass.getResource("/fixtures/cuelume-reference.json")!!.readText()).getJSONArray("cases")

    private fun capital(text: String) = text.replaceFirstChar { it.uppercase() }

    private fun render(case: JSONObject): FloatArray {
        val options = case.getJSONObject("options")
        val interaction = Interaction(
            direction = options.optString("direction").takeIf { it.isNotEmpty() }?.let { Direction.valueOf(capital(it)) },
            key = options.optString("key").takeIf { it.isNotEmpty() }?.let { Key.valueOf(capital(it)) },
            input = options.optString("input").takeIf { it.isNotEmpty() }?.let { Input.valueOf(capital(it)) },
            durationMs = if (options.has("duration")) options.getInt("duration").coerceIn(COUNT_MIN_MS, COUNT_MAX_MS) else null,
        )
        val cue = Cue.valueOf(capital(case.getString("cue")))
        val emphasis = Emphasis.valueOf(capital(case.getString("emphasis")))
        val recipe = palette[Material.of(case.getString("theme")), cue]
        val volume = case.getDouble("volume")
        val since = if (case.isNull("since")) Double.POSITIVE_INFINITY else case.getDouble("since")
        val played = Played(recipe, volume, emphasis, shapeFor(cue, interaction, emphasis, since), case.getInt("step"))
        val part = when (case.getString("part")) {
            "press" -> Part.Press
            "release" -> Part.Release
            else -> Part.Both
        }
        val master = recipe.masterGain * volume
        val rendered = synth.render(layersFor(played, part, half), master, ROOM_SEND * (recipe.room ?: 1.0), half)
        val frames = case.getInt("frames")
        val mixer = Mixer()
        if (rendered != null) mixer.add(Voice(cue, rendered, master, HANDOFF_SECONDS))
        return FloatArray(frames * 2).also { mixer.render(it, frames) }
    }

    @Test
    fun everyCaseSoundsLikeTheOriginal() {
        val worst = StringBuilder()
        assertTrue(fixture.length() > 100)
        for (index in 0 until fixture.length()) {
            val case = fixture.getJSONObject(index)
            val id = case.getString("id")
            val out = render(case)
            val windows = case.getJSONArray("windowsDb")
            val size = (0.02 * SAMPLE_RATE).toInt()
            for (w in 0 until windows.length()) {
                var sum = 0.0
                for (i in w * size until (w + 1) * size) sum += out[i * 2].toDouble() * out[i * 2] + out[i * 2 + 1].toDouble() * out[i * 2 + 1]
                val mine = maxOf(-90.0, 20 * log10(sqrt(sum / (2 * size)).coerceAtLeast(1e-9)))
                val theirs = windows.getDouble(w)
                // below -70 dB nothing is heard; the original's filters are not sample-exact there
                if (theirs > -70 && abs(mine - theirs) > 0.5) worst.append("$id window $w: $mine dB, original $theirs dB\n")
            }
            val peak = out.maxOf { abs(it) }
            assertEquals(case.getDouble("peak"), peak.toDouble(), case.getDouble("peak") * 0.02 + 1e-4, "$id peak")
        }
        assertEquals("", worst.toString())
    }
}
