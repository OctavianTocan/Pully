package com.octaviantocan.pulls.sound

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the port to the original. `cuelume-reference.json` holds what Cuelume 0.2.8 itself sounds like
 * (see `android/scripts/gen-cuelume-reference.mjs`), with Pulls' own Default tap rendered by the same
 * engine: every cue in every material, the emphases, the ways a cue bends to its context, a press and
 * its release, and a walk. Each is rendered here and its loudness over time must match within half a
 * decibel.
 */
class CuelumeFidelityTest {
    private val palette = Palette.parse(SoundFixtures.recipes)
    private val synth = Synth()
    private val half = Dice { 0.5 }
    private val fixture = Json.parseToJsonElement(SoundFixtures.reference).jsonObject.a("cases")

    private fun capital(text: String) = text.replaceFirstChar { it.uppercase() }

    private fun render(case: JsonObject): FloatArray {
        val options = case.o("options")
        val interaction = Interaction(
            direction = options.optional("direction").takeIf { it.isNotEmpty() }?.let { Direction.valueOf(capital(it)) },
            key = options.optional("key").takeIf { it.isNotEmpty() }?.let { Key.valueOf(capital(it)) },
            input = options.optional("input").takeIf { it.isNotEmpty() }?.let { Input.valueOf(capital(it)) },
            durationMs = if ("duration" in options) options.i("duration").coerceIn(COUNT_MIN_MS, COUNT_MAX_MS) else null,
        )
        val cue = Cue.valueOf(capital(case.s("cue")))
        val emphasis = Emphasis.valueOf(capital(case.s("emphasis")))
        val recipe = palette[Material.of(case.s("theme")), cue]
        val volume = case.d("volume")
        val since = if (case["since"] == null || case["since"] == JsonNull) Double.POSITIVE_INFINITY else case.d("since")
        val played = Played(recipe, volume, emphasis, shapeFor(cue, interaction, emphasis, since), case.i("step"))
        val part = when (case.s("part")) {
            "press" -> Part.Press
            "release" -> Part.Release
            else -> Part.Both
        }
        val master = recipe.masterGain * volume
        val rendered = synth.render(layersFor(played, part, half), master, ROOM_SEND * (recipe.room ?: 1.0), half)
        val frames = case.i("frames")
        val mixer = Mixer()
        if (rendered != null) mixer.add(Voice(cue, rendered, master, HANDOFF_SECONDS))
        return FloatArray(frames * 2).also { mixer.render(it, frames) }
    }

    @Test
    fun everyCaseSoundsLikeTheOriginal() {
        val worst = StringBuilder()
        assertTrue(fixture.size > 100)
        for (index in 0 until fixture.size) {
            val case = fixture[index].jsonObject
            val id = case.s("id")
            val out = render(case)
            val windows = case.a("windowsDb")
            val size = (0.02 * SAMPLE_RATE).toInt()
            for (w in 0 until windows.size) {
                var sum = 0.0
                for (i in w * size until (w + 1) * size) sum += out[i * 2].toDouble() * out[i * 2] + out[i * 2 + 1].toDouble() * out[i * 2 + 1]
                val mine = maxOf(-90.0, 20 * log10(sqrt(sum / (2 * size)).coerceAtLeast(1e-9)))
                val theirs = windows[w].jsonPrimitive.double
                // below -70 dB nothing is heard; the original's filters are not sample-exact there
                if (theirs > -70 && abs(mine - theirs) > 0.5) worst.append("$id window $w: $mine dB, original $theirs dB\n")
            }
            val peak = out.maxOf { abs(it) }
            assertEquals(case.d("peak"), peak.toDouble(), case.d("peak") * 0.02 + 1e-4, "$id peak")
        }
        assertEquals("", worst.toString())
    }
}

private fun JsonObject.o(key: String): JsonObject = getValue(key).jsonObject
private fun JsonObject.a(key: String): JsonArray = getValue(key).jsonArray
private fun JsonObject.s(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.d(key: String): Double = getValue(key).jsonPrimitive.double
private fun JsonObject.i(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.optional(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
